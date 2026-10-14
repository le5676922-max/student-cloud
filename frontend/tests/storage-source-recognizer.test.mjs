import assert from 'node:assert/strict'
import test from 'node:test'
import { detectStorageSource } from '../src/utils/storage-source-recognizer.mjs'

const supported = ['local', 'ftp', 'sftp', 'webdav', 's3', 'minio', 'aliyun']
const detect = (value, allowed = supported) =>
	detectStorageSource(value, allowed)
const one = (value) => {
	const result = detect(value)
	assert.equal(result.candidates.length, 1)
	return result.candidates[0]
}

test('Unix, Windows drive and UNC absolute directories are certain without disk probes', () => {
	for (const filePath of [
		'/data/files',
		'/srv/学习资料',
		'/data/My Files',
		'D:\\Cloud Files\\Courses',
		'D:/Cloud Files',
		'\\\\server\\Share Files\\Course',
	]) {
		assert.deepEqual(one(filePath).suggestions, { filePath })
		assert.equal(one(filePath).type, 'local')
		assert.equal(one(filePath).confidence, 'certain')
	}
	assert.match(detect('/data/files').notice, /尚未验证/)
})

test('explicit FTP and SFTP split host, real port and decoded base path only', () => {
	assert.deepEqual(
		one('sftp://files.example.test:2222/My%20Files').suggestions,
		{ host: 'files.example.test', port: 2222, basePath: '/My Files' }
	)
	assert.deepEqual(one('ftp://files.example.test/course').suggestions, {
		host: 'files.example.test',
		port: 21,
		basePath: '/course',
	})
	assert.deepEqual(one('sftp://[2001:db8::1]/').suggestions, {
		host: '[2001:db8::1]',
		port: 22,
		basePath: '/',
	})
	assert.equal(one('SFTP://files.example.test/').type, 'sftp')
	assert.equal(one('sftp://files.example.test/').confidence, 'certain')
})

test('explicit WebDAV schemes map to the corresponding HTTP protocol without guessing credentials', () => {
	assert.deepEqual(one('webdav://files.example.test/dav/').suggestions, {
		url: 'http://files.example.test/dav/',
	})
	assert.deepEqual(
		one('webdavs://files.example.test/My%20Files/').suggestions,
		{ url: 'https://files.example.test/My%20Files/' }
	)
	assert.equal(one('webdavs://files.example.test/dav/').confidence, 'certain')
})

test('generic HTTP(S) root endpoints remain ambiguous even on common MinIO ports', () => {
	for (const address of [
		'http://127.0.0.1:9000',
		'https://files.example.test:9001',
		'https://minio.example.test',
	]) {
		const result = detect(address)
		assert.deepEqual(
			result.candidates.map((item) => item.type),
			['minio', 's3', 'webdav']
		)
		assert.ok(result.candidates.every((item) => item.confidence === 'possible'))
		assert.match(result.notice, /无法仅凭地址确定类型/)
		assert.ok(
			result.candidates.every(
				(item) =>
					!('bucketName' in item.suggestions) && !('region' in item.suggestions)
			)
		)
	}
	assert.deepEqual(detect('http://127.0.0.1:9000/').candidates[0].suggestions, {
		endPoint: 'http://127.0.0.1:9000',
	})
})

test('WebDAV-looking HTTP path is merely a possible protocol and never an object endpoint', () => {
	for (const address of [
		'https://files.example.test/dav/',
		'https://files.example.test/remote.php/dav/files/alice/',
		'https://files.example.test/bucket/object.pdf',
	]) {
		const candidate = one(address)
		assert.equal(candidate.type, 'webdav')
		assert.equal(candidate.confidence, 'possible')
		assert.deepEqual(candidate.suggestions, { url: address })
	}
})

