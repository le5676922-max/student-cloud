"""Four real JVMs + disposable MySQL + a loopback model. No external mail/model calls.

Build jars first. Run with Python 3 and Docker running; --java selects a JDK 21 executable.
This script creates and removes only its labelled fixture container and temporary credentials.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import uuid
import hashlib
import base64
from remote_storage_fixture import ObjectStorageFixture, verify_remote
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.client import HTTPException
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--mysql-image', default='mysql:8.4.11')
    parser.add_argument('--scratch', type=Path, default=Path(tempfile.gettempdir()))
    args = parser.parse_args()
    if not args.java:
        parser.error('Set --java to a JDK 21 java executable')
    project = Path(__file__).resolve().parents[1]
    run_id = uuid.uuid4().hex[:12]
    scratch = args.scratch.resolve() / ('student-cloud-workflows-' + run_id)
    scratch.mkdir(parents=True)
    (scratch / 'managed').mkdir(); (scratch / 'courses').mkdir();(scratch/'uploads').mkdir()
    children = []; model_calls = []; passed = []; container = 'student-cloud-qa-' + run_id
    windows = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    password = secrets.token_urlsafe(24); internal = secrets.token_hex(32)
    database_passwords = {service: secrets.token_hex(24) for service in ('platform', 'content', 'intelligence')}
    database_root = secrets.token_hex(32)
    credential_file = scratch / 'mysql-private.env'
    credential_file.write_text('MYSQL_ROOT_PASSWORD=' + database_root + '\n', encoding='utf-8')
    if os.name != 'nt': credential_file.chmod(0o600)

    def docker(*command, data=None, check=True):
        result = subprocess.run(['docker', *command], input=data, capture_output=True, **windows)
        if check and result.returncode:
            # Do not print SQL/credential-bearing Docker stderr.
            raise RuntimeError('Fixture Docker command failed: ' + command[0])
        return result

    used_ports=set()
    def port():
        # Stay outside the Windows dynamic client-port range to avoid TCP self-connects
        # while polling a JVM whose listening socket has not opened yet.
        for _ in range(100):
            candidate=20000+secrets.randbelow(20000)
            if candidate in used_ports: continue
            with socket.socket() as sock:
                try: sock.bind(('127.0.0.1',candidate))
                except OSError: continue
                used_ports.add(candidate);return candidate
        raise RuntimeError('Could not allocate isolated fixture ports')

    ports = {service: port() for service in ('platform', 'content', 'intelligence', 'gateway', 'management')}
    base = 'http://127.0.0.1:' + str(ports['gateway'])

    class Model(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            model_calls.append(body)
            answer = '模型模拟回答：请核对资料中的 atomic 说明。'
            data = json.dumps({'choices': [{'message': {'content': answer}, 'finish_reason': 'stop'}]}).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def log_message(self, *_): pass

    model = ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    remote_fixture=ObjectStorageFixture()
    storage_master=base64.b64encode(secrets.token_bytes(32)).decode()

    def call(path, data=None, token=None, method=None, status=200, raw=False, content_type=None):
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        if data is not None:
            headers['Content-Type'] = content_type or 'application/json'
            if not isinstance(data, bytes): data = json.dumps(data).encode()
        try:
            with urlopen(Request(base + path, data=data, headers=headers, method=method or ('POST' if data is not None else 'GET')), timeout=65) as response:
                actual, body = response.status, response.read()
        except HTTPError as error:
            actual, body = error.code, error.read()
        if actual != status:
            raise AssertionError(f'{path.split("?")[0]}: HTTP {actual}, expected {status}; ' + body[:240].decode('utf-8', 'replace'))
        if raw: return body
        result = json.loads(body) if body else {}
        if status == 200: assert result.get('code', '0') == '0', 'Business error: ' + path
        return result.get('data', result)

    def multipart(parts):
        boundary = 'qa' + uuid.uuid4().hex; chunks = []
        for name, filename, mime, value in parts:
            disposition = f'Content-Disposition: form-data; name="{name}"'
            if filename: disposition += f'; filename="{filename}"'
            chunks.extend([f'--{boundary}\r\n{disposition}\r\nContent-Type: {mime}\r\n\r\n'.encode(), value, b'\r\n'])
        chunks.append(f'--{boundary}--\r\n'.encode())
        return b''.join(chunks), 'multipart/form-data; boundary=' + boundary

    def mark(label):
        passed.append(label); print('PASS: ' + label, flush=True)

    def login(name): return call('/user/login', {'username': name, 'password': password})['token']

    def wait_task(plan, token):
        until = time.monotonic() + 20
        while time.monotonic() < until:
            result = call('/api/ai/tasks/' + plan['id'], token=token)
            if result['status'] not in ('PENDING', 'RUNNING'): return result
            time.sleep(.3)
        raise AssertionError('Confirmed task did not finish')

    try:
        docker('run', '--detach', '--name', container, '--label', 'student-cloud.qa=' + run_id,
               '--memory', '768m', '--env-file', str(credential_file), '--publish', '127.0.0.1::3306',
               '--tmpfs', '/var/lib/mysql:rw,size=384m', args.mysql_image,
               '--innodb-buffer-pool-size=96M', '--performance-schema=OFF')
        deadline = time.monotonic() + 100
        while True:
            # mysqladmin ping also exits zero for Access denied during first-time initialization.
            # Require an authenticated query over TCP after the temporary socket-only server stops.
            ping = docker('exec', container, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot --batch --skip-column-names -e "SELECT 1"', check=False)
            if ping.returncode == 0: break
            if time.monotonic() > deadline: raise RuntimeError('Fixture MySQL startup timeout')
            time.sleep(.5)
        published = docker('port', container, '3306/tcp').stdout.decode().strip()
        mysql_port = int(published.rsplit(':', 1)[1])
        sql = []
        for service, secret in database_passwords.items():
            sql.extend([f'CREATE DATABASE student_cloud_{service} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',
                        f"CREATE USER 'sc_{service}'@'%' IDENTIFIED BY '{secret}';",
                        f"GRANT ALL PRIVILEGES ON student_cloud_{service}.* TO 'sc_{service}'@'%';"])
        docker('exec', '-i', container, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot', data='\n'.join(sql).encode())
        credential_file.unlink()
        for service, heap in [('platform', '256m'), ('content', '384m'), ('intelligence', '256m'), ('gateway', '128m')]:
            env = os.environ.copy()
            for name in list(env):
                if name.startswith(('SC_', 'SPRING_DATASOURCE_', 'SPRING_APPLICATION_JSON')): env.pop(name)
            env.update(SC_INTERNAL_KEY=internal, SC_BIND_ADDRESS='127.0.0.1', SC_PORT=str(ports[service]),
                       SC_MAIL_ENABLED='false', SC_ALLOWED_ORIGINS='http://127.0.0.1:5173',
                       SC_PLATFORM_URI=f'http://127.0.0.1:{ports["platform"]}',
                       SC_CONTENT_URI=f'http://127.0.0.1:{ports["content"]}',
                       SC_INTELLIGENCE_URI=f'http://127.0.0.1:{ports["intelligence"]}',
                       SC_PLATFORM_WS_URI=f'ws://127.0.0.1:{ports["platform"]}',
                       SC_MANAGED_STORAGE_ROOT=str(scratch / 'managed'), SC_COURSE_STORAGE_ROOT=str(scratch / 'courses'),SC_UPLOAD_STAGING_ROOT=str(scratch/'uploads'),SC_CONTENT_NODE_ID='fixture-content',
                       SC_AI_BASE_URL=f'http://127.0.0.1:{model.server_port}/v1', SC_AI_MODEL='synthetic', SC_AI_API_KEY='fixture-not-a-provider-key')
            if service=='content':env.update(SC_STORAGE_MASTER_KEY=storage_master,SC_STORAGE_ALLOWED_ENDPOINTS=remote_fixture.origin)
            if service != 'gateway':
                env.update(SC_DB_URL=f'jdbc:mysql://127.0.0.1:{mysql_port}/student_cloud_{service}?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false',
                           SC_DB_USER='sc_' + service, SC_DB_PASSWORD=database_passwords[service])
            module = 'cloud-gateway' if service == 'gateway' else f'cloud-{service}-service'
            jar = project / module / 'target' / f'{module}-0.1.0-SNAPSHOT.jar'
            command = [args.java, '-Xms48m', '-Xmx' + heap, '-XX:MaxDirectMemorySize=64m', '-jar', str(jar),
                       '--spring.profiles.active=mysql', '--spring.main.banner-mode=off']
            if service == 'gateway': command.append('--management.server.port=' + str(ports['management']))
            with (scratch / (service + '.log')).open('wb') as output:
                children.append(subprocess.Popen(command, cwd=project, env=env, stdout=output, stderr=subprocess.STDOUT, **windows))
        for service in ('platform', 'content', 'intelligence', 'gateway'):
            deadline = time.monotonic() + 100
            while True:
                if any(p.poll() is not None for p in children): raise RuntimeError('A service stopped; inspect fixture logs')
                try:
                    with urlopen(f'http://127.0.0.1:{ports[service]}/' + ('healthz' if service == 'gateway' else 'readyz'), timeout=1) as response:
                        if response.status == 200: break
                except (OSError,HTTPException): pass
                if time.monotonic() > deadline: raise RuntimeError('Service readiness timeout: ' + service)
                time.sleep(.3)
        mark('four JVMs start against three MySQL schemas with separate least-privilege users')
        call('/api/install', {'siteName': 'Workflow fixture', 'username': 'qa-root', 'password': password}); admin = login('qa-root')
        owner = call('/api/platform/workspace-administrators', {'username': 'qa-owner', 'email': 'owner@example.invalid', 'password': password, 'workspaceName': 'QA Space'}, admin)
        owner_token = login('qa-owner')
        member = call('/api/workspace-admin/members', {'username': 'qa-member', 'password': password}, owner_token); token = login('qa-member')
        peer = call('/api/workspace-admin/members', {'username': 'qa-peer', 'password': password}, owner_token); peer_token = login('qa-peer')
        call('/api/storage/list', status=401)
        mark('installation, real sessions and anonymous rejection')
        verify_remote(call,multipart,mark,remote_fixture,admin,owner_token,token,peer_token,owner,member,peer)
        for identity,question,expected_help in (
            (owner_token,'普通管理员怎么连接阿里云 OSS','remote-storage-add'),
            (token,'远程云盘成员容量不足怎么办','remote-member-privacy-quota'),
            (admin,'远程写入结果未确认怎么核查','remote-pending-write-inspection')):
            helped=call('/api/ai/chat',{'message':question,'answerStyle':'concise'},identity)
            assert helped['knowledge'][0]['id']==expected_help
        mark('role-scoped remote product help is served by the chat pipeline using the new bundled catalogue')
        request = call('/api/workspace-storage/local-requests', {'reason': 'Course storage'}, owner_token)
        approved = call(f'/admin/storage-local-requests/{request["id"]}/review', {'approve': True}, admin)
        mount = next(row for row in call('/api/drives', token=owner_token) if row['id'] == approved['mountId']); key = mount['key']
        application = call(f'/api/workspace-cloud/{mount["id"]}/apply', {}, token)
        permissions = ['list', 'read', 'mkdir', 'upload', 'copy', 'move', 'rename', 'delete']
        call(f'/api/workspace-cloud/requests/{application["id"]}/review', {'approve': True, 'quotaBytes': 1048576, 'permissions': permissions}, owner_token)
        grants = call(f'/api/workspace-admin/members/{member["id"]}/permissions', token=owner_token)
        assert grants['userId'] == member['id'] and grants['storages']
        call(f'/api/workspace-admin/members/{member["id"]}/permissions', token=admin, status=403)
        assert call('/admin/storages', token=admin)
        call(f'/admin/cache/{mount["id"]}/enable', {}, admin, status=501)
        probe_root=scratch/'managed'/'probe';probe_root.mkdir()
        config={'type':'local','key':'fixture-probe','name':'Probe','enable':True,'storageSourceAllParam':{'filePath':str(probe_root)},'verifyWrite':True}
        assert call('/admin/storage/test-connection',config,admin)['passed'] is True
        assert not list(probe_root.iterdir())
        mark('local storage requires global approval; member quotas belong to workspace administrator')
        content = b'transaction isolation and atomic commit'
        file_body, file_type = multipart([('file', 'notes.txt', 'text/plain', content)])
        call(f'/api/drives/{key}/files?path=%2Fnotes.txt', file_body, token, method='PUT', content_type=file_type)
        call(f'/api/drives/{key}/content?path=%2Fnotes.txt', token=peer_token, raw=True, status=403)
        assert call(f'/api/drives/{key}/content?path=%2Fnotes.txt', token=token, raw=True) == content
        assert call(f'/api/drives/{key}/preview?path=%2Fnotes.txt', token=token,raw=True)==content
        call(f'/api/drives/{key}/preview?path=%2Fnotes.txt',token=peer_token,raw=True,status=403)
        personal = call('/api/learning/courses', {'name': 'Database', 'semester': 'Autumn'}, token)
        plan = call('/api/ai/tasks', {'type': 'IMPORT_LEARNING', 'storageKey': key, 'path': '/', 'courseId': personal['id'], 'tags': 'exam'}, token)
        assert call('/api/learning/materials?courseId=' + str(personal['id']), token=token) == []
        call('/api/ai/tasks/' + plan['id'] + '/confirm', {'confirmed': True}, peer_token, status=404)
        call('/api/ai/tasks/' + plan['id'] + '/confirm', {'confirmed': True}, token)
        assert wait_task(plan, token)['status'] == 'SUCCEEDED'
        assert len(call('/api/learning/materials?courseId=' + str(personal['id']), token=token)) == 1
        assert call(f'/api/drives/{key}/content?path=%2Fnotes.txt', token=token, raw=True) == content
        mark('Agent imports actual personal course indexes only after owner confirmation, preserving file')
        answer = call('/api/ai/chat', {'message': 'atomic', 'learningCourseId': personal['id'], 'optInPrivateContext': True}, token)
        assert answer['citations'] and any('transaction isolation' in row['content'] for row in model_calls[-1]['messages'])
        feedback = call('/api/ai/feedback', {'question': 'atomic', 'answer': answer['answer'], 'clientMessageId': answer['messageId']}, token)
        call('/api/ai/feedback', {'question': 'atomic', 'answer': 'forged', 'clientMessageId': answer['messageId']}, token, status=409)
        detail = call('/admin/ai-feedback/' + feedback['id'], token=admin)
        assert detail['canPublish'] is False
        call('/admin/ai-knowledge/refine', {'feedbackId': feedback['id']}, admin,status=400)
        draft=call('/admin/ai-knowledge/refine', {'feedbackId': feedback['id'],'expectedUpdatedAt':detail['updatedAt'],'administratorAnswer':'Please check the document version before applying the atomic commit explanation.'}, admin)
        assert draft['requiresReview'] is True and draft['sourceUpdatedAt']==detail['updatedAt']
        call('/api/ai/chat', {'message': 'atomic', 'learningCourseId': personal['id'], 'optInPrivateContext': False}, token)
        assert not any('transaction isolation' in row['content'] for row in model_calls[-1]['messages'])
        stream = call('/api/ai/chat/stream/' + str(uuid.uuid4()), {'message': 'hello'}, token, raw=True)
        assert b'event:done' in stream and b'event:token' in stream
        body, mime = multipart([('request', None, 'application/json', json.dumps({'message': 'Read attachment'}).encode()), ('files', 'notes.txt', 'text/plain', b'fixture temporary attachment')])
        temporary = call('/api/ai/chat/files', body, token, content_type=mime)
        assert temporary['messageId'] and any('fixture temporary attachment' in row['content'] for row in model_calls[-1]['messages'])
        mark('RAG consent, authentic feedback, private publication guard, model draft, SSE and temporary attachments')
        duplicate_body, duplicate_type = multipart([('file', 'copy.txt', 'text/plain', content)])
        call(f'/api/drives/{key}/files?path=%2Fcopy.txt', duplicate_body, token, method='PUT', content_type=duplicate_type)
        duplicates = call('/api/ai/tasks/duplicates?' + urlencode({'storageKey': key, 'path': '/'}), token=token)
        assert len(duplicates['groups']) == 1 and duplicates['checkedFiles'] == 2
        schedule = call('/api/ai/schedules', {'type': 'RENAME_BY_DATE', 'storageKey': key, 'path': '/', 'title': 'Weekly fixture', 'dayOfWeek': 1, 'hourOfDay': 9, 'minuteOfHour': 0}, token)
        call(f'/api/ai/schedules/{schedule["id"]}/preview', {}, peer_token, status=404)
        preview = call(f'/api/ai/schedules/{schedule["id"]}/preview', {}, token)
        assert preview['task']['status'] == 'PENDING' and not preview['task']['taskId']
        paused = call(f'/api/ai/schedules/{schedule["id"]}/enabled?value=false', {}, token)
        assert paused['enabled'] is False
        mark('read-only byte duplicate check and owner-scoped weekly previews without automatic writes')
        course = call('/api/courses', {'name': 'Shared Database', 'description': 'fixture', 'quotaBytes': 1048576, 'enabled': True}, owner_token)
        invitation = call(f'/api/courses/{course["id"]}/invitations', {'maxMembers': 1}, owner_token)
        call('/api/courses/join', {'code': invitation['code']}, token)
        call('/api/courses/join', {'code': invitation['code']}, peer_token, status=409)
        call(f'/api/courses/{course["id"]}/materials', file_body, token, content_type=file_type, status=403)
        call(f'/api/courses/{course["id"]}/upload-request', {}, token)
        call(f'/api/courses/{course["id"]}/members/{member["id"]}/upload', {'approve': True}, owner_token)
        shared = call(f'/api/courses/{course["id"]}/materials', file_body, token, content_type=file_type)
        assert call(f'/api/courses/{course["id"]}/materials/{shared["id"]}/download', token=owner_token, raw=True) == content
        call(f'/api/courses/{course["id"]}/materials', token=peer_token, status=403)
        mark('course invitations expire at capacity; creator approves ongoing uploads; downloads require membership')
        call('/admin/overview', token=token, status=403)
        assert call('/admin/overview', token=admin)
        assert call('/admin/overview/workspaces', token=admin)['accountModel'] == 'independent'
        mark('global console and role boundaries')
        # Six adopted file businesses use the same production gateway, grants and MySQL migrations.
        files=call('/api/storage/files',{'storageKey':key,'path':'/'},token)['files']
        source=next(row['name'] for row in files if row['name'].endswith('notes.txt'))
        catalog=call(f'/api/drives/{key}/catalog?'+urlencode({'path':'/','pageSize':1,'query':'notes'}),token=token)
        assert catalog['total']>=1 and len(catalog['records'])==1
        share=call('/api/shares',{'storageKey':key,'path':'/'+source,'audience':'OWN_WORKSPACE'},token)
        assert call('/api/shares/'+share['id']+'/content',token=peer_token,raw=True)==content
        call('/api/shares/'+share['id'],status=401)
        call('/api/shares/'+share['id']+'?version='+str(share['version']),token=token,method='DELETE')
        call('/api/shares/'+share['id']+'/content',token=peer_token,raw=True,status=410)
        call(f'/api/drives/{key}/entries?'+urlencode({'path':'/'+source}),token=token,method='DELETE')
        trash=call(f'/api/drives/{key}/recycle-bin',token=token);assert len(trash)==1
        call(f'/api/drives/{key}/recycle-bin',token=peer_token,status=403)
        call(f'/api/drives/{key}/recycle-bin/{trash[0]["id"]}/restore',{},token)
        assert call(f'/api/drives/{key}/content?'+urlencode({'path':'/'+source}),token=token,raw=True)==content
        mark('catalog SQL query, authenticated share/revoke, private recycle and restoration')
        # Raise this fixture member quota through the actual workspace-admin delegation endpoint.
        call(f'/api/workspace-admin/members/{member["id"]}/drives/{mount["id"]}/grant',{'quotaBytes':16*1024*1024,'permissions':permissions},owner_token,method='PUT')
        blob=b'a'*(8*1024*1024+7);chunks=[blob[i:i+4*1024*1024] for i in range(0,len(blob),4*1024*1024)]
        hashes=[hashlib.sha256(chunk).hexdigest() for chunk in chunks];manifest=hashlib.sha256('\n'.join(hashes).encode('ascii')).hexdigest()
        upload=call('/api/uploads/resumable/init',{'storageKey':key,'path':'/','name':'large.bin','size':len(blob),'chunkHashes':hashes,'manifestHash':manifest},token)
        for index,chunk in enumerate(chunks):
            request=Request(base+f'/api/uploads/resumable/{upload["id"]}/chunks/{index}',data=chunk,method='PUT',headers={'Authorization':'Bearer '+token,'Content-Type':'application/octet-stream','X-Upload-Chunk-SHA256':hashes[index]})
            with urlopen(request,timeout=45) as response:assert response.status==200
            if index==0:
                restored=call('/api/uploads/resumable/'+upload['id'],token=token);assert restored['receivedChunks']==[0]
        completed=call('/api/uploads/resumable/'+upload['id']+'/complete',{},token);assert completed['state']=='COMPLETED'
        assert call('/api/uploads/resumable/'+upload['id']+'/complete',{},token)['state']=='COMPLETED'
        assert call(f'/api/drives/{key}/content?path=%2Flarge.bin',token=token,raw=True)==blob
        stats=call('/admin/file-business/overview',token=admin);assert stats['metrics']['mounts']['value']>=1
        call('/admin/file-business/overview',token=token,status=403)
        mark('durable chunk receipt/resume, idempotent no-overwrite completion and real scoped statistics')
        # Knowledge/reply quality and the reviewed feedback publication loop use a synthetic local model.
        long_question='如何使用回收站，恢复后会覆盖同名文件吗？'+'我希望了解具体使用步骤和限制。'*20
        helped=call('/api/ai/chat',{'message':long_question,'answerStyle':'concise'},token)
        assert helped['knowledge'] and '简洁' in model_calls[-1]['messages'][0]['content']
        assert not any(str(item.get('source','')).startswith('backend-independent/') for item in helped['knowledge'])
        detailed=call('/api/ai/chat',{'message':'请详细说明回收站恢复方法','answerStyle':'detailed'},token)
        assert detailed['knowledge'] and '详细' in model_calls[-1]['messages'][0]['content']
        before=len(model_calls)
        clarified=call('/api/ai/chat',{'message':'帮我整理当前目录的文件'},token)
        assert not clarified['plans'] and len(model_calls)==before
        previewed=call('/api/ai/chat',{'message':'帮我整理当前目录的文件','storageKey':key,'path':'/'},token)
        assert previewed['plans'] and previewed['plans'][0]['status']=='PENDING' and len(model_calls)==before
        public_feedback=call('/api/ai/feedback',{'question':long_question,'answer':helped['answer'],'clientMessageId':helped['messageId']},token)
        public_detail=call('/admin/ai-feedback/'+public_feedback['id'],token=admin)
        assert public_detail['canPublish'] is True
        correction='回收站内容保留7天，仍占用容量；恢复遇到同名文件会拒绝，不覆盖已有文件。'
        reviewed=call('/admin/ai-feedback/'+public_feedback['id'],{'expectedVersion':public_detail['version'],'correctedAnswer':correction,'status':'RESOLVED'},admin,method='PUT')
        state=call('/admin/ai-knowledge/feedback/'+public_feedback['id'],token=admin)
        published=call('/admin/ai-knowledge/publish',{'entries':[{'feedbackId':public_feedback['id'],'expectedFeedbackVersion':reviewed['version'],'expectedUpdatedAt':reviewed['updatedAt'],'expectedVersion':state['version'],'title':'回收站恢复规则核验','answer':correction,'scope':'GLOBAL','confirmGlobal':True}]},admin)
        recalled=call('/api/ai/knowledge/search?'+urlencode({'query':'恢复同名文件会覆盖吗'}),token=token)
        assert any(hit['title']=='回收站恢复规则核验' for hit in recalled)
        call('/admin/ai-knowledge/withdraw',{'feedbackId':public_feedback['id'],'expectedVersion':published[0]['version']},admin)
        assert not any(hit['title']=='回收站恢复规则核验' for hit in call('/api/ai/knowledge/search?'+urlencode({'query':'恢复同名文件会覆盖吗'}),token=token))
        mark('long-question Chinese project recall, explicit answer styles, clarification/preview and reviewed knowledge publish/withdraw')
        report = {'passed': passed, 'database': args.mysql_image, 'model': 'loopback synthetic fixture', 'external_calls': False, 'services': 4}
        (scratch / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print('RESULT_DIR: ' + str(scratch), flush=True)
    finally:
        for process in children:
            if process.poll() is None: process.terminate()
        for process in children:
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        model.shutdown(); model.server_close()
        remote_fixture.close()
        # Only the generated fixture with the matching ownership label may be removed.
        label = docker('inspect', '--format', '{{index .Config.Labels "student-cloud.qa"}}', container, check=False)
        if label.returncode == 0 and label.stdout.decode().strip() == run_id:
            docker('rm', '--force', container, check=False)
        if credential_file.exists(): credential_file.unlink()


if __name__ == '__main__': main()
