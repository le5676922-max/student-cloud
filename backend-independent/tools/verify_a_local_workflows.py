"""Isolated LOCAL/H2 acceptance through four real services; never uses an existing database.

Build first with mvn -f backend-independent/pom.xml verify.
Example: python tools/verify_a_local_workflows.py --sizes-mib 49,50,51,128,1024
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--sizes-mib', default='51')
    parser.add_argument('--output-directory', default='.codex-run/a-local-workflows')
    args = parser.parse_args()
    sizes = [int(value) * 1024 * 1024 for value in args.sizes_mib.split(',')]
    if not sizes or any(value < 1 or value > 1024**3 for value in sizes):
        parser.error('sizes must be between 1 MiB and 1024 MiB')
    project = Path(__file__).resolve().parents[1]
    output = Path(args.output_directory).resolve()
    output.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='fixture-', dir=output)).resolve()
    for name in ('managed', 'courses', 'uploads', 'tmp'):
        (scratch / name).mkdir()
    selected = set()

    def port():
        while True:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                candidate = probe.getsockname()[1]
            if candidate not in selected:
                selected.add(candidate)
                return candidate

    ports = {name: port() for name in ('platform', 'content', 'intelligence', 'gateway', 'management')}
    base = f'http://127.0.0.1:{ports["gateway"]}'
    children = []
    logs = []
    report = {'profile': 'isolated H2', 'sizes': [], 'checks': [], 'content_peak_rss_bytes': None,
              'peak_observed_owned_data_bytes': 0, 'status': 'running'}
    stop = threading.Event()
    connection = http.client.HTTPConnection('127.0.0.1', ports['gateway'], timeout=180)

    def peak_rss(child):
        try:
            if os.name == 'nt':
                import ctypes
                from ctypes import wintypes
                class Counters(ctypes.Structure):
                    _fields_ = [('cb', wintypes.DWORD), ('faults', wintypes.DWORD)] + [
                        (name, ctypes.c_size_t) for name in ('peak', 'working', 'paged_peak', 'paged',
                                                            'nonpaged_peak', 'nonpaged', 'pagefile', 'pagefile_peak')]
                counters = Counters()
                counters.cb = ctypes.sizeof(counters)
                function = ctypes.WinDLL('psapi').GetProcessMemoryInfo
                function.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
                function.restype = wintypes.BOOL
                if function(int(child._handle), ctypes.byref(counters), counters.cb):
                    return counters.peak
            else:
                for line in Path(f'/proc/{child.pid}/status').read_text().splitlines():
                    if line.startswith('VmHWM:'):
                        return int(line.split()[1]) * 1024
        except (OSError, AttributeError, ValueError):
            pass
        return None

    def sample():
        while not stop.wait(0.15):
            total, seen = 0, set()
            for directory in ('managed', 'uploads', 'tmp'):
                for item in (scratch / directory).rglob('*'):
                    try:
                        if item.is_symlink() or not item.is_file():
                            continue
                        stat = item.stat()
                        identity = (stat.st_dev, stat.st_ino)
                        if identity not in seen:
                            total += stat.st_size
                            seen.add(identity)
                    except OSError:
                        pass
            report['peak_observed_owned_data_bytes'] = max(report['peak_observed_owned_data_bytes'], total)
            measured = peak_rss(children[1])
            if measured is not None:
                report['content_peak_rss_bytes'] = max(report['content_peak_rss_bytes'] or 0, measured)

    sampler = threading.Thread(target=sample, daemon=True)

    def call(path, data=None, token=None, method=None, expected=200, headers=None):
        request_headers = dict(headers or {})
        if token:
            request_headers['Authorization'] = 'Bearer ' + token
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data).encode()
            request_headers['Content-Type'] = 'application/json'
        verb = method or ('POST' if data is not None else 'GET')
        report['last_request'] = verb + ' ' + path.split('?')[0]
        # Match a browser's persistent connection instead of creating hundreds of
        # simultaneous TIME_WAIT sockets while exercising a 256-chunk file.
        connection.request(verb, path, body=data, headers=request_headers)
        response = connection.getresponse()
        status, body = response.status, response.read(131073)
        if len(body)>131072:
            connection.close()
            raise AssertionError('Unexpected large JSON response: '+path.split('?')[0])
        if status != expected:
            raise AssertionError(f'{path.split("?")[0]}: HTTP {status}, expected {expected}; {body[:180]!r}')
        result = json.loads(body) if body else {}
        if status == 200 and result.get('code', '0') != '0':
            raise AssertionError('Business failure: ' + path.split('?')[0])
        return result.get('data', result)

    def login(username, password):
        return call('/user/login', {'username': username, 'password': password})['token']

    started = time.monotonic()
    try:
        internal, password = secrets.token_hex(32), secrets.token_urlsafe(24)
        for name, heap in (('platform', '256m'), ('content', '256m'), ('intelligence', '256m'), ('gateway', '128m')):
            module = 'cloud-gateway' if name == 'gateway' else f'cloud-{name}-service'
            jar = project / module / 'target' / f'{module}-0.1.0-SNAPSHOT.jar'
            if not jar.is_file():
                raise RuntimeError('Build the independent backend before this verification')
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith(('SC_', 'SPRING_', 'STUDENT_CLOUD_', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS'))}
            env.update(SC_INTERNAL_KEY=internal, SC_BIND_ADDRESS='127.0.0.1', SC_PORT=str(ports[name]),
                       SC_PLATFORM_URI=f'http://127.0.0.1:{ports["platform"]}',
                       SC_CONTENT_URI=f'http://127.0.0.1:{ports["content"]}',
                       SC_INTELLIGENCE_URI=f'http://127.0.0.1:{ports["intelligence"]}',
                       SC_PLATFORM_WS_URI=f'ws://127.0.0.1:{ports["platform"]}',
                       SC_MANAGED_STORAGE_ROOT=str(scratch / 'managed'),
                       SC_COURSE_STORAGE_ROOT=str(scratch / 'courses'), SC_UPLOAD_STAGING_ROOT=str(scratch / 'uploads'),
                       SC_CONTENT_NODE_ID='a-local-fixture', SC_LOCAL_MAX_FILE_BYTES=str(1024**3),
                       SC_MAIL_ENABLED='false', SC_AI_API_KEY='', SC_ALLOWED_ORIGINS='http://127.0.0.1:5173')
            command = [args.java, '-Xms32m', '-Xmx' + heap, '-XX:MaxDirectMemorySize=64m',
                       '-Djava.io.tmpdir=' + str(scratch / 'tmp'), '-jar', str(jar),
                       '--spring.profiles.active=local', '--spring.main.banner-mode=off']
            if name == 'gateway':
                command.append('--management.server.port=' + str(ports['management']))
            log = (output / (name + '.log')).open('wb')
            logs.append(log)
            children.append(subprocess.Popen(command, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT,
                                              creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0))
        sampler.start()
        for name in ('platform', 'content', 'intelligence', 'gateway'):
            deadline = time.monotonic() + 100
            while True:
                if any(child.poll() is not None for child in children):
                    raise RuntimeError('A fixture service stopped; inspect isolated logs')
                try:
                    with urlopen(f'http://127.0.0.1:{ports[name]}/' + ('healthz' if name == 'gateway' else 'readyz'), timeout=1) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                if time.monotonic() > deadline:
                    raise RuntimeError('Readiness timed out: ' + name)
                time.sleep(0.2)
        report['checks'].append('four real JVMs with isolated in-memory databases')
        call('/api/install', {'siteName': 'A local fixture', 'username': 'fixture-root', 'password': password})
        admin = login('fixture-root', password)
        call('/api/platform/workspace-administrators', {'username': 'fixture-owner', 'email': 'owner@example.invalid',
             'password': password, 'workspaceName': 'Fixture space'}, admin)
        owner = login('fixture-owner', password)
        call('/api/workspace-admin/members', {'username': 'fixture-member', 'password': password}, owner)
        call('/api/workspace-admin/members', {'username': 'fixture-peer', 'password': password}, owner)
        member, peer = login('fixture-member', password), login('fixture-peer', password)
        requested = call('/api/workspace-storage/local-requests', {'reason': 'Isolated acceptance'}, owner)
        approved = call(f'/admin/storage-local-requests/{requested["id"]}/review', {'approve': True}, admin)
        mount = next(item for item in call('/api/drives', token=owner) if item['id'] == approved['mountId'])
        key = mount['key']
        application = call(f'/api/workspace-cloud/{mount["id"]}/apply', {}, member)
        call(f'/api/workspace-cloud/requests/{application["id"]}/review', {'approve': True, 'quotaBytes': 2*1024**3,
             'permissions': ['list', 'read', 'mkdir', 'upload', 'copy', 'move', 'rename', 'delete']}, owner)
        config = call('/api/site/config/storage', {'storageKey': key, 'path': '/'}, member)
        assert config['metadata']['maxDownloadFileSize'] == 1024**3
        block = bytearray(bytes(range(256)) * (4 * 1024 * 1024 // 256))
        def chunk(index, size):
            struct.pack_into('<Q',block,0,index)
            return bytes(block[:min(len(block),size-index*len(block))])
        for size in sizes:
            before = time.monotonic()
            count = (size + len(block) - 1) // len(block)
            hashes = [hashlib.sha256(chunk(index,size)).hexdigest() for index in range(count)]
            target = {'storageKey': key, 'path': '/', 'name': f'synthetic-{size}.bin', 'size': size}
            caps = call('/api/uploads/resumable/capabilities', target, member)
            assert caps['enabled'] and caps['maxFileSize'] == 1024**3 and caps['chunkSize'] == len(block)
            manifest = dict(target, chunkHashes=hashes, manifestHash=hashlib.sha256('\n'.join(hashes).encode()).hexdigest())
            session = call('/api/uploads/resumable/init', manifest, member)
            expected_hash = hashlib.sha256()
            for index in range(count):
                data = chunk(index,size)
                expected_hash.update(data)
                call(f'/api/uploads/resumable/{session["id"]}/chunks/{index}', data, member, method='PUT',
                     headers={'Content-Type': 'application/octet-stream', 'X-Upload-Chunk-SHA256': hashes[index]})
                if index == 2:
                    resumed = call('/api/uploads/resumable/init', manifest, member)
                    assert resumed['id'] == session['id'] and resumed['receivedChunks'] == [0, 1, 2]
            for _ in range(2):
                assert call(f'/api/uploads/resumable/{session["id"]}/complete', {}, member)['state'] == 'COMPLETED'
            url = f'/api/drives/{key}/content?' + urlencode({'path': '/' + target['name']})
            actual_hash, received = hashlib.sha256(), 0
            with urlopen(Request(base+url, headers={'Authorization': 'Bearer '+member}), timeout=180) as response:
                while data := response.read(64*1024):
                    received += len(data)
                    actual_hash.update(data)
            assert received == size and actual_hash.digest() == expected_hash.digest()
            call(url, expected=401)
            call(url, token=peer, expected=403)
            assert not any(item.is_file() for item in (scratch/'uploads').rglob('*'))
            report['sizes'].append({'bytes': size, 'chunks': count, 'sha256': actual_hash.hexdigest(),
                                    'seconds': round(time.monotonic()-before, 3), 'resumed_after_chunk': 3})
            print('PASS: streamed upload/download SHA256 ' + str(size) + ' bytes', flush=True)
        memory = call('/api/ai/memories', {'content': 'Synthetic personal preference'}, member)['items'][0]
        call('/api/ai/memories/'+memory['id'], {'content': 'Changed preference', 'expectedVersion': memory['version']}, member, method='PUT')
        call('/api/ai/memories/'+memory['id']+'?expectedVersion='+str(memory['version']), token=member, method='DELETE', expected=409)
        call('/api/ai/memories/'+memory['id'], token=member, method='DELETE', expected=400)
        assert call('/api/ai/memories', token=peer)['items'] == []
        assert call('/api/ai/memories', token=admin)['items'] == []
        report['checks'].extend(['resumable restart at chunk 3 and repeated completion', 'anonymous and cross-account download denied',
                                 '256MiB content heap', 'stale memory delete denied; missing version denied; personal isolation'])
        report['status'] = 'passed'
    except Exception as error:
        report.update(status='failed', error=str(error))
        raise
    finally:
        connection.close()
        stop.set()
        if sampler.is_alive():
            sampler.join(timeout=3)
        for child in children:
            child.terminate()
        for child in children:
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        for log in logs:
            log.close()
        report['seconds'] = round(time.monotonic()-started, 3)
        (output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        # Only remove this run's fresh fixture directory, never the evidence directory or other runs.
        if scratch.parent == output and scratch.name.startswith('fixture-'):
            shutil.rmtree(scratch)


if __name__ == '__main__':
    main()
