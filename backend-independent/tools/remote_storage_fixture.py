"""Loopback OSS/S3 protocol fixture and authenticated gateway workflow assertions.

This is a deterministic protocol fixture, not a real Aliyun/AWS account acceptance test.
Only synthetic credentials and memory-owned objects are used. No external requests.
"""
import hashlib
import threading
from datetime import datetime, timezone
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit, urlencode
from xml.sax.saxutils import escape


class ObjectStorageFixture:
    def __init__(self):
        self.objects = {}
        self.requests = []
        self.fail_publication_response_once = None
        self.lock = threading.Lock()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def log_message(self, *_): pass
            def reply(self, status, body=b'', headers=None):
                self.send_response(status)
                self.send_header('Content-Length', str(len(body)))
                for key, value in (headers or {}).items(): self.send_header(key, str(value))
                self.end_headers()
                if self.command != 'HEAD': self.wfile.write(body)
            def error(self, status, code):
                self.reply(status, ('<Error><Code>'+code+'</Code><Message>Fixture rejection</Message><RequestId>fixture</RequestId></Error>').encode(), {'Content-Type':'application/xml'})
            def do_HEAD(self): self.handle_object()
            def do_GET(self): self.handle_object()
            def do_PUT(self): self.handle_object()
            def do_DELETE(self): self.handle_object()
            def handle_object(self):
                parsed = urlsplit(self.path)
                parts = unquote(parsed.path).lstrip('/').split('/', 1)
                bucket, key = parts[0], parts[1] if len(parts)>1 else ''
                query = parse_qs(parsed.query, keep_blank_values=True)
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                auth = self.headers.get('Authorization','')
                if not auth.startswith(('AWS4-HMAC-SHA256 ', 'OSS4-HMAC-SHA256 ')):
                    return self.error(403,'AccessDenied')
                with fixture.lock:
                    # Preserve only route/method/conditional flags, never Authorization or credentials.
                    fixture.requests.append((self.command,bucket,key,'if-none-match' in self.headers or 'x-oss-forbid-overwrite' in self.headers))
                    if not key and self.command=='GET' and 'versioning' in query:
                        return self.reply(200,b'<VersioningConfiguration/>',{'Content-Type':'application/xml'})
                    if not key and self.command=='GET':
                        prefix=query.get('prefix',[''])[0];delimiter=query.get('delimiter',[''])[0]
                        limit=int(query.get('max-keys',['1000'])[0]);offset=int(query.get('continuation-token',['0'])[0])
                        files=[];prefixes=set()
                        for (stored_bucket, stored_key), item in sorted(fixture.objects.items()):
                            if stored_bucket!=bucket or not stored_key.startswith(prefix):continue
                            tail=stored_key[len(prefix):]
                            if delimiter and delimiter in tail:prefixes.add(prefix+tail.split(delimiter,1)[0]+delimiter)
                            else:files.append((stored_key,item))
                        entries=[('file',v) for v in files]+[('prefix',v) for v in sorted(prefixes)]
                        page=entries[offset:offset+limit];more=offset+limit<len(entries)
                        xml='<ListBucketResult><Name>'+escape(bucket)+'</Name><Prefix>'+escape(prefix)+'</Prefix><KeyCount>'+str(len(page))+'</KeyCount><MaxKeys>'+str(limit)+'</MaxKeys><IsTruncated>'+str(more).lower()+'</IsTruncated>'
                        for kind,value in page:
                            if kind=='prefix':xml+='<CommonPrefixes><Prefix>'+escape(value)+'</Prefix></CommonPrefixes>'
                            else:
                                name,item=value
                                xml+='<Contents><Key>'+escape(name)+'</Key><LastModified>2026-10-27T00:00:00.000Z</LastModified><ETag>'+escape(item['etag'])+'</ETag><Size>'+str(len(item['body']))+'</Size><StorageClass>STANDARD</StorageClass></Contents>'
                        if more:xml+='<NextContinuationToken>'+str(offset+limit)+'</NextContinuationToken>'
                        return self.reply(200,(xml+'</ListBucketResult>').encode(),{'Content-Type':'application/xml'})
                    owned=(bucket,key);item=fixture.objects.get(owned)
                    if self.command=='PUT':
                        condition=self.headers.get('If-None-Match')=='*' or self.headers.get('x-oss-forbid-overwrite')=='true'
                        if not condition:return self.error(400,'InvalidRequest')
                        if item is not None:return self.error(412 if auth.startswith('AWS') else 409,'PreconditionFailed' if auth.startswith('AWS') else 'FileAlreadyExists')
                        metadata={k.lower():v for k,v in self.headers.items() if k.lower().startswith(('x-amz-meta-','x-oss-meta-'))}
                        item={'body':body,'etag':'"'+hashlib.md5(body).hexdigest()+'"','metadata':metadata};fixture.objects[owned]=item
                        if fixture.fail_publication_response_once==(bucket,key.rsplit('/',1)[-1]):
                            fixture.fail_publication_response_once=None
                            return self.error(503,'ServiceUnavailable')
                        return self.reply(200,headers={'ETag':item['etag']})
                    if item is None:return self.error(404,'NoSuchKey')
                    if self.command=='DELETE':
                        if self.headers.get('If-Match')!=item['etag']:return self.error(412,'PreconditionFailed')
                        del fixture.objects[owned];return self.reply(204)
                    headers={'ETag':item['etag'],'Last-Modified':formatdate(usegmt=True),'Content-Type':'application/octet-stream',**item['metadata']}
                    return self.reply(200,item['body'],headers)
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.server.daemon_threads=True
        self.origin='http://127.0.0.1:'+str(self.server.server_port)
        threading.Thread(target=self.server.serve_forever,daemon=True).start()

    def close(self):
        self.server.shutdown();self.server.server_close()


