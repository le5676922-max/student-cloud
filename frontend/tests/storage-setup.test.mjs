import assert from 'node:assert/strict'
import test from 'node:test'
import {
	commonStorageTypes,
	isEssentialStorageField,
	isSecretStorageField,
	parseStorageAddress,
	storageFieldHint,
	storageSetupGuide,
	storageSetupIssues,
} from '../src/utils/storage-setup.mjs'
import { detectStorageSource } from '../src/utils/storage-source-recognizer.mjs'

const field = (key, required = true, extra = {}) => ({
	key,
	name: key,
	required,
	...extra,
})
const issues = (
	type,
	params,
	fields = Object.keys(params).map((key) => field(key))
) => storageSetupIssues({ type, params, fields })

test('common storage cards use the server allowlist, fixed ordering and independent returned objects', () => {
	assert.deepEqual(
		commonStorageTypes([
			{ key: 'webdav' },
			{ key: 'local' },
			{ key: 'unsupported' },
			{ key: 's3' },
		]).map((item) => item.key),
		['local', 's3', 'webdav']
	)
	assert.deepEqual(
		commonStorageTypes(['sftp', 'minio', 'local', 'local']).map(
			(item) => item.key
		),
		['local', 'minio', 'sftp']
	)
	assert.deepEqual(commonStorageTypes(null), [])
	const first = commonStorageTypes(['local'])
	first[0].title = 'changed'
	assert.notEqual(commonStorageTypes(['local'])[0].title, 'changed')
})

test('guidance distinguishes container paths, MinIO API addresses and manually specified object-storage region', () => {
	assert.match(storageSetupGuide('local').description, /服务器/)
	assert.ok(
		storageSetupGuide('local').checklist.some((item) => /容器/.test(item))
	)
	assert.match(storageSetupGuide('minio').description, /不是控制台/)
	assert.ok(storageSetupGuide('s3').checklist.some((item) => /地域/.test(item)))
	const guide = storageSetupGuide('local')
	guide.checklist.length = 0
	assert.ok(storageSetupGuide('local').checklist.length)
	assert.equal(typeof storageSetupGuide('unknown').nameSuggestion, 'string')
})

test('required schema fields and essential connection inputs remain visible while optional tuning may fold', () => {
	assert.equal(isEssentialStorageField(field('customMandatory')), true)
	for (const key of [
		'filePath',
		'basePath',
		'host',
		'port',
		'endPoint',
		'bucketName',
		'region',
		'username',
		'password',
		'privateKey',
		'passphrase',
	])
		assert.equal(isEssentialStorageField(field(key, false)), true)
	assert.equal(isEssentialStorageField(field('maxConnections', false)), false)
	assert.equal(isEssentialStorageField(null), false)
})

test('mandatory technical options with complete typed defaults may fold while connection fields stay essential', () => {
	for (const defaultValue of [
		false,
		true,
		'false',
		'true',
		'[]',
		[],
		0,
		1800,
		'1800',
	]) {
		assert.equal(
			isEssentialStorageField(field('technicalOption', true, { defaultValue })),
			false
		)
		assert.equal(
			isEssentialStorageField(field('host', true, { defaultValue })),
			true
		)
	}
	for (const defaultValue of [null, undefined, ''])
		assert.equal(
			isEssentialStorageField(field('technicalOption', true, { defaultValue })),
			true
		)
	assert.equal(
		isEssentialStorageField(
			field('corsConfigList', true, { defaultValue: '[]' })
		),
		false
	)
})

test('required JSON-list defaults and false/zero values are not mistaken for empty fields, but a cleared input is empty', () => {
	for (const value of [[], '[]', false, 'false', 0]) {
		assert.deepEqual(
			issues('s3', { corsConfigList: value }, [
				field('corsConfigList', true, { defaultValue: '[]' }),
			]),
			[]
		)
	}
	assert.equal(
		issues('s3', { corsConfigList: '' }, [
			field('corsConfigList', true, { defaultValue: '[]' }),
		]).length,
		1
	)
	assert.equal(
		issues('s3', { corsConfigList: '  ' }, [
			field('corsConfigList', true, { defaultValue: '[]' }),
		]).length,
		1
	)
})