test('bare ordinary hosts do not receive fabricated HTTPS or a MinIO guess', () => {
	const result = detect('files.example.test:9000')
	assert.deepEqual(
		result.candidates.map((item) => item.type),
		['s3', 'webdav']
	)
	assert.deepEqual(result.candidates[0].suggestions, {
		endPoint: 'files.example.test:9000',
	})
	assert.deepEqual(result.candidates[1].suggestions, {})
	assert.ok(
		result.candidates.every((item) => !('endPointScheme' in item.suggestions))
	)
	assert.match(result.notice, /无法仅凭地址确定类型/)
	assert.deepEqual(detect('files.example.test/dav').candidates, [])
})

test('classic official OSS host is certain and only explicit protocol becomes a scheme suggestion', () => {
	for (const endPoint of [
		'oss-cn-hangzhou.aliyuncs.com',
		'oss-cn-hangzhou-internal.aliyuncs.com',
		'oss-us-west-1.aliyuncs.com',
		'oss-ap-southeast-1.aliyuncs.com',
	]) {
		const candidate = one(endPoint)
		assert.equal(candidate.type, 'aliyun')
		assert.equal(candidate.confidence, 'certain')
		assert.deepEqual(candidate.suggestions, { endPoint })
		assert.deepEqual(one('http://' + endPoint).suggestions, {
			endPoint,
			endPointScheme: 'http',
		})
		assert.deepEqual(one('https://' + endPoint + '/').suggestions, {
			endPoint,
			endPointScheme: 'https',
		})
	}
})

test('OSS bucket hostname splits one legal bucket from endpoint and never duplicates its prefix', () => {
	const endPoint = 'oss-cn-hangzhou.aliyuncs.com'
	for (const bucketName of [
		'abc',
		'course-files',
		'a' + 'b'.repeat(61) + 'c',
	]) {
		assert.deepEqual(
			one('https://' + bucketName + '.' + endPoint).suggestions,
			{ endPoint, endPointScheme: 'https', bucketName }
		)
		assert.deepEqual(one(bucketName + '.' + endPoint).suggestions, {
			endPoint,
			bucketName,
		})
	}
	assert.equal('region' in one('course-files.' + endPoint).suggestions, false)
})

test('OSS object links and custom ports are rejected instead of copying an object as bucket or base path', () => {
	for (const value of [
		'https://course-files.oss-cn-hangzhou.aliyuncs.com/a.pdf',
		'https://oss-cn-hangzhou.aliyuncs.com/bucket',
		'https://course-files.oss-cn-hangzhou.aliyuncs.com:9000/',
	]) {
		const result = detect(value)
		assert.deepEqual(result.candidates, [])
		assert.match(result.notice, /OSS.*接口地址/)
	}
})

test('CNAME, spoofed suffix, illegal bucket and nonclassic domains are not confidently called OSS', () => {
	for (const value of [
		'https://files.example.test',
		'https://oss-cn-hangzhou.aliyuncs.com.evil.test',
		'https://eviloss-cn-hangzhou.aliyuncs.com',
		'https://ab.oss-cn-hangzhou.aliyuncs.com',
		'https://-abc.oss-cn-hangzhou.aliyuncs.com',
		'https://abc-.oss-cn-hangzhou.aliyuncs.com',
		'https://abc_def.oss-cn-hangzhou.aliyuncs.com',
		'https://a' + 'b'.repeat(63) + '.oss-cn-hangzhou.aliyuncs.com',
		'https://oss-accelerate.aliyuncs.com',
		'https://course-files.oss-cn-hangzhou.dualstack.aliyuncs.com',
	]) {
		const result = detect(value)
		assert.equal(
			result.candidates.some((item) => item.type === 'aliyun'),
			false
		)
		assert.ok(result.candidates.every((item) => item.confidence === 'possible'))
	}
})

