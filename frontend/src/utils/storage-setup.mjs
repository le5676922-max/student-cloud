const COMMON = Object.freeze([
	{
		key: 'local',
		title: '服务器本地目录',
		description: '使用部署服务器或容器内的文件夹',
	},
	{
		key: 'aliyun',
		title: '阿里云 OSS',
		description: '连接已有的 OSS 存储空间',
	},
	{
		key: 's3',
		title: 'S3 兼容存储',
		description: '连接支持 S3 协议的对象存储',
	},
	{ key: 'minio', title: 'MinIO', description: '连接自建 MinIO 文件服务' },
	{
		key: 'webdav',
		title: 'WebDAV',
		description: '使用服务方提供的 WebDAV 地址',
	},
	{ key: 'sftp', title: 'SFTP', description: '通过 SSH 连接服务器文件目录' },
])
const ESSENTIAL = new Set([
	'filePath',
	'basePath',
	'url',
	'host',
	'port',
	'username',
	'password',
	'accessKey',
	'secretKey',
	'secretId',
	'endPoint',
	'endPointScheme',
	'bucketName',
	'region',
	'accessToken',
	'refreshToken',
	'clientId',
	'clientSecret',
	'siteId',
	'listId',
	'privateKey',
	'passphrase',
])
// Reject non-printing input characters; matching them here is intentional.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/u
const OBJECT_TYPES = new Set([
	's3',
	'aliyun',
	'minio',
	'tencent',
	'huawei',
	'qiniu',
	'doge-cloud',
])

export function commonStorageTypes(supported = []) {
	const keys = new Set(
		(Array.isArray(supported) ? supported : []).map((item) =>
			typeof item === 'string' ? item : item?.key
		)
	)
	return COMMON.filter((item) => keys.has(item.key)).map((item) => ({
		...item,
	}))
}

export function storageSetupGuide(type) {
	const guides = {
		local: {
			description:
				'把服务器上的一个目录作为云盘。这里填写服务器路径，不是浏览器所在电脑的路径。',
			checklist: [
				'准备一个可读写的绝对路径',
				'Docker 部署时填写容器内已挂载的路径',
				'保存后检查连接状态，再配置成员权限',
			],
			nameSuggestion: '本地云盘',
		},
		aliyun: {
			description:
				'连接已创建的 OSS Bucket；地域、名称和密钥以阿里云控制台信息为准。',
			checklist: [
				'准备 Bucket 名称和对应地域 Endpoint',
				'使用仅能访问该 Bucket 的 RAM 访问密钥',
				'保持私有空间设置，再检查连接与上传权限',
			],
			nameSuggestion: '阿里云资料库',
		},
		s3: {
			description:
				'填写服务方提供的 S3 接口地址、地域和存储空间名称，不是管理后台网址。',
			checklist: [
				'核对 Endpoint、协议与地域',
				'准备 Bucket 名称和访问密钥',
				'域名风格以服务方要求为准，再检查连接',
			],
			nameSuggestion: '对象存储云盘',
		},
		minio: {
			description:
				'使用 MinIO 的文件接口地址（常见为 9000 端口），不是控制台地址。',
			checklist: [
				'填写包含 http:// 或 https:// 的文件接口地址',
				'核对已创建的 Bucket 和访问密钥',
				'检查部署服务器可以访问该地址',
			],
			nameSuggestion: 'MinIO 云盘',
		},
		webdav: {
			description:
				'填写服务方提供的完整 WebDAV 地址；账号密码分别填写，不要放进地址。',
			checklist: [
				'确认完整 WebDAV 地址和服务目录',
				'需要认证时分别填写用户名与密码',
				'保持中转设置，检查连接后再调整下载方式',
			],
			nameSuggestion: 'WebDAV 云盘',
		},
		sftp: {
			description: '通过 SSH 文件服务访问远程目录。主机、端口和目录分别填写。',
			checklist: [
				'准备远程主机与 SSH 端口',
				'填写用户名以及密码或私钥，不在地址中放密钥',
				'确认远程目录及账号读写权限',
			],
			nameSuggestion: '服务器资料库',
		},
	}
	const guide = guides[type] || {
		description:
			'按服务方提供的连接参数填写。系统不会猜测账号、目录或存储空间。',
		checklist: [
			'核对连接地址和必填参数',
			'凭据仅填写到对应输入框',
			'保存后检查连接与成员权限',
		],
		nameSuggestion: '资料云盘',
	}
	return { ...guide, checklist: [...guide.checklist] }
}

export function isEssentialStorageField(field) {
	const hasDefault =
		field?.defaultValue !== null &&
		field?.defaultValue !== undefined &&
		field?.defaultValue !== ''
	return Boolean(ESSENTIAL.has(field?.key) || (field?.required && !hasDefault))
}

export function isSecretStorageField(key) {
	return (
		typeof key === 'string' &&
		/password|secret|token|privatekey|passphrase|accesskey|apikey/i.test(
			key.replace(/[-_.]/g, '')
		)
	)
}

