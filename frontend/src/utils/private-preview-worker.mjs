/** Each document owns one disposable worker; no account tokens enter it. */
export function parsePrivatePreviewDocument(
	buffer,
	name,
	signal,
	{
		createWorker = () =>
			new Worker(
				new URL('../workers/private-preview.worker.js', import.meta.url),
				{ type: 'module' }
			),
		timeout = 10000,
		setTimer = setTimeout,
		clearTimer = clearTimeout,
	} = {}
) {
	const error = (message) =>
		Object.assign(new Error(message), { privatePreviewMessage: true })
	return new Promise((resolve, reject) => {
		if (signal?.aborted) {
			reject(Object.assign(new Error('预览已取消'), { name: 'AbortError' }))
			return
		}
		let worker,
			timer,
			settled = false
		const finish = (error, value) => {
			if (settled) return
			settled = true
			clearTimer(timer)
			signal?.removeEventListener('abort', abort)
			worker?.terminate()
			error ? reject(error) : resolve(value)
		}
		const abort = () =>
			finish(Object.assign(new Error('预览已取消'), { name: 'AbortError' }))
		try {
			worker = createWorker()
			if (signal?.aborted) {
				abort()
				return
			}
			worker.onmessage = (event) => {
				const result = event.data
				if (
					result?.ok === true &&
					['document', 'slides', 'workbook'].includes(result.value?.kind)
				)
					finish(null, result.value)
				else finish(error('文档无法安全解析或超过预览预算，请下载后查看'))
			}
			worker.onerror = () => finish(error('文档解析失败，请下载后查看'))
			signal?.addEventListener('abort', abort, { once: true })
			timer = setTimer(
				() => finish(error('文档解析超时，请下载后查看')),
				timeout
			)
			worker.postMessage({ buffer, name }, [buffer])
		} catch {
			finish(error('当前浏览器不支持本地文档预览，请下载后查看'))
		}
	})
}
