#!/usr/bin/env python3
"""Reuse existing local configuration through an encrypted SSH stdin pipe.

No new credentials, command-line credential values, or local credential copy.
"""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import subprocess
import sys


def existing_file(value):
    path = Path(value).expanduser().resolve()
    if not path.is_file():
        raise argparse.ArgumentTypeError('must identify an existing file')
    return str(path)


def server_destination(value):
    # A single user@host argument only; no options, ports, shell syntax, or paths.
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]{0,63}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', value):
        raise argparse.ArgumentTypeError('use an SSH destination such as root@example.com')
    return value


def remote_helper_path(value):
    path = PurePosixPath(value)
    if (not path.is_absolute() or not re.fullmatch(r'/[A-Za-z0-9_./-]+\.py', value)
            or any(part in {'.', '..'} for part in value.split('/'))):
        raise argparse.ArgumentTypeError('use an absolute Python helper path without shell syntax or traversal')
    return value


def parse_arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True, type=server_destination, help='SSH user@host')
    parser.add_argument('--identity-file', required=True, type=existing_file, help='existing SSH private-key path')
    parser.add_argument('--remote-helper', type=remote_helper_path,
                        default='/opt/student-cloud-v5/configure-services.py')
    parser.add_argument('--ssh-executable', type=existing_file, default=shutil.which('ssh'),
                        help='SSH executable path (default: ssh from PATH)')
    args = parser.parse_args(argv)
    if not args.ssh_executable:
        parser.error('ssh was not found; provide --ssh-executable')
    return args


def main():
    args = parse_arguments()
    repo = Path(__file__).resolve().parents[1]
    private = repo / 'backend' / 'application-local-secrets.properties'
    properties = {}
    if private.is_file():
        for line in private.read_text(encoding='utf-8-sig').splitlines():
            line = line.strip()
            if not line or line.startswith(('#', '!')) or '=' not in line:
                continue
            name, value = line.split('=', 1)
            properties[name.strip()] = value.strip()
    prefix = 'tongligong-student-cloud.registration.mail.'
    username = os.environ.get('MAIL_USERNAME') or properties.get(prefix + 'username', '')
    password = os.environ.get('MAIL_PASSWORD') or properties.get(prefix + 'password', '')
    sender = os.environ.get('MAIL_FROM') or properties.get(prefix + 'from') or username
    mail = {'username': username, 'password': password, 'from': sender} if username and password else None
    key = os.environ.get('DEEPSEEK_API_KEY') or None
    if mail is None and key is None:
        print('V5_EXISTING_CONFIGURATION_MISSING')
        return 3
    payload = json.dumps({'mail': mail, 'aiKey': key}).encode('utf-8')
    ssh = [args.ssh_executable, '-T', '-i', args.identity_file,
           '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'IdentitiesOnly=yes',
           '-o', 'PreferredAuthentications=publickey', '-o', 'NumberOfPasswordPrompts=0',
           '-o', 'PasswordAuthentication=no', '-o', 'KbdInteractiveAuthentication=no',
           '-o', 'ConnectTimeout=12', args.server,
           'python3 -I ' + shlex.quote(args.remote_helper)]
    completed = subprocess.run(ssh, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    payload = properties = password = key = mail = None
    text = completed.stdout.decode('utf-8', errors='replace').strip()
    # Accept only known non-sensitive statuses; SSH diagnostics remain private in memory.
    if completed.returncode == 0:
        result = json.loads(text)
        if set(result) != {'status', 'mailAuthenticated', 'aiConfigured', 'testEmailSent'}:
            raise ValueError('unexpected response')
        if (result['status'] != 'V5_SERVICES_CONFIGURED'
                or any(type(result[field]) is not bool for field in
                       ('mailAuthenticated', 'aiConfigured', 'testEmailSent'))):
            raise ValueError('unexpected status values')
        print(json.dumps(result))
    elif text in {'V5_MAIL_AUTH_FAILED_NO_CONFIGURATION_CHANGED', 'V5_NO_EXISTING_SERVICE_CONFIGURATION',
                  'V5_SERVICE_CONFIGURATION_FAILED_NO_SECRET_OUTPUT'}:
        print(text)
    else:
        print('V5_SSH_SERVICE_CONFIGURATION_FAILED')
    return completed.returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('V5_PRIVATE_CONFIGURATION_ERROR_NO_SECRET_OUTPUT')
        sys.exit(1)