export function storageFieldHint(key, type) {
	const common = {
		filePath: {
			description:
				'填写部署服务器或容器内的绝对目录；Windows 路径和包含空格的目录也可使用。',
			placeholder: '/data/files 或 D:\\Cloud Files',
		},
		basePath: {
			description:
				'只显示这个存储目录下的文件；使用以 / 开头的目录，不填写 ..。',
			placeholder: '/',
		},
		host: {
			description: '仅填写远程主机名或 IP，不包含协议、端口、账号或目录。',
			placeholder: 'files.example.com',
		},
		port: {
			description: '端口必须为 1–65535 的整数，以服务方配置为准。',
			placeholder: type === 'ftp' ? '21' : '22',
		},
		url: {
			description:
				'使用完整 WebDAV 地址，账号密码另填；不使用带签名参数的分享链接。',
			placeholder: 'https://files.example.com/dav/',
		},
		endPoint:
			type === 'minio'
				? {
						description:
							'填写 MinIO 文件接口的完整地址，不是管理控制台；必须包含协议。',
						placeholder: 'http://192.168.1.20:9000',
				  }
				: {
						description:
							'填写服务方的接口主机名，可带端口；地域和 Bucket 不会自动推测。',
						placeholder:
							type === 'aliyun'
								? 'oss-cn-hangzhou.aliyuncs.com'
								: 's3.example.com',
				  },
		endPointScheme: {
			description: '与服务方接口协议一致；可用时优先 https。',
			placeholder: 'https',
		},
		bucketName: {
			description: '填已有存储空间的准确名称，不填目录、域名或完整地址。',
			placeholder: 'my-bucket',
		},
		region: {
			description: '填写服务方明确提供的地域；不要依据地址自动猜测。',
			placeholder: type === 'minio' ? 'minio' : '服务方提供的地域',
		},
		accessKey: {
			description: '服务方提供的访问标识，建议使用权限最小化的独立账号。',
			placeholder: '访问密钥 ID',
		},
		secretKey: {
			description: '填访问密钥对应的 Secret，不在备注或地址中填写。',
			placeholder: '访问密钥 Secret',
		},
		username: {
			description: '需要认证时填写服务方分配的用户名。',
			placeholder: '用户名',
		},
		password: {
			description: '填写服务密码或授权密码，不放进连接地址。',
			placeholder: '密码或授权密码',
		},
	}
	return common[key] ? { ...common[key] } : {}
}

function hasTraversal(value) {
	if (typeof value !== 'string') return true
	let decoded = value
	try {
		decoded = decodeURIComponent(value)
	} catch {
		return true
	}
	return (
		CONTROL.test(decoded) ||
		/%[a-f\d]{2}/iu.test(decoded) ||
		decoded.split(/[\\/]/u).some((part) => part === '..')
	)
}

function absoluteLocalPath(value) {
	if (
		typeof value !== 'string' ||
		!value ||
		CONTROL.test(value) ||
		hasTraversal(value)
	)
		return false
	// Drive paths and ordinary UNC shares are valid; device paths are not filesystem folders.
	if (/^\\\\[?.][\\/]/u.test(value)) return false
	return (
		(/^\/(?!\/)/u.test(value) && !value.includes('\\')) ||
		/^[a-z]:[\\/]/iu.test(value) ||
		/^\\\\[^\\/]+[\\/][^\\/]+(?:[\\/]|$)/u.test(value)
	)
}