test('server support allowlist is enforced and cannot turn a possible match into a certain one', () => {
	assert.deepEqual(detect('/data/files', ['s3']).candidates, [])
	assert.match(detect('/data/files', ['s3']).notice, /服务器暂不支持/)
	assert.deepEqual(
		detect('https://host.example.test', [{ key: 's3' }]).candidates.map(
			(item) => item.type
		),
		['s3']
	)
	assert.equal(
		detect('https://host.example.test', [{ key: 's3' }]).candidates[0]
			.confidence,
		'possible'
	)
	assert.deepEqual(detect('ftp://host.example.test/', ['sftp']).candidates, [])
	for (const allowed of [[], null, undefined, {}, ['unsupported']])
		assert.deepEqual(
			detectStorageSource('https://host.example.test', allowed).candidates,
			[]
		)
})

test('userinfo, encoded credentials, signed links and fragments reject without reflecting secrets', () => {
	const secret = 'synthetic-sensitive-value'
	for (const address of [
		'https://user:' + secret + '@host.example.test',
		'https://user%3A' + secret + '%40host.example.test',
		'sftp://user:' + secret + '@host.example.test/',
		'ftp://user%3A' + secret + '%40host.example.test/',
		'webdavs://user:' + secret + '@host.example.test/dav',
		'https://host.example.test?signature=' + secret,
		'https://course-files.oss-cn-hangzhou.aliyuncs.com/?OSSAccessKeyId=' +
			secret,
		'https://host.example.test/#' + secret,
		'https://host.example.test/%00' + secret,
		'https://@host.example.test',
		'https://host.example.test?',
		'https://host.example.test#',
	]) {
		const result = detect(address)
		assert.deepEqual(result.candidates, [])
		assert.equal(JSON.stringify(result).includes(secret), false)
	}
})

test('raw, encoded and double-encoded traversal or controls are rejected before URL normalization', () => {
	for (const address of [
		'/data/../private',
		'/data/%2e%2e/private',
		'/data/%252e%252e/private',
		'D:\\data\\..\\private',
		'\\\\server\\share\\..\\private',
		'https://host.example.test/a/../private',
		'https://host.example.test/a/%2e%2e/private',
		'https://host.example.test/a/%252e%252e/private',
		'https://host.example.test/a%2f..%2fprivate',
		'sftp://host.example.test/%00',
		'https://host.example.test\n/private',
		'https://host.example.test/%250a',
		'webdavs://host.example.test/../private',
		'https://host.example.test\\private',
		'\\\\?\\C:\\data',
	])
		assert.deepEqual(
			detect(address).candidates,
			[],
			'invalid input should not yield suggestions'
		)
})

test('malformed inputs and unsupported protocols return stable notices without exceptions', () => {
	for (const value of [
		null,
		undefined,
		42,
		{},
		[],
		'',
		'  ',
		'relative/path',
		'D:relative',
		'~',
		'file:///data/files',
		'javascript:alert(1)',
		'https://host.example.test:70000',
		'sftp://host.example.test:0/',
		'ftp://host.example.test:65536/',
		'https://host.example.test/%oops',
		'x'.repeat(2049),
	]) {
		const result = detect(value)
		assert.deepEqual(result.candidates, [])
		assert.equal(typeof result.notice, 'string')
	}
})

test('recognition is immutable, returned candidates are independent, and no fetch or model is used', () => {
	const allowed = Object.freeze([
		Object.freeze({ key: 'aliyun' }),
		Object.freeze({ key: 's3' }),
	])
	const first = detect('https://oss-cn-hangzhou.aliyuncs.com', allowed)
	first.candidates[0].suggestions.endPoint = 'changed'
	first.candidates[0].title = 'changed'
	assert.equal(
		detect('https://oss-cn-hangzhou.aliyuncs.com', allowed).candidates[0]
			.suggestions.endPoint,
		'oss-cn-hangzhou.aliyuncs.com'
	)
	assert.equal(
		detect('https://oss-cn-hangzhou.aliyuncs.com', allowed).candidates[0].title,
		'阿里云 OSS'
	)
	assert.equal(allowed[0].key, 'aliyun')
})
