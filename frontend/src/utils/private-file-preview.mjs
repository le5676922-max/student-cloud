const BINARY_LIMIT = 20 * 1048576
const TEXT_LIMIT = 1048576
const OFFICE_LIMIT = 10 * 1048576
const MEDIA_LIMIT = 50 * 1048576
const TYPES = {
	image: new Set([
		'image/png',
		'image/jpeg',
		'image/gif',
		'image/webp',
		'image/bmp',
	]),
	pdf: new Set(['application/pdf']),
	text: new Set(['text/plain']),
	markdown: new Set(['text/plain']),
	audio: new Set([
		'audio/mpeg',
		'audio/mp4',
		'audio/aac',
		'audio/wav',
		'audio/ogg',
		'audio/flac',
	]),
	video: new Set(['video/mp4', 'video/webm', 'video/ogg']),
	office: new Set([
		'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
		'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
		'application/vnd.openxmlformats-officedocument.presentationml.presentation',
	]),
	model: new Set(['application/octet-stream']),
}
const publicError = (message) =>
	Object.assign(new Error(message), { privatePreviewMessage: true })

export function privatePreviewTarget(row, key) {
	if (
		!/^[a-z0-9][a-z0-9_-]{2,63}$/.test(key) ||
		row?.authenticatedContent !== true ||
		row.previewAvailable !== true ||
		!TYPES[row.previewKind]
	)
		throw publicError('当前文件暂不支持安全预览，请下载后查看')
	const limit = ['text', 'markdown'].includes(row.previewKind)
		? TEXT_LIMIT
		: ['office', 'model'].includes(row.previewKind)
		? OFFICE_LIMIT
		: ['audio', 'video'].includes(row.previewKind)
		? MEDIA_LIMIT
		: BINARY_LIMIT
	if (!Number.isSafeInteger(row.size) || row.size < 0 || row.size > limit)
		throw publicError('文件超过预览预算，请下载后查看')
	const value = row.previewUrl
	if (
		typeof value !== 'string' ||
		value.length > 8192 ||
		!value.startsWith(`/api/drives/${key}/preview?`) ||
		/[\\\s#]/.test(value) ||
		/%(?![a-f0-9]{2})/i.test(value)
	)
		throw publicError('预览地址无效，请刷新目录后重试')
	const url = new URL(value, 'https://preview.invalid')
	const entries = [...url.searchParams]
	const path = url.searchParams.get('path')
	const expected = `${row.path === '/' ? '' : row.path}/${row.name}`
	if (
		entries.length !== 1 ||
		entries[0][0] !== 'path' ||
		!path?.startsWith('/') ||
		path !== expected ||
		// eslint-disable-next-line no-control-regex -- Reject control characters in virtual paths.
		/[\\\u0000-\u001f]/.test(path) ||
		path.split('/').some((part) => part === '.' || part === '..')
	)
		throw publicError('预览地址与文件不符，请刷新目录后重试')
	return {
		url: value,
		kind: row.previewKind,
		limit,
		size: row.size,
		name: row.name,
	}
}

const empty = () => ({
	visible: false,
	loading: false,
	name: '',
	kind: '',
	url: '',
	text: '',
	error: '',
	document: null,
	mediaType: '',
	buffer: null,
})

/** One owner-bound preview. Closing/replacing invalidates pending IO and releases every object URL. */
export function createPrivatePreviewSession({
	request,
	onChange,
	parseDocument,
	createUrl = URL.createObjectURL.bind(URL),
	revokeUrl = URL.revokeObjectURL.bind(URL),
}) {
	let sequence = 0,
		controller,
		currentUrl = '',
		state = empty()
	const update = (value) => {
		state = value
		onChange(value)
	}
	function close() {
		sequence++
		controller?.abort()
		controller = undefined
		if (currentUrl) revokeUrl(currentUrl)
		currentUrl = ''
		update(empty())
	}
	async function open(row, key) {
		close()
		const id = sequence,
			signal = (controller = new AbortController()).signal
		const active = () => sequence === id && !signal.aborted
		update({
			...empty(),
			visible: true,
			loading: true,
			name: row?.name || '文件预览',
		})
		try {
			const target = privatePreviewTarget(row, key)
			const response = await request(target.url, signal)
			if (!active()) return false
			const blob = response.data
			const type = String(
				response.headers?.get?.('content-type') ||
					response.headers?.['content-type'] ||
					blob?.type ||
					''
			)
				.split(';')[0]
				.trim()
				.toLowerCase()
			if (
				!(blob instanceof Blob) ||
				blob.size > target.limit ||
				blob.size !== target.size ||
				(blob.type && blob.type.split(';')[0].trim().toLowerCase() !== type) ||
				!TYPES[target.kind].has(type)
			)
				throw publicError('预览内容或格式无效，请下载后查看')
			if (
				target.kind === 'office' ||
				(target.kind === 'text' && /\.(csv|tsv)$/i.test(target.name))
			) {
				if (typeof parseDocument !== 'function')
					throw publicError('当前浏览器不支持本地文档预览，请下载后查看')
				const document = await parseDocument(
					await blob.arrayBuffer(),
					target.name,
					signal
				)
				if (!active()) return false
				update({ ...state, loading: false, kind: 'office', document })
			} else if (target.kind === 'model') {
				const buffer = await blob.arrayBuffer()
				if (!active()) return false
				update({ ...state, loading: false, kind: 'model', buffer })
			} else if (['text', 'markdown'].includes(target.kind)) {
				const text = new TextDecoder('utf-8', { fatal: true }).decode(
					await blob.arrayBuffer()
				)
				if (text.includes('\0'))
					throw publicError('文件不是可预览的文本，请下载后查看')
				if (!active()) return false
				update({ ...state, loading: false, kind: target.kind, text })
			} else {
				currentUrl = createUrl(new Blob([blob], { type }))
				update({
					...state,
					loading: false,
					kind: target.kind,
					url: currentUrl,
					mediaType: type,
				})
			}
			return true
		} catch (failure) {
			if (!active()) return false
			const messages = {
				401: '请重新登录后预览',
				403: '没有读取此文件的权限',
				404: '文件已移动或不存在，请刷新目录',
				413: '文件超过预览预算，请下载后查看',
				415: '文件格式不支持或内容无效，请下载后查看',
				429: '预览服务繁忙，请稍后重试',
			}
			update({
				...state,
				loading: false,
				error:
					messages[failure?.response?.status] ||
					(failure?.privatePreviewMessage === true
						? failure.message
						: '预览失败，请刷新后重试'),
			})
			return false
		}
	}
	return { open, close }
}