function cleanUrl(value, { protocolRequired = true } = {}) {
	if (
		typeof value !== 'string' ||
		!value ||
		CONTROL.test(value) ||
		/[\s\\?#]/u.test(value) ||
		/%(?:2f|5c)/iu.test(value) ||
		hasTraversal(value)
	)
		return null
	if (protocolRequired && !/^https?:\/\//iu.test(value)) return null
	const authority = value.replace(/^https?:\/\//iu, '').split('/')[0]
	if (/[@%]/u.test(authority)) return null
	try {
		const url = new URL(
			/^https?:\/\//iu.test(value) ? value : 'https://' + value
		)
		if (
			!['http:', 'https:'].includes(url.protocol) ||
			!url.hostname ||
			url.username ||
			url.password ||
			url.search ||
			url.hash
		)
			return null
		return url
	} catch {
		return null
	}
}

function validPort(value) {
	if (
		typeof value !== 'number' &&
		(typeof value !== 'string' || !/^\d+$/u.test(value))
	)
		return false
	const number = Number(value)
	return Number.isInteger(number) && number >= 1 && number <= 65535
}

function validHost(value) {
	if (
		typeof value !== 'string' ||
		!value ||
		CONTROL.test(value) ||
		/[\s/@?#\\]/u.test(value)
	)
		return false
	if (!value.includes(':')) return /^[\p{L}\p{N}._-]+$/u.test(value)
	try {
		const url = new URL(
			'http://' + (value.startsWith('[') ? value : '[' + value + ']')
		)
		return Boolean(url.hostname) && !url.port && url.pathname === '/'
	} catch {
		return false
	}
}

/** Checks only the server-provided visible schema; no connection, disk or model call. */
export function storageSetupIssues({
	type,
	params = {},
	fields = [],
	fieldShowMap,
} = {}) {
	const issues = []
	for (const field of Array.isArray(fields) ? fields : []) {
		if (
			!field?.key ||
			field.hidden ||
			field.ignoreInput ||
			(fieldShowMap && fieldShowMap[field.key] !== true)
		)
			continue
		const key = field.key,
			value = params?.[key]
		const empty = value == null || (typeof value === 'string' && !value.trim())
		if (empty) {
			if (field.required)
				issues.push({ key, message: '请填写' + (field.name || key) })
			continue
		}
		let message = ''
		if (key === 'port' && !validPort(value)) message = '端口应为 1–65535 的整数'
		else if (
			key === 'filePath' &&
			type === 'local' &&
			!absoluteLocalPath(value)
		)
			message = '文件路径需为服务器绝对目录，不能包含 .. 或控制字符'
		else if (
			key === 'basePath' &&
			(typeof value !== 'string' ||
				!value.startsWith('/') ||
				hasTraversal(value) ||
				value.includes('\\'))
		)
			message = '基路径需以 / 开头，不能包含 ..、反斜杠或控制字符'
		else if (key === 'url' && type === 'webdav' && !cleanUrl(value))
			message =
				'WebDAV 地址需为完整 http(s) 地址，不能包含账号密码、签名参数或片段'
		else if (['domain', 'proxyDomain'].includes(key) && !cleanUrl(value))
			message = '域名地址需包含 http(s) 协议，不包含账号密码、签名参数或片段'
		else if (key === 'endPoint' && OBJECT_TYPES.has(type)) {
			const url = cleanUrl(value, { protocolRequired: type === 'minio' })
			if (!url || url.pathname !== '/')
				message =
					type === 'minio'
						? 'MinIO 接口地址需包含 http(s) 协议，不能带目录、账号密码或签名参数'
						: 'Endpoint 需为接口主机名或 http(s) 地址，不包含目录、账号密码或签名参数'
		} else if (
			key === 'host' &&
			['sftp', 'ftp'].includes(type) &&
			!validHost(value)
		)
			message = '主机只填写域名或 IP；协议、端口和目录请分别填写'
		if (message) issues.push({ key, message })
	}
	return issues
}

/** Explicit address parsing creates suggestions only, without copying credentials or guessing provider metadata. */
export function parseStorageAddress(raw, type) {
	const rejected = (notice) => ({ suggestions: {}, notice })
	if (typeof raw !== 'string' || !raw.trim() || CONTROL.test(raw))
		return rejected('请输入不含账号密码的连接地址')
	const value = raw.trim()
	if (type === 'local')
		return absoluteLocalPath(value)
			? {
					suggestions: { filePath: value },
					notice: '已识别目录，请核对它是部署服务器或容器内的路径；尚未保存',
			  }
			: rejected('本地目录需为绝对路径，不能包含 .. 或控制字符')
	if (['ftp', 'sftp'].includes(type)) {
		const authority = value.replace(/^[a-z]+:\/\//iu, '').split('/')[0]
		if (
			!value.toLowerCase().startsWith(type + '://') ||
			hasTraversal(value) ||
			/[\s\\?#]/u.test(value) ||
			/%(?:2f|5c)/iu.test(value) ||
			/[@%]/u.test(authority)
		)
			return rejected('请填写与所选类型一致的完整连接地址，不含账号密码')
		try {
			const url = new URL(value)
			if (
				!url.hostname ||
				url.username ||
				url.password ||
				url.search ||
				url.hash ||
				/\s/u.test(value)
			)
				return rejected(
					'地址中不能包含账号密码、查询参数或片段，请分别填写认证信息'
				)
			const port = url.port ? Number(url.port) : type === 'sftp' ? 22 : 21
			if (!validPort(port))
				return rejected('连接地址的端口无效，请填写 1–65535 的整数')
			const basePath = decodeURIComponent(url.pathname || '/')
			return {
				suggestions: { host: url.hostname, port, basePath },
				notice: '已识别主机、端口和基路径，请核对后应用；尚未保存',
			}
		} catch {
			return rejected('无法识别连接地址，请核对协议、主机和端口')
		}
	}
	if (type === 'webdav') {
		const url = cleanUrl(value)
		return url
			? {
					suggestions: { url: url.href },
					notice: '已识别 WebDAV 地址，账号密码需分别填写；尚未保存',
			  }
			: rejected('请使用完整 http(s) WebDAV 地址，不含账号密码、签名参数或片段')
	}
	if (['s3', 'aliyun', 'minio'].includes(type)) {
		const url = cleanUrl(value, { protocolRequired: type === 'minio' })
		if (!url || url.pathname !== '/')
			return rejected(
				'请使用接口地址，不含目录、账号密码、查询参数或片段；MinIO 需要完整协议'
			)
		return {
			suggestions:
				type === 'minio'
					? { endPoint: url.origin }
					: {
							endPoint: url.host,
							...(/^https?:\/\//iu.test(value)
								? { endPointScheme: url.protocol.slice(0, -1) }
								: {}),
					  },
			notice: '已识别接口地址；Bucket 和地域仍需按服务方信息填写，尚未保存',
		}
	}
	return rejected('该类型请按字段逐项填写，不自动识别连接参数')
}