def verify_remote(call,multipart,mark,fixture,admin_token,owner_token,member_token,peer_token,owner,member,peer):
    workspace=owner['workspaceId']
    types=call('/admin/support-storage',token=owner_token)
    assert {'local','aliyun','s3','minio'} <= {row['key'] for row in types}
    for provider in ('aliyun','s3'):
        params={'endPoint':fixture.origin,'bucketName':provider+'-fixture','region':'cn-hangzhou' if provider=='aliyun' else 'us-east-1',
                'basePath':'private-project','pathStyleAccess':True,'accessKey':'fixture-access','secretKey':'fixture-secret'}
        config={'name':provider+' fixture','key':provider+'-fixture','type':provider,'tenantId':workspace,'enable':True,'verifyWrite':True,'storageSourceAllParam':params}
        fields=call('/admin/storage-params?storageType='+provider,token=owner_token)
        assert any(row['key']=='pathStyleAccess' and row['type']=='switch' for row in fields)
        result=call('/admin/storage/test-connection',config,owner_token)
        assert result['passed'] is True and result['cleanup'] is True and result['writeVerified'] is True
        assert not any(bucket==params['bucketName'] for bucket,_ in fixture.objects), 'Probe cleanup left an object in the new fixture bucket'
        # Test both globally provisioned and workspace-admin-owned additions.
        mount_id=call('/admin/storage',config,admin_token if provider=='aliyun' else owner_token)
        detail=call('/admin/storage/'+str(mount_id),token=owner_token)
        assert detail['credentialsConfigured'] and detail['writeVerified']
        assert 'fixture-secret' not in str(detail) and 'fixture-access' not in str(detail)
        edited=dict(detail,type=provider,verifyWrite=True)
        call('/admin/storage',edited,owner_token)
        call('/admin/storage',edited,owner_token,status=409)
        key=config['key']
        assert call('/api/drives/'+key+'/entries',token=owner_token)==[]
        call('/api/drives/'+key+'/entries',token=member_token,status=403)
        request=call('/api/workspace-cloud/'+str(mount_id)+'/apply',{},member_token)
        permissions=['list','read','mkdir','upload']
        call('/api/workspace-cloud/requests/'+str(request['id'])+'/review',{'approve':True,'quotaBytes':64,'permissions':permissions},owner_token)
        peer_request=call('/api/workspace-cloud/'+str(mount_id)+'/apply',{},peer_token)
        call('/api/workspace-cloud/requests/'+str(peer_request['id'])+'/review',{'approve':True,'quotaBytes':64,'permissions':permissions},owner_token)
        call('/api/drives/'+key+'/directories',{'path':'/notes'},member_token)
        content=b'private remote byte consistency'
        body,mime=multipart([('file','notes.txt','text/plain',content)])
        call('/api/drives/'+key+'/files?path=%2Fnotes%2Fnotes.txt',body,member_token,method='PUT',content_type=mime)
        assert call('/api/drives/'+key+'/content?path=%2Fnotes%2Fnotes.txt',token=member_token,raw=True)==content
        assert call('/api/drives/'+key+'/entries?path=%2Fnotes',token=member_token)[0]['name']=='notes.txt'
        call('/api/drives/'+key+'/files?path=%2Fnotes%2Fnotes.txt',body,member_token,method='PUT',content_type=mime,status=409)
        call('/api/drives/'+key+'/content?path=%2Fnotes%2Fnotes.txt',token=peer_token,raw=True,status=404)
        assert call('/api/drives/'+key+'/entries',token=owner_token)==[]
        oversized,large_mime=multipart([('file','large.txt','text/plain',b'x'*65)])
        call('/api/drives/'+key+'/files?path=%2Flarge.txt',oversized,member_token,method='PUT',content_type=large_mime,status=409)
        capabilities=call('/api/site/config/storage',{'storageKey':key,'path':'/'},member_token)
        assert capabilities['permission']['upload'] and not capabilities['permission']['delete'] and not capabilities['permission']['createShareLink']
        assert not capabilities['metadata']['recycleBin']
        call('/api/drives/'+key+'/entries?path=%2Fnotes%2Fnotes.txt',token=member_token,method='DELETE',status=501)
        call('/api/drives/'+key+'/entries',status=401)
        lost_bytes=b'ack uncertainty'
        lost_body,lost_type=multipart([('file','lost-ack.txt','text/plain',lost_bytes)])
        fixture.fail_publication_response_once=(params['bucketName'],'lost-ack.txt')
        call('/api/drives/'+key+'/files?path=%2Flost-ack.txt',lost_body,member_token,method='PUT',content_type=lost_type,status=503)
        pending=call('/admin/storage/'+str(mount_id)+'/pending-writes',token=owner_token)
        assert len(pending)==1 and pending[0]['status']=='UNCERTAIN' and pending[0]['path']=='/lost-ack.txt'
        call('/admin/storage/'+str(mount_id)+'/pending-writes',token=member_token,status=403)
        puts_before=sum(method=='PUT' for method,*_ in fixture.requests)
        call('/api/drives/'+key+'/files?path=%2Fagain.txt',lost_body,member_token,method='PUT',content_type=lost_type,status=409)
        checked=call('/admin/storage/'+str(mount_id)+'/pending-writes/'+pending[0]['id']+'/inspect',{},owner_token)
        assert checked['result']=='CONFIRMED' and checked['status']=='COMPLETED'
        assert sum(method=='PUT' for method,*_ in fixture.requests)==puts_before, 'Inspection must never replay a PUT'
        assert call('/api/drives/'+key+'/content?path=%2Flost-ack.txt',token=member_token,raw=True)==lost_bytes
        assert call('/admin/storage/'+str(mount_id)+'/pending-writes',token=owner_token)==[]
        grants=call('/api/workspace-admin/members/'+str(member['id'])+'/permissions',token=owner_token)
        selected=next(row for row in grants['storages'] if row['storageSourceId']==mount_id)
        assert set(selected['allowedPermissions'])==set(permissions)
        call('/api/workspace-admin/members/'+str(member['id'])+'/permissions',{'defaultPermissions':[],'storages':[dict(selected,enable=False,permissions=[])]},owner_token,method='PUT')
        call('/api/drives/'+key+'/content?path=%2Fnotes%2Fnotes.txt',token=member_token,raw=True,status=403)
        pending=call('/admin/storage/'+str(mount_id)+'/pending-writes',token=owner_token);assert pending==[]
        assert all('.studentcloud-probes/' not in object_key for _,object_key in fixture.objects)
        mark(provider+' native SDK: encrypted config, probe/cleanup, private grants/quota, no overwrite, uncertain ACK inspection without replay, revoke and UI capabilities')
