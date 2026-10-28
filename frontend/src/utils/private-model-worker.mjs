import { MODEL_PREVIEW_LIMITS } from './private-model-preview.mjs'

export function validPrivateGeometry(value) {
	if (
		!value ||
		!(value.positions instanceof Float32Array) ||
		!(value.normals instanceof Float32Array) ||
		!Number.isInteger(value.triangleCount) ||
		value.triangleCount < 1 ||
		value.triangleCount > MODEL_PREVIEW_LIMITS.triangles ||
		!Number.isInteger(value.vertexCount) ||
		value.vertexCount < 1 ||
		value.vertexCount > MODEL_PREVIEW_LIMITS.vertices ||
		value.positions.length !== value.triangleCount * 9 ||
		value.normals.length !== value.positions.length
	)
		return false
	return (
		value.positions.every(Number.isFinite) &&
		value.normals.every(Number.isFinite)
	)
}

/** Transfer only the child component's copy; never detach the parent's preview buffer. */
export function parsePrivateModelInWorker(
	buffer,
	format,
	signal,
	{
		createWorker = () =>
			new Worker(
				new URL('../workers/private-model-preview.worker.js', import.meta.url),
				{ type: 'module' }
			),
		setTimer = setTimeout,
		clearTimer = clearTimeout,
		timeout = 8000,
	} = {}
) {
	const publicError = (message) =>
		Object.assign(new Error(message), { privatePreviewMessage: true })
	const cancelled = () =>
		Object.assign(new Error('预览已取消'), { name: 'AbortError' })
	return new Promise((resolve, reject) => {
		if (signal?.aborted) {
			reject(cancelled())
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
		const abort = () => finish(cancelled())
		try {
			worker = createWorker()
			if (signal?.aborted) {
				abort()
				return
			}
			worker.onmessage = ({ data }) =>
				data?.ok === true && validPrivateGeometry(data.geometry)
					? finish(null, data.geometry)
					: finish(publicError('模型无法安全解析或超过预览预算，请下载后查看'))
			worker.onerror = () => finish(publicError('模型解析失败，请下载后查看'))
			signal?.addEventListener('abort', abort, { once: true })
			timer = setTimer(
				() => finish(publicError('模型解析超时，请精简模型或下载后查看')),
				timeout
			)
			worker.postMessage({ buffer, format }, [buffer])
		} catch {
			finish(publicError('当前浏览器不支持安全模型解析，请下载后查看'))
		}
	})
}
