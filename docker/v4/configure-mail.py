#!/usr/bin/env python3
"""Configure only V4 QQ SMTP. Credentials arrive exclusively as JSON on stdin.

This helper is intentionally silent about addresses, credentials, env contents,
SMTP replies, Docker output, and HTTP bodies. Run through configure-v4-mail.ps1.
"""

import fcntl
import json
import os
from pathlib import Path
import re
import smtplib
import ssl
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request


TARGET = Path("/opt/student-cloud-v4")
ENV_PATH = TARGET / ".env"
COMPOSE_PATH = TARGET / "compose.yaml"
DOCKER_ENV = {
    "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    "HOME": "/root",
    "LANG": "C.UTF-8",
    "DOCKER_HOST": "unix:///var/run/docker.sock",
}
COMPOSE = [
    "docker", "compose", "--project-name", "student-cloud-v4",
    "--project-directory", str(TARGET), "--env-file", str(ENV_PATH),
    "--file", str(COMPOSE_PATH),
]
EMAIL = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}@qq\.com", re.ASCII)
AUTH_CODE = re.compile(r"[A-Za-z]{16}", re.ASCII)
MAX_INPUT = 4096
MAX_ENV = 1024 * 1024


class SetupFailure(Exception):
    def __init__(self, stage, business_code=None):
        self.stage = stage
        self.business_code = business_code


def emit(token):
    print(token, flush=True)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise SetupFailure("input")
        result[key] = value
    return result


def read_credentials():
    raw = sys.stdin.buffer.read(MAX_INPUT + 1)
    if len(raw) > MAX_INPUT:
        raise SetupFailure("input")
    try:
        payload = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        raise SetupFailure("input") from None
    finally:
        raw = None
    if not isinstance(payload, dict) or set(payload) != {"email", "authorization_code", "send_test"}:
        raise SetupFailure("input")
    email = payload.get("email")
    code = payload.get("authorization_code")
    send_test = payload.get("send_test")
    if not isinstance(email, str) or not EMAIL.fullmatch(email):
        raise SetupFailure("input")
    if not isinstance(code, str) or not AUTH_CODE.fullmatch(code) or type(send_test) is not bool:
        raise SetupFailure("input")
    payload.clear()
    return email.lower(), code, send_test


def check_directory():
    if os.geteuid() != 0 or TARGET.resolve(strict=True) != TARGET:
        raise SetupFailure("preflight")
    info = TARGET.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise SetupFailure("preflight")
    compose_info = COMPOSE_PATH.lstat()
    if not stat.S_ISREG(compose_info.st_mode) or compose_info.st_uid != 0 or compose_info.st_mode & 0o022:
        raise SetupFailure("preflight")


def file_signature(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_mode, info.st_uid, info.st_nlink)


