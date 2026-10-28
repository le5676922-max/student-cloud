#!/usr/bin/env python3
"""Read-only static behavior inventory. Never imports or executes legacy code.

Run from any directory with Python 3.10+: python backend-independent/tools/extract_legacy_contracts.py
Inputs are allowlisted tracked source paths. No environment, secret, runtime data,
network, Java build, application process, or database is accessed.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess

BASELINE = "fbb1610"
MAPPINGS = {"GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
            "DeleteMapping": "DELETE", "PatchMapping": "PATCH", "RequestMapping": None}


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args])


def compact(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def without_comments(text: str) -> str:
    # Keep offsets/line numbers and quoted strings. No source is evaluated.
    pattern = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`|/\*.*?\*/|//[^\r\n]*', re.S)
    return pattern.sub(lambda m: re.sub(r"[^\r\n]", " ", m[0]) if m[0].startswith(("//", "/*")) else m[0], text)


def balanced(text: str, start: int, opening="(", closing=")") -> int:
    depth = 0
    quote = None
    escaped = False
    for pos in range(start, len(text)):
        char = text[pos]
        if quote:
            if escaped: escaped = False
            elif char == "\\": escaped = True
            elif char == quote: quote = None
            continue
        if char in "\"'`": quote = char
        elif char == opening: depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0: return pos + 1
    raise ValueError("Unbalanced source expression")


def split_args(text: str) -> list[str]:
    parts, start, depth, quote, escape = [], 0, 0, None, False
    for pos, char in enumerate(text):
        if quote:
            if escape: escape = False
            elif char == "\\": escape = True
            elif char == quote: quote = None
            continue
        if char in "\"'`": quote = char
        elif char in "([{": depth += 1
        elif char in ")]}": depth -= 1
        elif char == "," and depth == 0:
            parts.append(text[start:pos].strip()); start = pos + 1
    if text[start:].strip(): parts.append(text[start:].strip())
    return parts


def quoted(text: str) -> list[str]:
    return re.findall(r'"((?:\\.|[^"\\])*)"', text)


def annotation_values(args: str, key: str) -> list[str]:
    for item in split_args(args):
        if re.match(rf"{key}\s*=", item): return quoted(item.split("=", 1)[1])
    return []


def mapping_paths(args: str, constants: dict[str, str]) -> tuple[list[str], bool]:
    expressions = [part.split("=", 1)[1].strip() for part in split_args(args)
                   if re.match(r"(?:value|path)\s*=", part)]
    if not expressions:
        positional = [part for part in split_args(args) if "=" not in part]
        expressions = positional[:1] or ['""']
    output, unresolved = [], False
    for expression in expressions:
        expression = expression.strip().removeprefix("{").removesuffix("}")
        for element in split_args(expression):
            pieces = [part.strip() for part in element.split("+")]
            value = ""
            for piece in pieces:
                if re.fullmatch(r'"(?:\\.|[^"\\])*"', piece): value += piece[1:-1]
                elif piece in constants:
                    value += constants[piece]
                    if "${AMBIGUOUS:" in constants[piece]: unresolved = True
                else: value += "${" + piece + "}"; unresolved = True
            output.append(value)
    return output or [""], unresolved


def annotations(text: str):
    for match in re.finditer(r"@([A-Za-z_$][\w.$]*)", text):
        end = match.end()
        while end < len(text) and text[end].isspace(): end += 1
        args = ""
        if end < len(text) and text[end] == "(":
            stop = balanced(text, end); args = text[end + 1:stop - 1]; end = stop
        yield {"name": match[1].split(".")[-1], "start": match.start(), "end": end, "args": args}


def java_inventory(path: str, source: str, global_constants: dict[str, str]) -> list[dict]:
    text = without_comments(source)
    global_constants = dict(global_constants)
    for constant in re.finditer(r'\b(?:public\s+)?(?:private\s+)?static\s+final\s+String\s+(\w+)\s*=\s*"([^"\r\n]*)"', text):
        global_constants[constant[1]] = constant[2]
    type_match = re.search(r"\b(?:class|interface)\s+(\w+)", text)
    if not type_match: return []
    ann = list(annotations(text))
    mapped = [item for item in ann if item["name"] in MAPPINGS]
    if not mapped: return []
    class_mapping = [item for item in mapped if item["start"] < type_match.start()]
    base, base_unresolved = mapping_paths(class_mapping[-1]["args"], global_constants) if class_mapping else ([""], False)
    class_security = [compact(text[item["start"]:item["end"]]) for item in ann
                      if item["start"] < type_match.start() and item["name"] in {"SaCheckLogin", "SaCheckRole", "SaCheckPermission"}]
    methods = []
    method_pattern = re.compile(r"\b(?:public|protected)\s+(?:(?:static|final|synchronized|abstract)\s+)*(?:<[^;{}]*?>\s*)?([\w.$<>?,\[\] \r\n\t]+?)\s+(\w+)\s*\(")
    for match in method_pattern.finditer(text, type_match.end()):
        end = balanced(text, match.end() - 1)
        methods.append((match, end))
    output = []
    for item in mapped:
        if item["start"] < type_match.start(): continue
        associated = next(((match, end) for match, end in methods if match.start() > item["start"]), None)
        if not associated: continue
        match, end = associated
        # Find all metadata annotations associated with this declaration.
        prior_body = text.rfind("}", 0, item["start"])
        start = max(type_match.end(), prior_body + 1)
        metadata = [entry for entry in ann if start <= entry["start"] < match.start()]
        suffixes, unresolved = mapping_paths(item["args"], global_constants)
        verbs = [MAPPINGS[item["name"]]] if MAPPINGS[item["name"]] else re.findall(r"RequestMethod\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)", item["args"])
        if not verbs: verbs = ["ANY"]
        declared_return = compact(match[1])
        response_kind = "json-envelope" if "AjaxJson" in declared_return else "server-sent-events" if "SseEmitter" in declared_return else "raw-or-redirect"
        body_start = text.find("{", end)
        method_body = text[body_start:balanced(text, body_start, "{", "}")] if body_start >= 0 else ""
        factories = sorted(set(re.findall(r"AjaxJson\.(\w+)\s*\(", method_body)))
        for prefix in base:
            for suffix in suffixes:
                route = "/" + "/".join(part.strip("/") for part in [prefix, suffix] if part.strip("/"))
                for verb in verbs:
                    output.append({"method": verb, "path": route, "controller": type_match[1], "handler": match[2],
                                   "source": path, "line": source[:item["start"]].count("\n") + 1,
                                   "declaredReturnType": declared_return, "responseKind": response_kind,
                                   "parameterSignature": compact(text[match.end():end - 1]),
                                   "pathVariables": re.findall(r"\{([^}]+)\}", route),
                                   "consumes": annotation_values(item["args"], "consumes"),
                                   "produces": annotation_values(item["args"], "produces"),
                                   "responseFactories": factories,
                                   "securityAnnotations": class_security + [compact(text[e["start"]:e["end"]]) for e in metadata if e["name"] in {"SaCheckLogin", "SaCheckRole", "SaCheckPermission", "CheckPassword"}],
                                   "unresolvedPathExpression": base_unresolved or unresolved,
                                   "migrationStatus": "pending"})
    return output


def js_functions(source: str) -> list[dict]:
    text = without_comments(source)
    pattern = re.compile(r"(?m)^(?P<export>export\s+)?(?:(?:async\s+)?function\s+(?P<function>\w+)\s*\(|(?:const|let)\s+(?P<arrow>\w+)\s*=\s*(?:async\s*)?\()")
    matches = list(pattern.finditer(text)); result = []
    for index, match in enumerate(matches):
        end = balanced(text, match.end() - 1)
        limit = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        body = text[end:limit]
        result.append({"name": match["function"] or match["arrow"], "exported": bool(match["export"]),
                       "line": text[:match.start()].count("\n") + 1, "offset": match.start(),
                       "parameters": split_args(text[match.end():end - 1]), "body": body})
    return result


def js_value(expression: str) -> tuple[str, bool]:
    expression = expression.strip()
    if expression and expression[0] in "\"'`" and expression[-1:] == expression[0]:
        return expression[1:-1], "${" in expression
    return expression, True


def frontend_inventory(path: str, source: str) -> list[dict]:
    definitions = js_functions(source); records = []
    helpers = {}
    for function in definitions:
        body = function["body"]
        urls = []
        for match in re.finditer(r"\burl\s*:\s*(`(?:\\.|[^`\\])*`|'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"|\w+)", body):
            value, dynamic = js_value(match[1]); tail = body[max(0, match.start() - 100):match.end() + 250]
            method = re.search(r"\bmethod\s*:\s*['\"]([A-Za-z]+)['\"]", tail)
            named = re.search(r"\bmethod\s*:\s*(\w+)", tail)
            verb = method[1].upper() if method else "${" + named[1] + "}" if named else "${method}" if re.search(r"\bmethod\s*[,}]", tail) else "UNKNOWN"
            urls.append((value, dynamic, verb))
        for match in re.finditer(r"\bfetch\s*\(\s*(`(?:\\.|[^`\\])*`|'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\")", body):
            value, dynamic = js_value(match[1]); method = re.search(r"method\s*:\s*['\"](\w+)['\"]", body[match.end():match.end() + 150])
            urls.append((value, dynamic, method[1].upper() if method else "GET"))
        # Dynamic raw Axios URLs are retained as unresolved rather than invented endpoints.
        for match in re.finditer(r"\b\w+\.(get|post|put|delete)\s*\(\s*(\w+)\s*,", body):
            urls.append(("${" + match[2] + "}", True, match[1].upper()))
        if urls: helpers[function["name"]] = (function, urls)
        for value, dynamic, verb in urls:
            records.append({"source": path, "function": function["name"], "line": function["line"],
                            "exported": function["exported"], "pathExpression": value, "method": verb,
                            "dynamic": dynamic, "requestParameters": function["parameters"],
                            "kind": "direct-or-wrapper", "migrationStatus": "pending"})
    # Resolve same-file convenience functions calling the known request wrappers.
    for function in definitions:
        for helper_name, (helper, urls) in helpers.items():
            if helper_name == function["name"]: continue
            for call in re.finditer(r"\b" + re.escape(helper_name) + r"\s*\(", function["body"]):
                close = balanced(function["body"], call.end() - 1)
                supplied = split_args(function["body"][call.end():close - 1]); bindings = {}
                for index, parameter in enumerate(helper["parameters"]):
                    name, _, default = parameter.partition("="); name = name.strip()
                    expression = supplied[index] if index < len(supplied) else default.strip()
                    bindings[name] = js_value(expression)[0] if expression else "${" + name + "}"
                for value, dynamic, verb in urls:
                    resolved = value
                    for name, replacement in bindings.items(): resolved = resolved.replace("${" + name + "}", replacement)
                    resolved_verb = bindings.get(verb[2:-1], verb).upper() if verb.startswith("${") else verb
                    records.append({"source": path, "function": function["name"], "line": function["line"],
                                    "exported": function["exported"], "pathExpression": resolved,
                                    "method": resolved_verb, "dynamic": "${" in resolved or resolved_verb not in {"GET", "POST", "PUT", "DELETE", "PATCH"},
                                    "requestParameters": function["parameters"], "kind": "same-file-wrapper-call",
                                    "via": helper_name, "migrationStatus": "pending"})
    return records


def response_behavior(sources: dict[str, str]) -> dict:
    prefix = "backend/src/main/java/im/tlgxy/cloud/core/"
    envelope_path = prefix + "util/AjaxJson.java"
    handler_path = prefix + "exception/GlobalExceptionHandler.java"
    envelope = without_comments(sources[envelope_path])
    constant = without_comments(sources["frontend/src/constant/index.js"])
    error_codes = {}
    declaration = re.search(r"const\s+responseCode\s*=\s*\{", constant)
    if declaration:
        end = balanced(constant, declaration.end() - 1, "{", "}")
        error_codes = dict(re.findall(r"(\w+)\s*:\s*'([^']*)'", constant[declaration.end():end - 1]))
    handlers, text = [], without_comments(sources[handler_path])
    method_pattern = re.compile(r"\bpublic\s+([\w.$<>?,\[\] \r\n\t]+?)\s+(\w+)\s*\(")
    declared = list(method_pattern.finditer(text))
    for annotation in annotations(text):
        if annotation["name"] != "ExceptionHandler": continue
        method = next((m for m in declared if m.start() > annotation["start"]), None)
        if not method: continue
        signature_end = balanced(text, method.end() - 1)
        body_start = text.find("{", signature_end)
        body = text[body_start:balanced(text, body_start, "{", "}")] if body_start >= 0 else ""
        metadata = text[annotation["end"]:method.start()]
        status = re.search(r"@ResponseStatus(?:\s*\((.*?)\))?", metadata, re.S)
        handlers.append({"handler": method[2], "line": text[:annotation["start"]].count("\n") + 1,
                         "exceptionTypes": re.findall(r"([\w.$]+)\.class", annotation["args"]),
                         "statusDeclaration": compact(status[0]) if status else "not-declared",
                         "responseFactories": sorted(set(re.findall(r"AjaxJson\.(\w+)\s*\(", body))),
                         "mayRedirectBrowser": "sendRedirect(" in body})
    success = re.search(r'CODE_SUCCESS\s*=\s*"([^"]+)"', envelope)
    return {"jsonEnvelope": {"fields": re.findall(r"\bprivate\s+(?:final\s+)?(?:String|Long|T)\s+(\w+)\s*;", envelope),
                              "successCode": success[1] if success else None, "successCodeType": "string"},
            "frontendResponseCodes": error_codes, "exceptionHandlerDeclarations": handlers,
            "evidence": [envelope_path, handler_path, "frontend/src/constant/index.js", "frontend/src/http/index.js", "frontend/src/http/request.js"]}


def allowed(path: str) -> bool:
    parts = Path(path).parts
    name = Path(path).name.lower()
    return not any(part in {"node_modules", "target", "dist", ".git", ".local-data", "data", "upload-staging", "__pycache__"} for part in parts) and not name.startswith(".env") and not name.endswith((".pem", ".key", ".p12", ".pfx", ".db", ".sqlite", ".log")) and "secret" not in name


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default=BASELINE, help="Existing source commit; not an upstream-version comparison")
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    destination = root / "backend-independent" / "contracts"
    baseline = git(root, "rev-parse", "--verify", options.baseline + "^{commit}").decode().strip()
    tracked = [path for path in git(root, "ls-files", "-z", "backend/src/main/java", "frontend").decode().split("\0") if path and allowed(path)]
    sources = {}
    frontend_hashes = []
    for path in sorted(tracked):
        absolute = root / path
        if absolute.is_symlink() or not absolute.is_file(): raise ValueError("Missing or symlink tracked source: " + path)
        data = absolute.read_bytes()
        if path.startswith("frontend/"):
            frontend_hashes.append({"path": path, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
        if (path.endswith(".java") or path.startswith("frontend/src/api/") and path.endswith((".js", ".ts", ".mjs"))
                or path in {"frontend/src/constant/index.js", "frontend/src/http/index.js", "frontend/src/http/request.js"}):
            sources[path] = data.decode("utf-8-sig")
    constants = {}
    for path, source in sources.items():
        if not path.endswith(".java"): continue
        for match in re.finditer(r'\b(?:public\s+)?static\s+final\s+String\s+(\w+)\s*=\s*"([^"\r\n]*)"', without_comments(source)):
            # Keep collisions unresolved instead of guessing an imported constant's owner.
            if match[1] not in constants: constants[match[1]] = match[2]
            elif constants[match[1]] != match[2]: constants[match[1]] = "${AMBIGUOUS:" + match[1] + "}"
    endpoints, requests = [], []
    for path, source in sources.items():
        if path.endswith(".java"): endpoints.extend(java_inventory(path, source, constants))
        elif path.startswith("frontend/src/api/"): requests.extend(frontend_inventory(path, source))
    endpoints.sort(key=lambda item: (item["path"], item["method"], item["source"], item["line"]))
    requests.sort(key=lambda item: (item["source"], item["line"], item["pathExpression"]))
    # Behavioral assertions are explicitly static observations, not execution results.
    behavior = {"observationMode": "static-source-only; no legacy imports or runtime execution",
                **response_behavior(sources),
                "ajaxHeaders": ["tongligong-student-cloud-token", "axios-request", "axios-from"],
                "requestBody": "GET/DELETE data becomes query parameters; POST/PUT data becomes request body via src/http/request.js",
                "rawResponses": "responseIntercept:false bypasses business-envelope handling (downloads/blob/raw content)",
                "unauthorized": "Ajax requests receive HTTP401 with business code42000; direct browser navigation may redirect to login. Confirm actual handler and middleware before implementing.",
                "errors": "Business failures are not uniformly HTTP200: BizException maps to HTTP400; parameter validation maps to41000. Per-handler/annotation mapping and browser redirects require follow-up contract tests.",
                "streaming": "AI uses authenticated POST SSE and a separate POST cancellation; JSON and multipart variants. Event schemas, Range behavior, OAuth callbacks and WebSocket handshake are not fully inferred by static annotations."}
    inventory = {"schemaVersion": 1, "baselineCommit": baseline, "workingTreeSources": True,
                 "purpose": "Behavioral comparison only, not a compile/runtime dependency or complete OpenAPI document",
                 "statistics": {"javaRoutes": len(endpoints), "distinctMethodPaths": len({(e['method'], e['path']) for e in endpoints}),
                                "frontendRequestRecords": len(requests), "frontendApiFiles": len({e['source'] for e in requests}),
                                "unresolvedJavaPathRecords": sum(e['unresolvedPathExpression'] for e in endpoints)},
                 "behavior": behavior, "backendRoutes": endpoints, "frontendRequests": requests,
                 "limitations": ["Regex and balanced-delimiter static inventory, not Java/JavaScript semantic analysis or Spring HandlerMapping.",
                                 "ANY means a RequestMapping without an explicit method; implicit HEAD/OPTIONS are not expanded.",
                                 "Dynamic frontend wrapper calls outside frontend/src/api, runtime URLs, meta-annotations and inherited routes require manual reconciliation.",
                                 "No DTO schemas, auth proof, complete response samples, external callbacks or browser/streaming execution is claimed.",
                                 "All migration statuses remain pending; inventory does not claim independent implementation or passing compatibility tests.",
                                 "Tracked .env files, secret/example-secret filenames, dependencies, build products and runtime data are deliberately excluded."]}
    baseline_frontend = {"schemaVersion": 1, "baselineCommit": baseline, "algorithm": "SHA-256", "hashMode": "current-working-tree-raw-bytes",
                         "scope": "All tracked frontend files except excluded configuration/secrets/runtime paths; includes code, tests, public assets and build configuration",
                         "excludedTrackedPaths": [p for p in git(root, "ls-files", "-z", "frontend").decode().split("\0") if p and not allowed(p)],
                         "fileCount": len(frontend_hashes), "files": frontend_hashes}
    destination.mkdir(parents=True, exist_ok=True)
    for name, value in [("legacy-http-inventory.json", inventory), ("frontend-baseline.json", baseline_frontend)]:
        (destination / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    counts = Counter(e["source"].split("/module/", 1)[1].split("/", 1)[0] if "/module/" in e["source"] else "core" for e in endpoints)
    lines = ["# 独立后端 API 迁移清单", "", "此文件由 `../tools/extract_legacy_contracts.py` 生成。仅记录行为对照，不导入、编译或执行旧代码。", "",
             f"基线提交：`{baseline}`。当前工作区源码静态扫描得到 {len(endpoints)} 条展开路由记录、{len(requests)} 条前端请求记录。", "",
             "所有功能初始均为 **pending**，不表示已经实现、已通过测试或形成完整 OpenAPI。动态参数、包装器和注解推断须在实际实现时逐项确认。", "",
             "| 旧接口业务模块 | 展开路由记录 | 独立实现状态 |", "| --- | ---: | --- |"]
    lines += [f"| {module} | {count} | pending |" for module, count in sorted(counts.items())]
    lines += ["", "## 接口逐项迁移", "", "| 方法 | 路径 | 旧处理函数（行为证据） | 状态 |", "| --- | --- | --- | --- |"]
    lines += [f"| {e['method']} | `{e['path'].replace('|', '&#124;')}` | `{e['controller']}.{e['handler']}` | pending |" for e in endpoints]
    lines += ["", "## 包装与边界", "", "- JSON 成功码是字符串 `0`，常规字段为 `code/msg/data/dataCount/traceId`；原始下载和 SSE 不套统一响应。",
              "- `axios-request` 影响错误响应/浏览器重定向，认证头和 cookie 名称须兼容；错误 HTTP 状态与业务码分别核对。",
              "- 通用请求包装器、动态 URL、SSE 事件、WebSocket 票据及完整 DTO 结构不能仅凭此静态清单保证兼容。",
              "- `frontend-baseline.json` 是跟踪前端文件的原始字节哈希；`.env`、私密配置及运行产物不读取。",
              "- 独立后端拥有新模型与迁移起点，此清单不要求沿用旧数据库表或旧实现。",
              "- 生成器可重复运行，但会重置本生成清单中的状态；正式实施进度应另行记录，不能将 pending 自动改成通过。", ""]
    (destination / "MIGRATION-MATRIX.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({**inventory["statistics"], "frontendBaselineFiles": len(frontend_hashes), "outputs": ["legacy-http-inventory.json", "frontend-baseline.json", "MIGRATION-MATRIX.md"]}))


if __name__ == "__main__":
    main()