test('secrets are consistently masked without mistaking source keys or public identifiers for credentials', () => {
	for (const key of [
		'password',
		'secretKey',
		'clientSecret',
		'accessToken',
		'refreshToken',
		'privateKey',
		'passphrase',
		'access_key',
		'api-key',
	])
		assert.equal(isSecretStorageField(key), true)
	for (const key of [
		'key',
		'username',
		'clientId',
		'publicKey',
		'bucketName',
		'region',
	])
		assert.equal(isSecretStorageField(key), false)
	assert.match(storageFieldHint('filePath', 'local').description, /容器/)
	assert.match(storageFieldHint('endPoint', 'minio').placeholder, /^http:\/\//)
	assert.deepEqual(storageFieldHint('unknown', 'local'), {})
})

test('only current visible server-schema fields produce required issues; boolean false is a valid switch', () => {
	const fields = [
		field('filePath'),
		field('password', true, { hidden: true }),
		field('hiddenByCondition'),
		field('enable'),
		field('ignored', true, { ignoreInput: true }),
	]
	assert.deepEqual(
		storageSetupIssues({
			type: 'local',
			params: { enable: false },
			fields,
			fieldShowMap: {
				filePath: true,
				password: true,
				hiddenByCondition: false,
				enable: true,
				ignored: true,
			},
		}),
		[{ key: 'filePath', message: '请填写filePath' }]
	)
	assert.deepEqual(
		storageSetupIssues({ type: 'local', params: {}, fields, fieldShowMap: {} }),
		[]
	)
	assert.deepEqual(
		issues('webdav', { password: '' }, [field('password', false)]),
		[]
	)
})

test('local absolute Unix, Windows and UNC directories accept ordinary spaces without filesystem probes', () => {
	for (const path of [
		'/data/files',
		'/srv/学习资料',
		'/data/My Files',
		'D:\\Cloud Files\\Courses',
		'D:/Cloud Files',
		'\\\\server\\Share Files\\Course',
	])
		assert.deepEqual(issues('local', { filePath: path }), [], path)
	for (const path of [
		'data/files',
		'D:relative',
		'~',
		'/data/../private',
		'D:\\data\\..\\private',
		'/data\n/files',
		'\\\\?\\C:\\data',
		'/data/%2e%2e/private',
	])
		assert.equal(issues('local', { filePath: path }).length, 1, path)
})

test('base paths reject traversal and controls but retain real folder names and spaces', () => {
	for (const basePath of ['/', '/My Files', '/.cache', '/course..notes'])
		assert.deepEqual(issues('sftp', { basePath }), [])
	for (const basePath of [
		'relative',
		'/../private',
		'/a/%2e%2e/b',
		'/a\\b',
		'/a\u0000b',
	])
		assert.equal(issues('sftp', { basePath }).length, 1)
})

test('independent object prefixes accept the server representation without allowing reserved or ambiguous segments', () => {
	const check = (basePath) =>
		storageSetupIssues({
			type: 's3',
			params: { basePath },
			fields: [field('basePath', false)],
			objectPrefix: true,
		})
	for (const prefix of [
		'',
		'/',
		'private/courses/',
		'/private/courses',
		'学习资料/',
	])
		assert.deepEqual(check(prefix), [], prefix)
	for (const prefix of [
		'../private',
		'private/../secret',
		'private/./notes',
		'private//notes',
		'private\\notes',
		'private/.studentcloud-probes/',
		'.STUDENTCLOUD-cache/',
		'private\nnotes',
	])
		assert.equal(check(prefix).length, 1, prefix)
})

test('port validation requires an integer from 1 to 65535, and host does not absorb the port or protocol', () => {
	for (const port of [1, 22, 65535, '22', '65535'])
		assert.deepEqual(issues('sftp', { port }), [])
	for (const port of [0, -1, 65536, 2.5, '22.0', '1e2', 'abc', true, ''])
		assert.equal(issues('sftp', { port }).length, 1)
	for (const host of ['files.example.com', '127.0.0.1', '::1', '[2001:db8::1]'])
		assert.deepEqual(issues('sftp', { host }), [], host)
	for (const host of [
		'files.example.com:22',
		'sftp://host',
		'host/path',
		'host?key=secret',
		'host\n',
	])
		assert.equal(issues('sftp', { host }).length, 1, host)
})

test('WebDAV requires a clean complete URL, MinIO a full API endpoint, S3 and Aliyun allow host plus protocol field', () => {
	assert.deepEqual(
		issues('webdav', { url: 'https://files.example.com/dav/My%20Files/' }),
		[]
	)
	assert.deepEqual(issues('minio', { endPoint: 'http://127.0.0.1:9000' }), [])
	assert.deepEqual(issues('s3', { endPoint: 's3.example.com:9000' }), [])
	assert.deepEqual(
		issues('aliyun', { endPoint: 'https://oss-cn-hangzhou.aliyuncs.com' }),
		[]
	)
	for (const endPoint of [
		'minio.example.com:9000',
		'https://host/admin',
		'https://user:secret@host',
		'https://host?token=secret',
	])
		assert.equal(issues('minio', { endPoint }).length, 1)
	for (const url of [
		'files.example.com/dav',
		'ftp://host/dav',
		'https://user:secret@host/dav',
		'https://host/dav?token=secret',
		'https://host/#private',
		'https://host/a/../private',
	])
		assert.equal(issues('webdav', { url }).length, 1)
})

test('address parsing fills actual FTP/SFTP host, port and basePath fields, not credentials or filePath', () => {
	assert.deepEqual(
		parseStorageAddress('sftp://files.example.com:2222/My%20Files', 'sftp')
			.suggestions,
		{ host: 'files.example.com', port: 2222, basePath: '/My Files' }
	)
	assert.deepEqual(
		parseStorageAddress('ftp://files.example.com/course', 'ftp').suggestions,
		{ host: 'files.example.com', port: 21, basePath: '/course' }
	)
	assert.deepEqual(parseStorageAddress('sftp://[::1]/', 'sftp').suggestions, {
		host: '[::1]',
		port: 22,
		basePath: '/',
	})
	assert.deepEqual(parseStorageAddress('ftp://host/', 'sftp').suggestions, {})
	assert.deepEqual(
		parseStorageAddress('sftp://host:70000/', 'sftp').suggestions,
		{}
	)
	assert.deepEqual(
		parseStorageAddress('sftp://host/a/../b', 'sftp').suggestions,
		{}
	)
})

test('endpoint suggestions preserve backend formats and never infer bucket, region or provider from hostname', () => {
	assert.deepEqual(
		parseStorageAddress('https://oss-cn-hangzhou.aliyuncs.com', 'aliyun')
			.suggestions,
		{ endPoint: 'oss-cn-hangzhou.aliyuncs.com', endPointScheme: 'https' }
	)
	assert.deepEqual(
		parseStorageAddress('s3.example.com:9000', 's3').suggestions,
		{ endPoint: 's3.example.com:9000' }
	)
	assert.deepEqual(
		parseStorageAddress('http://127.0.0.1:9000/', 'minio').suggestions,
		{ endPoint: 'http://127.0.0.1:9000' }
	)
	assert.deepEqual(
		parseStorageAddress('127.0.0.1:9000', 'minio').suggestions,
		{}
	)
	assert.deepEqual(
		parseStorageAddress('https://host/my-bucket', 's3').suggestions,
		{}
	)
	assert.deepEqual(parseStorageAddress('/data/My Files', 'local').suggestions, {
		filePath: '/data/My Files',
	})
	assert.deepEqual(
		parseStorageAddress('https://files.example.com/dav/', 'webdav').suggestions,
		{ url: 'https://files.example.com/dav/' }
	)
})

test('secrets and malformed addresses are rejected without reflecting their values in notices or suggestions', () => {
	const secret = 'secret-test-never-reflected'
	for (const [type, address] of [
		['webdav', 'https://user:' + secret + '@host/dav'],
		['webdav', 'https://host/dav?token=' + secret],
		['s3', 'https://host/#' + secret],
		['sftp', 'sftp://user:' + secret + '@host/'],
		['ftp', 'ftp://host/?password=' + secret],
		['minio', 'http://host:9000/?signature=' + secret],
		['webdav', 'javascript:' + secret],
		['sftp', 'sftp://host/%2e%2e/secret'],
	]) {
		const result = parseStorageAddress(address, type)
		assert.deepEqual(result.suggestions, {})
		assert.equal(JSON.stringify(result).includes(secret), false)
	}
	assert.deepEqual(parseStorageAddress(null, 'webdav').suggestions, {})
	assert.deepEqual(
		parseStorageAddress('https://host', 'unknown').suggestions,
		{}
	)
})

test('helpers do not mutate submitted params, fields, visibility or service-provided support list', () => {
	const params = Object.freeze({ filePath: '/data/My Files' })
	const fields = Object.freeze([Object.freeze(field('filePath'))])
	const fieldShowMap = Object.freeze({ filePath: true })
	assert.deepEqual(
		storageSetupIssues({ type: 'local', params, fields, fieldShowMap }),
		[]
	)
	const supported = Object.freeze([Object.freeze({ key: 'local' })])
	assert.equal(commonStorageTypes(supported).length, 1)
})

test('domain and proxyDomain validate only nonempty visible URL fields and accept configured path prefixes', () => {
	for (const key of ['domain', 'proxyDomain']) {
		for (const value of [
			'https://downloads.example.test',
			'http://127.0.0.1:8080/files',
			'https://downloads.example.test/My%20Files/',
		])
			assert.deepEqual(
				issues('s3', { [key]: value }, [field(key, false)]),
				[],
				`${key}: ${value}`
			)
		for (const value of ['', null, undefined])
			assert.deepEqual(issues('s3', { [key]: value }, [field(key, false)]), [])
		assert.deepEqual(
			storageSetupIssues({
				type: 's3',
				params: { [key]: 'not-an-http-url' },
				fields: [field(key, false)],
				fieldShowMap: { [key]: false },
			}),
			[]
		)
	}
})

test('download and proxy URL prechecks reject credentials, signatures, fragments and traversal without reflecting secrets', () => {
	const secret = 'synthetic-value-not-to-reflect'
	for (const key of ['domain', 'proxyDomain']) {
		for (const value of [
			'files.example.test',
			'ftp://files.example.test',
			'javascript:' + secret,
			'https://user:' + secret + '@files.example.test',
			'https://files.example.test?signature=' + secret,
			'https://files.example.test/#' + secret,
			'https://files.example.test/a/../private',
			'https://files.example.test/a/%2e%2e/private',
			'https://files.example.test/a/%00/private',
			'https://files.example.test\n/private',
		]) {
			const result = issues('s3', { [key]: value }, [field(key, false)])
			assert.equal(result.length, 1, `${key} should reject malformed URL`)
			assert.equal(result[0].key, key)
			assert.equal(JSON.stringify(result).includes(secret), false)
		}
	}
})

test('the backend doge-cloud provider key receives object endpoint checks rather than silently bypassing validation', () => {
	for (const endPoint of [
		'api.example.test',
		'api.example.test:9000',
		'https://api.example.test',
		'http://api.example.test:9000/',
	])
		assert.deepEqual(issues('doge-cloud', { endPoint }), [], endPoint)
	for (const endPoint of [
		'https://api.example.test/bucket',
		'https://user:synthetic-secret@api.example.test',
		'https://api.example.test?token=synthetic-secret',
		'https://api.example.test/#private',
		'https://api.example.test/a/../private',
		'https://api.example.test\n',
	])
		assert.equal(
			issues('doge-cloud', { endPoint }).length,
			1,
			'invalid doge-cloud endpoint'
		)
	assert.deepEqual(
		parseStorageAddress('https://api.example.test', 'doge-cloud').suggestions,
		{}
	)
})

test('selected WebDAV parsing, automatic detection and URL prechecks consistently reject ambiguous escaping and empty userinfo', () => {
	for (const url of [
		'https://@files.example.test/dav/',
		'https://%66iles.example.test/dav/',
		'https://files.example.test/dav?',
		'https://files.example.test/dav#',
		'https://files.example.test/a\\b',
		'https://files.example.test/a%2fb',
		'https://files.example.test/a%5cb',
		'https://files.example.test/%2e%2e/private',
		'https://files.example.test/%252e%252e/private',
		'https://files.example.test/%00/private',
		'https://files.example.test/%250a/private',
	]) {
		assert.deepEqual(parseStorageAddress(url, 'webdav').suggestions, {}, url)
		assert.deepEqual(detectStorageSource(url, ['webdav']).candidates, [], url)
		assert.equal(issues('webdav', { url }).length, 1, url)
	}
	const valid = 'https://files.example.test/dav/My%20Files/'
	assert.deepEqual(parseStorageAddress(valid, 'webdav').suggestions, {
		url: valid,
	})
	assert.equal(
		detectStorageSource(valid, ['webdav']).candidates[0].type,
		'webdav'
	)
	assert.deepEqual(issues('webdav', { url: valid }), [])
})

test('FTP and SFTP selected parsing matches automatic detection for empty userinfo, encoded separators, traversal and controls', () => {
	for (const type of ['ftp', 'sftp']) {
		for (const suffix of [
			'@files.example.test/course',
			'%66iles.example.test/course',
			'files.example.test/course?',
			'files.example.test/course#',
			'files.example.test/a\\b',
			'files.example.test/a%2fb',
			'files.example.test/a%5cb',
			'files.example.test/%2e%2e/private',
			'files.example.test/%252e%252e/private',
			'files.example.test/%00/private',
			'files.example.test/%250a/private',
		]) {
			const value = `${type}://${suffix}`
			assert.deepEqual(parseStorageAddress(value, type).suggestions, {}, value)
			assert.deepEqual(detectStorageSource(value, [type]).candidates, [], value)
		}
		const valid = `${type}://files.example.test:2222/My%20Files`
		const expected = {
			host: 'files.example.test',
			port: 2222,
			basePath: '/My Files',
		}
		assert.deepEqual(parseStorageAddress(valid, type).suggestions, expected)
		assert.deepEqual(
			detectStorageSource(valid, [type]).candidates[0].suggestions,
			expected
		)
	}
})

test('optional download and proxy domains apply the same strict escaping checks without breaking normal prefix URLs', () => {
	for (const key of ['domain', 'proxyDomain']) {
		for (const value of [
			'https://@files.example.test/downloads',
			'https://%66iles.example.test/downloads',
			'https://files.example.test/downloads?',
			'https://files.example.test/downloads#',
			'https://files.example.test/a\\b',
			'https://files.example.test/a%2fb',
			'https://files.example.test/a%5cb',
			'https://files.example.test/%252e%252e/private',
			'https://files.example.test/%2500/private',
		])
			assert.equal(
				issues('s3', { [key]: value }, [field(key, false)]).length,
				1,
				key
			)
		assert.deepEqual(
			issues('s3', { [key]: 'https://files.example.test/My%20Files/' }, [
				field(key, false),
			]),
			[]
		)
		assert.deepEqual(issues('s3', { [key]: '' }, [field(key, false)]), [])
	}
	assert.deepEqual(issues('local', { filePath: '/data/My Files' }), [])
	assert.deepEqual(
		issues('local', { filePath: 'D:\\Cloud Files\\Courses' }),
		[]
	)
})
