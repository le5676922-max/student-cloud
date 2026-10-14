const INTERACTIVE_SELECTOR =
	'input, textarea, select, button, a[href], [role="textbox"], [role="combobox"], [contenteditable]:not([contenteditable="false"])'

function interactiveTarget(target) {
	return (
		!!target &&
		(target.isContentEditable === true ||
			['INPUT', 'TEXTAREA', 'SELECT', 'BUTTON'].includes(
				target.tagName?.toUpperCase()
			) ||
			!!target.closest?.(INTERACTIVE_SELECTOR))
	)
}

// Never let file shortcuts intercept typing/IME or a focused control, even if the mouse is over the file list.
export function fileShortcutAllowed(event, activeElement) {
	if (
		!event ||
		event.defaultPrevented ||
		event.isComposing ||
		event.keyCode === 229 ||
		event.repeat
	)
		return false
	const targets = [
		event.target,
		activeElement,
		...(event.composedPath?.() || []),
	]
	return !targets.some(interactiveTarget)
}

export function galleryColumnCount(configured) {
	const count = Number(configured)
	return Number.isFinite(count) && count > 0
		? Math.max(1, Math.min(24, Math.floor(count)))
		: 3
}

// Fill columns directly; transposing an incomplete last row creates undefined cells.
export function galleryColumns(items, configured) {
	const files = Array.isArray(items) ? items.filter(Boolean) : []
	if (!files.length) return []
	const count = Math.min(files.length, galleryColumnCount(configured))
	const columns = Array.from({ length: count }, () => [])
	files.forEach((file, index) => columns[index % count].push(file))
	return columns
}
