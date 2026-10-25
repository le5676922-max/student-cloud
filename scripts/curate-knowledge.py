#!/usr/bin/env python3
"""Review-first feedback curation. Standard library only; never prints passwords, tokens or transcripts."""
from __future__ import annotations

import argparse
import getpass
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request


class CurationError(Exception):
    pass


def base_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise CurationError("地址只填写站点来源，不要附带账号、密码、路径、查询参数。")
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise CurationError("站点地址必须使用 http 或 https。")
    loopback = parsed.hostname.lower() == "localhost"
    try:
        loopback = loopback or ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        pass
    if parsed.scheme == "http" and not loopback:
        raise CurationError("远程站点请使用 HTTPS；HTTP 仅允许本机回环地址。")
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CurationError("请求被重定向，已停止；请核对站点地址，避免凭据被转发。")


class Client:
    def __init__(self, origin: str, username: str):
        self.origin = base_url(origin)
        self.token = ""
        self.opener = urllib.request.build_opener(NoRedirect)
        password = getpass.getpass("全局管理员密码（不显示、不保存）：")
        try:
            result = self.request("POST", "/user/login", {"username": username, "password": password})
        finally:
            password = ""
        if not isinstance(result, dict) or not isinstance(result.get("token"), str) or not result["token"]:
            raise CurationError("登录未成功；请在网页完成验证码或二次验证后再使用此脚本。")
        self.token = result["token"]
        # The server enforces ADMIN again on every curation API, even after this check.
        self.request("GET", "/admin/ai-feedback?page=1&pageSize=1&status=RESOLVED")

    def request(self, method: str, path: str, data=None):
        if not path.startswith("/") or path.startswith("//"):
            raise CurationError("内部接口路径不正确。")
        headers = {"Content-Type": "application/json;charset=UTF-8", "axios-request": "true"}
        if self.token:
            headers["tongligong-student-cloud-token"] = self.token
        payload = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.origin + path, data=payload, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=55) as response:
                body = response.read(2_000_001)
            if len(body) > 2_000_000:
                raise CurationError("接口响应过长，已停止。")
            result = json.loads(body)
            if not isinstance(result, dict) or str(result.get("code")) != "0":
                raise CurationError("接口未确认操作成功，请在管理页面核对权限、版本与操作结果。")
            return result.get("data")
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            # A failed write may already have committed. No automatic retry.
            raise CurationError("连接或响应异常，操作结果未确认；请先在网页核对，不要反复提交。") from None


