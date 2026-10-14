const canceledError = () =>
	Object.assign(new Error('存储源加载已取消'), { code: 'ERR_CANCELED' })

export const isStorageLoadCanceled = (error) =>
	error?.code === 'ERR_CANCELED' || error?.name === 'AbortError'

const committedCacheFailureMessage =
	'数据已保存，但存储列表缓存刷新失败。请恢复 Redis 后刷新页面，不要重复提交。'

/** Only this explicit API result is safe to display; never echo raw exceptions. */
export function storageCommittedFailureMessage(error) {
	const messages = [error?.data?.msg, error?.response?.data?.msg]
	return messages.some((message) => message === committedCacheFailureMessage)
		? committedCacheFailureMessage
		: ''
}

export function storageListErrorMessage(error) {
	if (['ETIMEDOUT', 'ECONNABORTED'].includes(error?.code)) {
		return '存储源加载超时，请检查后端服务或网络后重试。'
	}
	if (error?.code === 'INVALID_STORAGE_LIST') {
		return '存储源返回数据不完整，请重试。'
	}
	if ([401, 403].includes(error?.response?.status || error?.status)) {
		return '登录状态已失效或没有访问权限，请重新登录。'
	}
	return '暂时无法加载存储源，请检查后端服务或网络后重试。'
}

/** Latest request owns the view; leaving or retrying cancels obsolete work. */
export function createStorageListLoader({
	request,
	onStart = () => {},
	onLoaded = () => {},
	onFailure = () => {},
	onSettled = () => {},
	timeoutMs = 12000,
}) {
	let activeController
	let sequence = 0
	let disposed = false

	const load = async () => {
		if (disposed) return false
		const current = ++sequence
		activeController?.abort()
		const controller = new AbortController()
		activeController = controller
		const isCurrent = () => !disposed && current === sequence
		let timedOut = false
		let timer
		let onAbort

		try {
			onStart()
			const cancellation = new Promise((_, reject) => {
				onAbort = () => {
					reject(
						timedOut
							? Object.assign(new Error('存储源加载超时'), {
									code: 'ETIMEDOUT',
							  })
							: canceledError()
					)
				}
				controller.signal.addEventListener('abort', onAbort, { once: true })
			})
			timer = setTimeout(() => {
				timedOut = true
				controller.abort()
			}, timeoutMs)
			const response = await Promise.race([
				Promise.resolve().then(() => {
					if (controller.signal.aborted) throw canceledError()
					return request({ signal: controller.signal })
				}),
				cancellation,
			])
			if (!isCurrent()) return false
			if (
				!Array.isArray(response?.data) ||
				!response.data.every(
					(item) => item && typeof item === 'object' && !Array.isArray(item)
				)
			) {
				throw Object.assign(new Error('存储源列表格式错误'), {
					code: 'INVALID_STORAGE_LIST',
				})
			}
			await onLoaded(response.data, { isCurrent })
			return isCurrent()
		} catch (error) {
			if (isCurrent() && !isStorageLoadCanceled(error)) onFailure(error)
			return false
		} finally {
			clearTimeout(timer)
			if (onAbort) controller.signal.removeEventListener('abort', onAbort)
			if (isCurrent()) {
				activeController = undefined
				onSettled()
			}
		}
	}

	return {
		load,
		dispose() {
			disposed = true
			sequence++
			activeController?.abort()
			activeController = undefined
		},
	}
}

export function reorderStorageItems(items, oldIndex, newIndex) {
	if (
		!Array.isArray(items) ||
		!Number.isInteger(oldIndex) ||
		!Number.isInteger(newIndex) ||
		oldIndex < 0 ||
		newIndex < 0 ||
		oldIndex >= items.length ||
		newIndex >= items.length ||
		oldIndex === newIndex
	) {
		return null
	}
	const reordered = [...items]
	const [moved] = reordered.splice(oldIndex, 1)
	reordered.splice(newIndex, 0, moved)
	return reordered
}

export function filterStorageItems(items, query) {
	const normalized = String(query || '')
		.trim()
		.toLowerCase()
	if (!normalized) return items
	return items.filter((item) =>
		[item.name, item.remark, item.type?.description, item.type?.key].some(
			(value) =>
				typeof value === 'string' && value.toLowerCase().includes(normalized)
		)
	)
}
