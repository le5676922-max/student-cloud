import { parseStorageAddress } from './storage-setup.mjs'

const TITLES = Object.freeze({
	local: '服务器本地目录',
	ftp: 'FTP',
	sftp: 'SFTP',
	webdav: 'WebDAV',
	aliyun: '阿里云 OSS',
	s3: 'S3 兼容存储',
	minio: 'MinIO',
})
// Input controls are rejected before URL parsing can silently normalize them.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/u
const INVALID =
	'地址无法安全识别；请填写不含账号密码、查询参数、片段或上级目录的连接地址'
const AMBIGUOUS =
	'无法仅凭地址确定类型；请选择实际使用的存储服务，再核对连接参数'
const UNSUPPORTED =
	'已识别地址格式，但服务器暂不支持对应存储类型；请改用其他类型或手动填写'
const CLASSIC_OSS =
	/^(?:([a-z0-9][a-z0-9-]{1,61}[a-z0-9])\.)?(oss-(?:cn|us|ap|eu|me|sa|af)-[a-z0-9]+(?:-[a-z0-9]+)*\.aliyuncs\.com)$/u

function rejected(notice = INVALID) {
	return { candidates: [], notice }
}

function safePath(value, { local = false } = {}) {
	if (CONTROL.test(value) || /[?#]/u.test(value)) return false
	try {
		const decoded = decodeURIComponent(value)
		// A second layer of escaping could conceal path separators or traversal.
		if (CONTROL.test(decoded) || /%[a-f\d]{2}/iu.test(decoded)) return false
		if (!local && (decoded.includes('\\') || /%2f/iu.test(value))) return false
		return !decoded
			.split(/[\\/]/u)
			.some((segment) => segment === '..' || segment === '.')
	} catch {
		return false
	}
}

function parseCleanAddress(value, explicitScheme) {
	if (/\s|[\\?#]/u.test(value)) return null
	const supplied = explicitScheme ? value : 'https://' + value
	const authority = supplied
		.replace(/^[a-z][a-z\d+.-]*:\/\//iu, '')
		.split('/')[0]
	// Reject even empty or encoded userinfo; do not let URL normalize it away.
	if (!authority || /[@%]/u.test(authority)) return null
	const pathStart = supplied.indexOf('/', supplied.indexOf('://') + 3)
	const rawPath = pathStart === -1 ? '/' : supplied.slice(pathStart)
	if (!safePath(rawPath)) return null
	try {
		const url = new URL(supplied)
		if (!url.hostname || url.username || url.password || url.search || url.hash)
			return null
		if (
			!url.hostname.startsWith('[') &&
			(url.hostname.length > 253 ||
				!url.hostname
					.replace(/\.$/u, '')
					.split('.')
					.every((label) =>
						/^[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?$/iu.test(label)
					))
		)
			return null
		return url
	} catch {
		return null
	}
}

/**
 * Offline, allowlisted address recognition only. This never contacts a service,
 * saves a source, copies credentials, or assumes that a reachable port is MinIO.
 */
export function detectStorageSource(raw, supported = []) {
	if (
		typeof raw !== 'string' ||
		!raw.trim() ||
		raw.length > 2048 ||
		CONTROL.test(raw)
	)
		return rejected()
	const value = raw.trim()
	const supportedKeys = new Set(
		(Array.isArray(supported) ? supported : []).map((item) =>
			typeof item === 'string' ? item : item?.key
		)
	)
	const result = (candidates, notice) => {
		const permitted = candidates.filter((candidate) =>
			supportedKeys.has(candidate.type)
		)
		return {
			candidates: permitted,
			notice: permitted.length ? notice : UNSUPPORTED,
		}
	}
	const candidate = (type, confidence, suggestions, reason) => ({
		type,
		title: TITLES[type],
		confidence,
		suggestions: { ...suggestions },
		reason,
	})
	if (/^\/(?!\/)|^[a-z]:[\\/]|^\\\\/iu.test(value)) {
		if (!safePath(value, { local: true })) return rejected()
		const parsed = parseStorageAddress(value, 'local')
		if (!parsed.suggestions.filePath) return rejected()
		return result(
			[
				candidate(
					'local',
					'certain',
					parsed.suggestions,
					'已识别绝对目录格式；请核对是部署服务器或容器内的目录，而不是浏览器电脑的目录'
				),
			],
			'已识别本地目录；仅生成建议，尚未验证目录或保存'
		)
	}
	const schemeMatch = value.match(/^([a-z][a-z\d+.-]*):\/\//iu)
	const scheme = schemeMatch?.[1]?.toLowerCase()
	if (
		scheme &&
		!['http', 'https', 'ftp', 'sftp', 'webdav', 'webdavs'].includes(scheme)
	)
		return rejected('暂不能识别这种连接协议；请选择存储类型并按字段填写')
	if (scheme === 'ftp' || scheme === 'sftp') {
		if (!parseCleanAddress(value, true)) return rejected()
		const parsed = parseStorageAddress(value, scheme)
		if (!parsed.suggestions.host) return rejected()
		return result(
			[
				candidate(
					scheme,
					'certain',
					parsed.suggestions,
					'根据显式连接协议识别主机、端口和基路径；账号密码需另外填写'
				),
			],
			'已识别连接协议；请核对参数后采用，尚未连接或保存'
		)
	}
	if (scheme === 'webdav' || scheme === 'webdavs') {
		const httpValue = value.replace(
			/^[a-z]+:/iu,
			scheme === 'webdavs' ? 'https:' : 'http:'
		)
		const url = parseCleanAddress(httpValue, true)
		if (!url) return rejected()
		return result(
			[
				candidate(
					'webdav',
					'certain',
					{ url: url.href },
					'根据显式 WebDAV 协议识别；已转换为对应的 HTTP(S) 地址，账号密码需另外填写'
				),
			],
			'已识别 WebDAV 地址；尚未验证连接或保存'
		)
	}
	// Bare input is a hostname, not a path or a protocol-less URL to normalize.
	if (!scheme && /[\\/]/u.test(value)) return rejected()
	const url = parseCleanAddress(value, Boolean(scheme))
	if (!url || !['http:', 'https:'].includes(url.protocol)) return rejected()
	const oss = url.hostname.match(CLASSIC_OSS)
	if (oss) {
		if (url.pathname !== '/' || url.port)
			return rejected(
				'OSS 需使用接口地址或 Bucket 根域名，不使用对象链接、目录或自定义端口'
			)
		const suggestions = {
			endPoint: oss[2],
			...(scheme ? { endPointScheme: url.protocol.slice(0, -1) } : {}),
			...(oss[1] ? { bucketName: oss[1] } : {}),
		}
		return result(
			[
				candidate(
					'aliyun',
					'certain',
					suggestions,
					'已识别阿里云经典 OSS 官方域名；Bucket 与 Endpoint 分开填写，不推测地域或访问密钥'
				),
			],
			'已识别 OSS 地址格式；请核对 Bucket 和认证信息，尚未验证连接或保存'
		)
	}
	const candidates = []
	if (url.pathname === '/') {
		if (scheme)
			candidates.push(
				candidate(
					'minio',
					'possible',
					{ endPoint: url.origin },
					'可能是 MinIO 文件接口，但域名和端口不足以确定；请确认不是控制台地址'
				)
			)
		candidates.push(
			candidate(
				's3',
				'possible',
				{
					endPoint: url.host,
					...(scheme ? { endPointScheme: url.protocol.slice(0, -1) } : {}),
				},
				'可能是 S3 兼容接口；服务类型、Bucket 和地域需以服务方信息为准'
			)
		)
	}
	candidates.push(
		candidate(
			'webdav',
			'possible',
			scheme ? { url: url.href } : {},
			scheme
				? 'HTTP(S) 地址也可能提供 WebDAV；目录名称不能证明服务协议，请核对完整 WebDAV 接口'
				: '也可能是 WebDAV 主机；需另行填写服务方提供的 HTTP(S) 协议和完整目录'
		)
	)
	return result(candidates, AMBIGUOUS)
}
