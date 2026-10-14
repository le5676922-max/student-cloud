const BASE = '/api/uploads/resumable'
export const RESUMABLE_CHUNK_SIZE = 4 * 1024 * 1024
export const RESUMABLE_THRESHOLD = 2 * RESUMABLE_CHUNK_SIZE
const MAX_SIZE = 2 * 1024 * 1024 * 1024
const SESSION_ID =
	/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const STATES = new Set(['OPEN', 'COMPLETING', 'COMPLETED', 'EXPIRED'])
const PENDING_MESSAGE =
	'上传合并结果待核查，请检查任务状态和目标目录，不要重复提交。'

export function isResumableCandidate(file, uploadType) {
	return (
		uploadType === 'PROXY' &&
		Number.isSafeInteger(file?.size) &&
		file.size >= RESUMABLE_THRESHOLD
	)
}

function checkActive(signal, isActive) {
	if (signal?.aborted || (isActive && !isActive())) {
		const error = new Error('上传已暂停，已接收的分片会暂存 24 小时')
		error.name = 'AbortError'
		throw error
	}
}

function sessionView(value, size, expectedId) {
	const count = Math.ceil(size / RESUMABLE_CHUNK_SIZE)
	if (
		!value ||
		typeof value.id !== 'string' ||
		!SESSION_ID.test(value.id) ||
		(expectedId && value.id !== expectedId) ||
		!STATES.has(value.state) ||
		value.size !== size ||
		value.chunkSize !== RESUMABLE_CHUNK_SIZE ||
		!Number.isSafeInteger(value.expiresAt) ||
		value.expiresAt <= 0 ||
		!Array.isArray(value.receivedChunks) ||
		value.receivedChunks.length > count ||
		value.receivedChunks.some(
			(index) => !Number.isInteger(index) || index < 0 || index >= count
		) ||
		new Set(value.receivedChunks).size !== value.receivedChunks.length
	) {
		throw new Error('续传服务返回了无效的任务信息，请刷新后检查任务状态')
	}
	return { ...value, receivedChunks: [...value.receivedChunks] }
}

function pendingCompletion(session, cause) {
	const error = new Error(PENDING_MESSAGE)
	if (cause) error.cause = cause
	// This is a status hint, never permission to replay a publication request.
	return Object.assign(error, {
		name: 'ResumableCompletionPendingError',
		session: { ...session, state: 'COMPLETING' },
	})
}

async function sha256(buffer, cryptoApi) {
	return [...new Uint8Array(await cryptoApi.subtle.digest('SHA-256', buffer))]
		.map((value) => value.toString(16).padStart(2, '0'))
		.join('')
}

export async function hashUploadManifest(
	file,
	{ signal, isActive, onHashProgress, cryptoApi = globalThis.crypto } = {}
) {
	if (!cryptoApi?.subtle || typeof file?.slice !== 'function') {
		throw new Error(
			'断点续传需要 HTTPS 安全连接（本机 localhost 除外），请使用 HTTPS 后重试'
		)
	}
	if (
		!Number.isSafeInteger(file.size) ||
		file.size <= 0 ||
		file.size > MAX_SIZE
	)
		throw new Error('续传文件大小必须在 1 字节至 2 GiB 之间')
	const chunkHashes = []
	for (let start = 0; start < file.size; start += RESUMABLE_CHUNK_SIZE) {
		checkActive(signal, isActive)
		const end = Math.min(file.size, start + RESUMABLE_CHUNK_SIZE)
		const buffer = await file.slice(start, end).arrayBuffer()
		checkActive(signal, isActive)
		if (buffer.byteLength !== end - start)
			throw new Error('读取文件失败，请重新选择文件')
		chunkHashes.push(await sha256(buffer, cryptoApi))
		checkActive(signal, isActive)
		onHashProgress?.({ checked: end, total: file.size })
	}
	const manifestHash = await sha256(
		new TextEncoder().encode(chunkHashes.join('\n')),
		cryptoApi
	)
	checkActive(signal, isActive)
	return { chunkHashes, manifestHash }
}

export async function readResumableStatus({
	id,
	size,
	request,
	signal,
	isActive,
}) {
	if (typeof id !== 'string' || !SESSION_ID.test(id))
		throw new Error('无效的续传任务')
	checkActive(signal, isActive)
	const response = await request({
		method: 'GET',
		url: `${BASE}/${id}`,
		signal,
		timeout: 30000,
	})
	checkActive(signal, isActive)
	return sessionView(response.data, size, id)
}

