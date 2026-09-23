export const PANEL_MIN_WIDTH = 320
export const PANEL_MIN_HEIGHT = 360
export const PANEL_RIGHT_OFFSET = 24
export const PANEL_BOTTOM_OFFSET = 88

export function clampPanelSize(width, height, viewport) {
	const maxWidth = Math.max(
		PANEL_MIN_WIDTH,
		viewport.width - PANEL_RIGHT_OFFSET - viewport.padding
	)
	const maxHeight = Math.max(
		PANEL_MIN_HEIGHT,
		viewport.height - PANEL_BOTTOM_OFFSET - viewport.padding
	)
	return {
		width: Math.round(Math.min(Math.max(width, PANEL_MIN_WIDTH), maxWidth)),
		height: Math.round(Math.min(Math.max(height, PANEL_MIN_HEIGHT), maxHeight)),
	}
}

export function restorePanelSize(savedSize, viewport) {
	if (
		!Number.isFinite(savedSize?.width) ||
		!Number.isFinite(savedSize?.height)
	) {
		return null
	}
	return clampPanelSize(savedSize.width, savedSize.height, viewport)
}
