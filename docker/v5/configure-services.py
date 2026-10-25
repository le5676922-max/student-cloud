#!/usr/bin/env python3
"""Accept private service credentials on stdin; never print them or SMTP replies.

Only the fresh V5 environment is updated. Does not restart containers or send mail.
"""
import json
import os
from pathlib import Path
import re
import smtplib
import ssl
import stat
import sys
import tempfile

TARGET = Path('/opt/student-cloud-v5')


def signature(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_mode, info.st_uid, info.st_nlink)


def main():
    if os.geteuid() != 0 or TARGET.resolve(strict=True) != TARGET:
        raise ValueError('preflight')
    path = TARGET / '.env'
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
        raise ValueError('preflight')
    raw = sys.stdin.buffer.read(16385)
    if len(raw) > 16384:
        raise ValueError('input')
    payload = json.loads(raw.decode('utf-8'))
    if not isinstance(payload, dict) or set(payload) != {'mail', 'aiKey'}:
        raise ValueError('input')
    changes = {}
    mail = payload['mail']
    if mail is not None:
        if not isinstance(mail, dict) or set(mail) != {'username', 'password', 'from'}:
            raise ValueError('mail input')
        username, password, sender = mail['username'], mail['password'], mail['from']
        if not isinstance(username, str) or not re.fullmatch(r'[A-Za-z0-9._+-]+@qq\.com', username):
            raise ValueError('mail input')
        if not isinstance(password, str) or not re.fullmatch(r'[A-Za-z]{16}', password) or sender != username:
            raise ValueError('mail input')
        try:
            with smtplib.SMTP_SSL('smtp.qq.com', 465, timeout=12, context=ssl.create_default_context()) as client:
                client.login(username, password)
        except Exception:
            print('V5_MAIL_AUTH_FAILED_NO_CONFIGURATION_CHANGED', flush=True)
            return 2
        changes.update(MAIL_ENABLED='true', MAIL_HOST='smtp.qq.com', MAIL_PORT='465', MAIL_USERNAME=username,
                       MAIL_PASSWORD=password, MAIL_FROM=sender, MAIL_AUTH='true', MAIL_SSL='true', MAIL_STARTTLS='false')
    key = payload['aiKey']
    if key is not None:
        if not isinstance(key, str) or not re.fullmatch(r'sk-[A-Za-z0-9_-]{8,256}', key):
            raise ValueError('ai input')
        changes.update(AI_ENABLED='true', DEEPSEEK_API_KEY=key)
    if not changes:
        print('V5_NO_EXISTING_SERVICE_CONFIGURATION', flush=True)
        return 3
    original = path.read_text(encoding='utf-8')
    lines, found = [], set()
    for line in original.splitlines():
        name = line.split('=', 1)[0]
        if name in changes:
            if name in found:
                raise ValueError('duplicate assignment')
            found.add(name)
            line = name + '=' + changes[name]
        lines.append(line)
    for name in changes.keys() - found:
        lines.append(name + '=' + changes[name])
    # All allowed values are validated to exclude newlines, quotes and interpolation.
    descriptor, temporary = tempfile.mkstemp(prefix='.services-', dir=TARGET)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as output:
            output.write('\n'.join(lines) + '\n')
            output.flush()
            os.fsync(output.fileno())
        if signature(path.lstat()) != signature(info):
            raise ValueError('environment changed')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(json.dumps({'status': 'V5_SERVICES_CONFIGURED', 'mailAuthenticated': mail is not None,
                      'aiConfigured': key is not None, 'testEmailSent': False}))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        # Neither exception strings nor tracebacks may disclose credential material.
        print('V5_SERVICE_CONFIGURATION_FAILED_NO_SECRET_OUTPUT', flush=True)
        sys.exit(1)