/** No retry loop: reselecting the same file explicitly calls idempotent init and skips confirmed chunks. */
export async function tryResumableUpload({
	file,
	uploadType,
	storageKey,
	path,
	request,
	signal,
	isActive,
	onState,
	onProgress,
	onHashProgress,
	cryptoApi,
}) {
	if (!isResumableCandidate(file, uploadType)) return { handled: false }
	const send = async (options) => {
		checkActive(signal, isActive)
		const response = await request({
			...options,
			signal,
			timeout: options.timeout ?? 30000,
		})
		checkActive(signal, isActive)
		return response.data
	}
	const target = { storageKey, path, name: file.name, size: file.size }
	const capabilities = await send({
		method: 'POST',
		url: `${BASE}/capabilities`,
		data: target,
	})
	if (capabilities?.enabled === false) return { handled: false }
	if (
		capabilities?.enabled !== true ||
		capabilities.chunkSize !== RESUMABLE_CHUNK_SIZE ||
		!Number.isSafeInteger(capabilities.maxFileSize) ||
		capabilities.maxFileSize <= 0 ||
		capabilities.maxFileSize > MAX_SIZE ||
		file.size > capabilities.maxFileSize ||
		capabilities.expiresInSeconds !== 86400
	)
		throw new Error('续传服务配置无效，请联系管理员')
	onState?.({
		state: 'HASHING',
		message: '正在校验文件内容，校验结束后开始上传…',
	})
	const manifest = await hashUploadManifest(file, {
		signal,
		isActive,
		onHashProgress,
		cryptoApi,
	})
	let session = sessionView(
		await send({
			method: 'POST',
			url: `${BASE}/init`,
			data: { ...target, ...manifest },
		}),
		file.size
	)
	onState?.(session)
	if (session.state === 'COMPLETED') return { handled: true, session }
	if (session.state === 'COMPLETING') throw pendingCompletion(session)
	if (session.state !== 'OPEN')
		throw new Error('续传任务已过期，请重新选择文件创建新任务')
	const received = new Set(session.receivedChunks)
	const chunkBytes = (index) =>
		Math.min(RESUMABLE_CHUNK_SIZE, file.size - index * RESUMABLE_CHUNK_SIZE)
	let confirmedBytes = [...received].reduce(
		(sum, index) => sum + chunkBytes(index),
		0
	)
	onProgress?.({
		loaded: confirmedBytes,
		total: file.size,
		resumed: confirmedBytes > 0,
	})
	for (let index = 0; index < manifest.chunkHashes.length; index++) {
		if (received.has(index)) continue
		checkActive(signal, isActive)
		const start = index * RESUMABLE_CHUNK_SIZE
		const next = sessionView(
			await send({
				method: 'PUT',
				url: `${BASE}/${session.id}/chunks/${index}`,
				data: file.slice(start, start + chunkBytes(index)),
				timeout: 120000,
				headers: {
					'Content-Type': 'application/octet-stream',
					'X-Upload-Chunk-SHA256': manifest.chunkHashes[index],
				},
				onUploadProgress: (event) => {
					if (!signal?.aborted && (!isActive || isActive()))
						onProgress?.({
							loaded:
								confirmedBytes +
								Math.min(
									chunkBytes(index),
									Math.max(0, Number(event.loaded) || 0)
								),
							total: file.size,
						})
				},
			}),
			file.size,
			session.id
		)
		if (
			next.state !== 'OPEN' ||
			!next.receivedChunks.includes(index) ||
			[...received].some((part) => !next.receivedChunks.includes(part))
		)
			throw new Error('分片接收状态不一致，请重试以检查已保存的分片')
		session = next
		for (const part of next.receivedChunks) received.add(part)
		confirmedBytes = [...received].reduce(
			(sum, part) => sum + chunkBytes(part),
			0
		)
		onState?.(session)
		onProgress?.({ loaded: confirmedBytes, total: file.size })
	}
	checkActive(signal, isActive)
	onState?.({
		...session,
		state: 'COMPLETING',
		message: '分片已上传，正在校验并保存；请勿重复提交…',
	})
	try {
		const completed = sessionView(
			await send({
				method: 'POST',
				url: `${BASE}/${session.id}/complete`,
				timeout: 120000,
			}),
			file.size,
			session.id
		)
		if (completed.state !== 'COMPLETED') throw pendingCompletion(completed)
		onState?.(completed)
		return { handled: true, session: completed }
	} catch (error) {
		if (error.name === 'AbortError') throw error
		throw pendingCompletion(session, error)
	}
}
