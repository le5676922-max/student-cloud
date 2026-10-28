// Only this application's protected file endpoint can receive the session header.
export function privateDownloadUrl(value, expectedKey) {
	if (
		typeof value !== 'string' ||
		value.length > 8192 ||
		!value.startsWith(`/api/drives/${expectedKey}/content?`) ||
		!/^\/api\/drives\/[a-z0-9][a-z0-9_-]{2,63}\/content\?/.test(value) ||
		/[\\\s#]/.test(value) ||
		/%(?![a-f0-9]{2})/i.test(value)
	)
		throw new Error('文件下载地址无效，请刷新目录后重试')
	const url = new URL(value, 'https://download.invalid')
	const entries = [...url.searchParams]
	const path = url.searchParams.get('path')
	if (
		entries.length !== 1 ||
		entries[0][0] !== 'path' ||
		!path?.startsWith('/') ||
		// eslint-disable-next-line no-control-regex -- Protected paths must reject control characters.
		/[\\\u0000-\u001f]/.test(path) ||
		path.split('/').some((part) => part === '.' || part === '..')
	)
		throw new Error('文件下载地址无效，请刷新目录后重试')
	return value
}

export const MAX_PRIVATE_DOWNLOAD_BYTES = 50 * 1024 * 1024

export function privateDownloadSize(value) {
	if (
		!Number.isSafeInteger(value) ||
		value < 0 ||
		value > MAX_PRIVATE_DOWNLOAD_BYTES
	)
		throw new Error('请刷新目录，并选择不超过50MiB的文件')
	return value
}

// Invoke the picker synchronously, before an asynchronous request can consume
// transient activation. A cancelled or refused save must never start another GET.
export async function pickPrivateDownloadFile(browser, suggestedName) {
	if (
		browser?.isSecureContext !== true ||
		browser.navigator?.userActivation?.isActive !== true ||
		typeof browser.showSaveFilePicker !== 'function'
	)
		return null
	try {
		return await browser.showSaveFilePicker({ suggestedName })
	} catch (error) {
		if (error?.name === 'SecurityError' || error?.name === 'NotSupportedError')
			return null
		throw error
	}
}

export function validatePrivateDownloadResponse(response, expectedSize) {
	privateDownloadSize(expectedSize)
	const header = (name) =>
		response.headers?.get?.(name) ?? response.headers?.[name]
	const contentType = header('content-type') ?? response.data?.type
	if (
		typeof contentType !== 'string' ||
		contentType.split(';', 1)[0].trim().toLowerCase() !==
			'application/octet-stream' ||
		(response.data?.type &&
			response.data.type.split(';', 1)[0].trim().toLowerCase() !==
				'application/octet-stream')
	)
		throw new Error('文件下载响应无效，请刷新目录后重试')
	const length = header('content-length')
	if (
		length != null &&
		(!/^(0|[1-9]\d*)$/.test(String(length)) ||
			privateDownloadSize(Number(length)) !== expectedSize)
	)
		throw new Error('文件大小已变化，请刷新目录后重试')
}

const cancelledDownload = () =>
	Object.assign(new Error('本次下载已取消'), { name: 'AbortError' })

// FileSystemWritableFileStream commits its temporary file only on close().
// Backpressure keeps at most the current chunk in this application's memory.
export async function writePrivateDownloadStream({
	response,
	handle,
	expectedSize,
	signal,
	isCurrent = () => true,
	abortNetwork = () => {},
	onProgress = () => {},
}) {
	let reader
	let writable
	let cancellation
	let diskAbort
	let rejectAborted
	const aborted = new Promise((_resolve, reject) => {
		rejectAborted = reject
	})
	aborted.catch(() => {})
	// Keep cleanup failures from replacing the original network/disk error.
	const cancelReader = (reason) => {
		if (reader && !cancellation)
			cancellation = Promise.resolve()
				.then(() => reader.cancel(reason))
				.catch(() => {})
		return cancellation
	}
	const abortWritable = (reason) => {
		if (writable && !diskAbort)
			diskAbort = Promise.resolve()
				.then(() => writable.abort(reason))
				.catch(() => {})
		return diskAbort
	}
	const onAbort = () => {
		const error = cancelledDownload()
		cancelReader(error)
		abortWritable(error)
		rejectAborted(error)
	}
	const checkCurrent = () => {
		if (signal?.aborted || !isCurrent()) throw cancelledDownload()
	}
	const wait = (promise) => Promise.race([promise, aborted])
	signal?.addEventListener('abort', onAbort, { once: true })
	try {
		if (typeof response.data?.getReader !== 'function')
			throw new Error('当前浏览器未返回文件数据流')
		reader = response.data.getReader()
		checkCurrent()
		privateDownloadSize(expectedSize)
		validatePrivateDownloadResponse(response, expectedSize)
		writable = await handle.createWritable()
		checkCurrent()
		let received = 0
		for (;;) {
			checkCurrent()
			const { done, value } = await wait(reader.read())
			checkCurrent()
			if (done) break
			if (!(value instanceof Uint8Array)) throw new Error('文件下载响应无效')
			received += value.byteLength
			if (received > expectedSize || received > MAX_PRIVATE_DOWNLOAD_BYTES)
				throw new Error('文件大小已变化，请刷新目录后重试')
			await wait(writable.write(value))
			onProgress(received)
		}
		if (received !== expectedSize)
			throw new Error('文件下载不完整，请刷新目录后重试')
		checkCurrent()
		await wait(writable.close())
		return received
	} catch (error) {
		// Stop the single authenticated request; never retry with a Blob here.
		abortNetwork()
		await Promise.all([cancelReader(error), abortWritable(error)])
		throw error
	} finally {
		signal?.removeEventListener('abort', onAbort)
		try {
			reader?.releaseLock()
		} catch {
			// A browser cancellation may already have released its reader.
		}
	}
}