def private_json(path: Path, payload: dict, overwrite=False):
    path = path.absolute()
    if path.is_symlink() or path.exists() and not overwrite:
        raise CurationError("输出文件已存在或是符号链接，请换一个文件名。")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        descriptor, temporary = tempfile.mkstemp(prefix=".knowledge-review-", dir=path.parent)
        os.chmod(temporary, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(payload, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        if path.exists() and not overwrite:
            raise CurationError("输出文件已存在，请换一个文件名。")
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            os.unlink(temporary)


def export_review(client: Client, output: Path, limit: int, ai: bool):
    if output.exists() or output.is_symlink():
        raise CurationError("输出文件已存在或是符号链接，请换一个文件名。")
    if not 1 <= limit <= 50 or ai and limit > 5:
        raise CurationError("每批 1 至 50 条；AI 辅助整理每批最多 5 条，避免接口限流。")
    result = client.request("GET", "/admin/ai-feedback?page=1&pageSize=50&status=RESOLVED")
    if not isinstance(result, dict) or not isinstance(result.get("items"), list):
        raise CurationError("反馈列表格式不正确。")
    entries = []
    for item in result["items"][:limit]:
        identifier = item.get("id")
        if not isinstance(identifier, str) or not re.fullmatch("[a-f0-9]{32}", identifier):
            raise CurationError("反馈标识格式不正确。")
        source = client.request("GET", f"/admin/ai-feedback/{identifier}")
        state = client.request("GET", f"/admin/ai-knowledge/feedback/{identifier}")
        if source.get("status") != "RESOLVED" or not source.get("correctedAnswer", "").strip():
            raise CurationError("反馈状态已变化，请重新读取。")
        entry = {
            "feedbackId": identifier,
            "expectedUpdatedAt": source["updatedAt"],
            "expectedVersion": state["version"],
            "title": source["question"][:160],
            "question": source["question"],
            "answer": source["correctedAnswer"],
            "scope": "COURSE" if source.get("courseId") else "TENANT",
            "confirmGlobal": False,
            "reviewed": False,
        }
        if ai:
            preview = client.request("POST", "/admin/ai-knowledge/refine", {
                "feedbackId": identifier,
                "expectedUpdatedAt": source["updatedAt"],
                "administratorAnswer": source["correctedAnswer"],
            })
            if preview.get("requiresReview") is not True or not isinstance(preview.get("answer"), str):
                raise CurationError("AI 未返回待复核草稿，已停止。")
            entry["candidateAnswer"] = preview["answer"]
        entries.append(entry)
    private_json(output, {"schema": 1, "entries": entries})
    print(f"已生成 {len(entries)} 条待人工复核的问答；未保存修订、未发布知识。")


def validate_review(document):
    if not isinstance(document, dict) or document.get("schema") != 1:
        raise CurationError("复核文件格式不正确。")
    entries = document.get("entries")
    if not isinstance(entries, list) or not 1 <= len(entries) <= 50:
        raise CurationError("每批必须包含 1 至 50 条复核问答。")
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or entry.get("reviewed") is not True:
            raise CurationError("每条问答须人工检查，并将 reviewed 改为 true。")
        identifier = entry.get("feedbackId", "")
        if not isinstance(identifier, str) or not re.fullmatch("[a-f0-9]{32}", identifier) or identifier in seen:
            raise CurationError("反馈标识错误或重复。")
        seen.add(identifier)
        if type(entry.get("expectedUpdatedAt")) is not int or type(entry.get("expectedVersion")) is not int or entry["expectedVersion"] < 0:
            raise CurationError("来源或知识版本不正确。")
        for key, maximum in (("title", 160), ("answer", 12000)):
            value = entry.get(key)
            if not isinstance(value, str) or not value.strip() or len(value) > maximum:
                raise CurationError("标题或人工核对答案的长度不正确。")
        if entry.get("scope") not in ("TENANT", "COURSE", "GLOBAL"):
            raise CurationError("发布范围不正确。")
        if entry["scope"] == "GLOBAL" and entry.get("confirmGlobal") is not True:
            raise CurationError("全站通用知识必须额外设置 confirmGlobal 为 true。")
    return entries


def publish_review(client: Client, source_file: Path):
    if source_file.is_symlink() or source_file.stat().st_size > 3_000_000:
        raise CurationError("复核文件不正确或过大。")
    document = json.loads(source_file.read_text(encoding="utf-8"))
    entries = validate_review(document)
    sources = []
    for entry in entries:
        source = client.request("GET", f"/admin/ai-feedback/{entry['feedbackId']}")
        state = client.request("GET", f"/admin/ai-knowledge/feedback/{entry['feedbackId']}")
        if source.get("updatedAt") != entry["expectedUpdatedAt"] or state.get("version") != entry["expectedVersion"]:
            raise CurationError("服务器问答或知识版本已变化，请重新导出复核，未发布该批次。")
        if source.get("status") != "RESOLVED":
            raise CurationError("反馈不再是已整理状态，请重新复核。")
        if source.get("courseId") and entry["scope"] != "COURSE":
            raise CurationError("课程反馈只能发布回原课程。")
        sources.append(source)
    print(f"即将保存并发布 {len(entries)} 条人工确认的问答；默认所属空间，课程反馈仅原课程。")
    if input("确认已检查问答正文和发布范围；输入 PUBLISH 执行：").strip() != "PUBLISH":
        print("已取消，没有保存或发布。")
        return
    for entry, source in zip(entries, sources):
        if entry["answer"].strip() != source.get("correctedAnswer", "").strip():
            reviewed = client.request("PUT", f"/admin/ai-feedback/{entry['feedbackId']}", {
                "status": "RESOLVED", "correctedAnswer": entry["answer"], "note": source.get("note", ""),
            })
            entry["expectedUpdatedAt"] = reviewed["updatedAt"]
            entry["answer"] = reviewed["correctedAnswer"]
            # Checkpoint each acknowledged correction. A later error never publishes a partial batch.
            private_json(source_file, document, overwrite=True)
    fields = ("feedbackId", "expectedUpdatedAt", "expectedVersion", "title", "answer", "scope", "confirmGlobal")
    result = client.request("POST", "/admin/ai-knowledge/publish", {"entries": [{key: entry[key] for key in fields} for entry in entries]})
    if not isinstance(result, list) or len(result) != len(entries) or any(item.get("state") != "PUBLISHED" for item in result):
        raise CurationError("发布结果未确认，请在管理页面核对；不要重复提交。")
    versions = {item["feedbackId"]: item["version"] for item in result}
    for entry in entries:
        entry["expectedVersion"] = versions[entry["feedbackId"]]
        entry["reviewed"] = False
    private_json(source_file, document, overwrite=True)
    print(f"已发布 {len(result)} 条知识。后续检索会按空间/课程权限使用，管理页面可查看版本或撤回。")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("export", "publish"))
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="站点来源；远程必须 HTTPS")
    parser.add_argument("--username", required=True, help="全局管理员邮箱或账号ID；密码在隐藏提示中输入")
    parser.add_argument("--file", type=Path, required=True, help="包含问答的私有复核 JSON，不要提交到 Git")
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--ai", action="store_true", help="仅 export：显式调用 AI 生成草稿，仍须人工核对")
    args = parser.parse_args(argv)
    try:
        client = Client(args.base_url, args.username)
        if args.action == "export":
            export_review(client, args.file, args.limit, args.ai)
        else:
            if args.ai:
                raise CurationError("publish 不支持 --ai；请先导出、复核，再发布。")
            publish_review(client, args.file)
        return 0
    except (CurationError, OSError, ValueError) as error:
        # File-system/JSON errors can include file snippets; only domain errors are displayed.
        print(str(error) if isinstance(error, CurationError) else "文件读取或写入失败，请核对路径、权限和 JSON 格式。", file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print("操作已中断；若已提交写入，请在管理页面核对结果。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
