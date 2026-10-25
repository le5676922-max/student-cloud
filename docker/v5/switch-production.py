#!/usr/bin/env python3
"""Switch V5 onto the existing domain after explicit human confirmation.

Never changes databases or deletes deployment data. Failure restores the old web.
"""
import json
import os
from pathlib import Path
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path('/opt/student-cloud-v5')
COMPOSE = ['docker', 'compose', '--project-name', 'student-cloud-v5', '--env-file', str(ROOT / '.env'),
           '--file', str(ROOT / 'compose.yaml')]


def command(arguments, timeout=180):
    result = subprocess.run(arguments, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError('command failed')
    return result.stdout


def save_environment(text):
    descriptor, name = tempfile.mkstemp(prefix='.cutover-', dir=ROOT)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, ROOT / '.env')
    finally:
        if os.path.exists(name):
            os.unlink(name)


def health(name):
    value = json.loads(command(['docker', 'inspect', name], 15))[0]
    return value['State'].get('Health', {}).get('Status') == 'healthy' and not value['State']['OOMKilled']


def main():
    if sys.argv[1:] != ['--confirmed-domain-cutover']:
        print('Explicit confirmation of jialechatweb.xin and ports 80/443 is required.')
        return 2
    assert os.geteuid() == 0 and ROOT.resolve(strict=True) == ROOT
    env_path = ROOT / '.env'
    info = env_path.lstat()
    assert stat.S_ISREG(info.st_mode) and info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600
    original_env = env_path.read_text()
    original_config = (ROOT / 'nginx/default.conf').read_text()
    values = dict(line.split('=', 1) for line in original_env.splitlines() if '=' in line and not line.startswith('#'))
    assert values['WEB_HTTP_BIND'] == '127.0.0.1:18082' and values['PUBLIC_ORIGIN'] == 'http://127.0.0.1:18082'
    for service in ('mysql', 'redis-cache', 'backend', 'web'):
        assert health('student-cloud-v5-' + service + '-1')
    with urllib.request.urlopen('http://127.0.0.1:18082/api/install/status', timeout=10) as response:
        assert json.load(response)['data'] is True
    updates = {'PUBLIC_ORIGIN': 'https://jialechatweb.xin', 'WEB_HTTP_BIND': '80', 'WEB_HTTPS_BIND': '443'}
    new_env = '\n'.join(line.split('=', 1)[0] + '=' + updates[line.split('=', 1)[0]]
                        if line.split('=', 1)[0] in updates else line for line in original_env.splitlines()) + '\n'
    stage = 'prepare'
    old_web_stopped = False
    try:
        save_environment(new_env)
        command(COMPOSE + ['config', '--quiet'], 30)
        stage = 'backend origin'
        print('V5_UPDATE_PRODUCTION_ORIGIN', flush=True)
        command(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'backend'], 210)
        assert health('student-cloud-v5-backend-1')
        (ROOT / 'nginx/default.conf').write_text((ROOT / 'nginx/https.conf').read_text())
        command(['docker', 'exec', 'student-cloud-v5-web-1', 'nginx', '-t'], 20)
        stage = 'public web'
        print('V5_SWITCH_PUBLIC_WEB', flush=True)
        command(['docker', 'stop', '--timeout', '30', 'student-cloud-prod-web-1'], 45)
        old_web_stopped = True
        command(COMPOSE + ['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'web'], 120)
        assert health('student-cloud-v5-web-1')
        command(['curl', '--fail', '--silent', '--show-error', '--max-time', '15', '--resolve',
                 'jialechatweb.xin:443:127.0.0.1', 'https://jialechatweb.xin/api/install/status'], 20)
        print('V5_PRODUCTION_CUTOVER_COMPLETED', flush=True)
        return 0
    except Exception:
        print('V5_CUTOVER_FAILED_STAGE_' + stage.upper().replace(' ', '_'), flush=True)
        # Restore the public website first. Do not wait for the staging Java restart.
        if old_web_stopped:
            subprocess.run(['docker', 'stop', '--timeout', '15', 'student-cloud-v5-web-1'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            rollback = subprocess.run(['docker', 'start', 'student-cloud-prod-web-1'],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            print('OLD_PUBLIC_WEB_RESTORED' if rollback.returncode == 0 else 'OLD_PUBLIC_WEB_REQUIRES_ATTENTION', flush=True)
        save_environment(original_env)
        (ROOT / 'nginx/default.conf').write_text(original_config)
        print('V5_STAGING_CONFIGURATION_RESTORED_NO_DATA_DELETED', flush=True)
        return 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('V5_CUTOVER_PREFLIGHT_FAILED_NO_SECRET_OUTPUT', flush=True)
        sys.exit(1)
