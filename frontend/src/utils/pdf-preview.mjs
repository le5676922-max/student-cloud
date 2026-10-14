export const PDF_ZOOM_MIN = 50
export const PDF_ZOOM_MAX = 200

export function clampPdfPage(value, total, current = 1) {
	const maximum =
		Number.isSafeInteger(Number(total)) && Number(total) > 0 ? Number(total) : 1
	const parsed =
		typeof value === 'string' && !value.trim() ? NaN : Number(value)
	const fallback = Number.isFinite(Number(current))
		? Math.trunc(Number(current))
		: 1
	return Math.max(
		1,
		Math.min(maximum, Number.isFinite(parsed) ? Math.trunc(parsed) : fallback)
	)
}

export function clampPdfZoom(value) {
	const parsed = Number(value)
	return Number.isFinite(parsed)
		? Math.min(PDF_ZOOM_MAX, Math.max(PDF_ZOOM_MIN, Math.round(parsed)))
		: 100
}

export function pdfLoadingProgress(progress) {
	const loaded = Number(progress?.loaded)
	const total = Number(progress?.total)
	if (
		!Number.isFinite(loaded) ||
		loaded < 0 ||
		!Number.isFinite(total) ||
		total <= 0
	)
		return null
	return Math.min(100, Math.round((loaded / total) * 100))
}

// The component listens only inside its preview, never to global keyboard state.
export function pdfKeyboardAction(event) {
	if (
		event.defaultPrevented ||
		event.ctrlKey ||
		event.metaKey ||
		event.altKey ||
		event.isComposing
	)
		return null
	const target = event.target
	if (
		target?.isContentEditable ||
		['INPUT', 'TEXTAREA', 'SELECT'].includes(target?.tagName)
	)
		return null
	return (
		{
			ArrowLeft: 'previous',
			ArrowRight: 'next',
			'+': 'zoom-in',
			'=': 'zoom-in',
			'-': 'zoom-out',
		}[event.key] || null
	)
}

export function createPdfSessionGuard() {
	let generation = 0
	return {
		begin() {
			const id = ++generation
			return {
				id,
				guard:
					(callback) =>
					(...args) => {
						if (id === generation) return callback(...args)
					},
			}
		},
		invalidate() {
			generation++
		},
	}
}