def read_environment():
    descriptor = os.open(ENV_PATH, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600:
            raise SetupFailure("preflight")
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            raw = stream.read(MAX_ENV + 1)
        if len(raw) > MAX_ENV or b"\x00" in raw:
            raise SetupFailure("preflight")
    finally:
        os.close(descriptor)

    assignments = {}
    for line in raw.splitlines(keepends=True):
        body = line.rstrip(b"\r\n")
        if not body or body.startswith(b"#"):
            continue
        match = re.fullmatch(rb"([A-Z][A-Z0-9_]*)=([^\r\n]*)", body)
        if not match or match[1] in assignments:
            raise SetupFailure("preflight")
        assignments[match[1]] = match[2]
    required = {b"MAIL_ENABLED", b"MAIL_USERNAME", b"MAIL_PASSWORD", b"MAIL_FROM",
                b"MYSQL_ROOT_PASSWORD", b"MYSQL_PASSWORD", b"MAIL_CODE_SECRET"}
    expected = {b"MAIL_HOST": b"smtp.qq.com", b"MAIL_PORT": b"465", b"MAIL_AUTH": b"true",
                b"MAIL_SSL": b"true", b"MAIL_STARTTLS": b"false"}
    if not required.issubset(assignments) or any(assignments.get(key) != value for key, value in expected.items()):
        raise SetupFailure("preflight")
    if not assignments[b"MYSQL_ROOT_PASSWORD"] or not assignments[b"MYSQL_PASSWORD"] or len(assignments[b"MAIL_CODE_SECRET"]) < 32:
        raise SetupFailure("preflight")
    assignments.clear()
    return raw, file_signature(info)


def command(arguments, stage, timeout=30, capture=False):
    try:
        completed = subprocess.run(
            arguments, cwd=TARGET, env=DOCKER_ENV, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=timeout, check=False,
        )
    except Exception:
        raise SetupFailure(stage) from None
    if completed.returncode != 0:
        raise SetupFailure(stage)
    return completed.stdout if capture else None


def authenticate(email, authorization_code):
    try:
        # create_default_context verifies the public CA chain and smtp.qq.com.
        with smtplib.SMTP_SSL("smtp.qq.com", 465, timeout=15, context=ssl.create_default_context()) as smtp:
            smtp.set_debuglevel(0)
            smtp.ehlo()
            smtp.login(email, authorization_code)
    except Exception:
        raise SetupFailure("smtp-auth") from None
    emit("V4_MAIL_SMTP_OK")


def replace_environment(original, signature, email, authorization_code):
    updates = {
        b"MAIL_ENABLED": b"true", b"MAIL_USERNAME": email.encode("ascii"),
        b"MAIL_PASSWORD": authorization_code.encode("ascii"), b"MAIL_FROM": email.encode("ascii"),
    }
    lines = []
    for line in original.splitlines(keepends=True):
        key = line.partition(b"=")[0]
        if key in updates:
            newline = b"\r\n" if line.endswith(b"\r\n") else b"\n" if line.endswith(b"\n") else b""
            lines.append(key + b"=" + updates[key] + newline)
        else:
            # Preserve every other byte, including all existing database secrets.
            lines.append(line)
    updated = b"".join(lines)
    temporary = None
    try:
        if file_signature(ENV_PATH.lstat()) != signature:
            raise SetupFailure("env-write")
        descriptor, temporary = tempfile.mkstemp(prefix=".mail-env-", dir=TARGET)
        try:
            os.fchmod(descriptor, 0o600)
            os.fchown(descriptor, 0, 0)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(updated)
                stream.flush()
                os.fsync(stream.fileno())
        except BaseException:
            # fdopen owns the descriptor once constructed.
            try:
                os.close(descriptor)
            except OSError:
                pass
            raise
        if file_signature(ENV_PATH.lstat()) != signature:
            raise SetupFailure("env-write")
        os.replace(temporary, ENV_PATH)
        temporary = None
        directory = os.open(TARGET, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if temporary is not None:
            os.unlink(temporary)
        updates.clear()
        lines.clear()
        updated = None
    emit("V4_MAIL_ENV_OK")


def recreate_backend():
    command(COMPOSE + ["config", "--quiet"], "compose-check")
    command(COMPOSE + ["up", "--detach", "--no-deps", "--force-recreate", "--pull", "never",
                       "--wait", "--wait-timeout", "180", "backend"], "backend", timeout=240)
    container = command(COMPOSE + ["ps", "--quiet", "backend"], "backend", capture=True).strip()
    if not re.fullmatch(rb"[a-f0-9]{12,64}", container):
        raise SetupFailure("backend")
    health = command(["docker", "inspect", "--format", "{{.State.Health.Status}}", container.decode("ascii")],
                     "backend", capture=True).strip()
    if health != b"healthy":
        raise SetupFailure("backend")
    emit("V4_MAIL_BACKEND_OK")
    command(COMPOSE + ["exec", "-T", "web", "nginx", "-t"], "web-reload")
    command(COMPOSE + ["exec", "-T", "web", "nginx", "-s", "reload"], "web-reload")
    emit("V4_MAIL_WEB_OK")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def send_registration_test(email):
    # The current DTO accepts only email; the service fixes purpose to REGISTER.
    # No account is created, changed, or removed. Do not retry a failed POST.
    payload = json.dumps({"email": email}).encode("utf-8")
    request = urllib.request.Request(
        "http://127.0.0.1:18081/user/registration/email-code", data=payload,
        headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST",
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=30) as response:
            if response.status != 200:
                raise SetupFailure("registration-test")
            body = response.read(65537)
        if len(body) > 65536:
            raise SetupFailure("registration-test")
        result = json.loads(body)
        value = result.get("code") if isinstance(result, dict) else None
        business_code = str(value) if type(value) in (str, int) and re.fullmatch(r"[0-9]{1,8}", str(value)) else "UNKNOWN"
        if business_code != "0":
            raise SetupFailure("registration-test", business_code)
    except SetupFailure:
        raise
    except Exception:
        raise SetupFailure("registration-test") from None
    finally:
        payload = body = result = None
    emit("V4_MAIL_TEST_OK business_code=0")


def run():
    os.umask(0o077)
    check_directory()
    lock = os.open(TARGET / ".configure-mail.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        lock_info = os.fstat(lock)
        if not stat.S_ISREG(lock_info.st_mode) or lock_info.st_uid != 0 or lock_info.st_nlink != 1 or stat.S_IMODE(lock_info.st_mode) != 0o600:
            raise SetupFailure("preflight")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise SetupFailure("preflight") from None
        email = authorization_code = original = None
        try:
            email, authorization_code, send_test = read_credentials()
            original, signature = read_environment()
            command(COMPOSE + ["config", "--quiet"], "compose-check")
            authenticate(email, authorization_code)
            replace_environment(original, signature, email, authorization_code)
            authorization_code = original = None
            recreate_backend()
            if send_test:
                send_registration_test(email)
            else:
                emit("V4_MAIL_TEST_SKIPPED")
            emit("V4_MAIL_DONE")
        finally:
            email = authorization_code = original = None
    finally:
        os.close(lock)


if __name__ == "__main__":
    try:
        if len(sys.argv) != 1:
            raise SetupFailure("input")
        run()
    except SetupFailure as failure:
        token = "V4_MAIL_FAILED stage=" + failure.stage
        if failure.business_code is not None:
            token += " business_code=" + failure.business_code
        emit(token)
        sys.exit(1)
    except BaseException:
        # Never print an exception, traceback, SMTP reply, or subprocess output.
        emit("V4_MAIL_FAILED stage=internal")
        sys.exit(1)
