// SPDX-License-Identifier: MIT
// Cursor calculations adapted from CareerCompass, Copyright (c) 2025 arsh342.

const neutralPose = () => ({ faceX: 0, faceY: 0, bodySkew: 0 })

// Receive interaction flags only, never an account or password value.
export function loginInteractionMode({
	showPassword = false,
	passwordLength = 0,
	passwordFocused = false,
	isTyping = false,
	status = 'idle',
} = {}) {
	if (showPassword && Number.isFinite(passwordLength) && passwordLength > 0)
		return 'privacy'
	if (['error', 'success', 'busy'].includes(status)) return status
	if (passwordFocused) return 'password'
	if (isTyping) return 'account'
	return 'idle'
}

// Curved code-native outlines, sampled from the video's interaction poses.
export function characterOutline(key, height, mode = 'idle') {
	if (!Number.isFinite(height) || height <= 0) return null
	if (key === 'purple') {
		if (mode === 'password')
			return `M 70 0 L 192 80 Q 196 84 192 90 C 152 154 146 ${
				height - 110
			} 180 ${height} L 0 ${height} C -8 ${
				height - 180
			} 22 92 62 8 Q 66 2 70 0 Z`
		if (mode === 'error')
			return `M 12 0 L 176 28 Q 180 30 178 38 C 174 82 168 116 155 155 L 180 ${height} L 0 ${height} L -22 172 C -10 100 -2 48 8 8 Q 10 2 12 0 Z`
		return `M 10 0 L 170 0 Q 180 0 180 10 C 180 100 180 ${
			height - 100
		} 180 ${height} L 0 ${height} C 0 ${height - 100} 0 100 0 10 Q 0 0 10 0 Z`
	}
	if (key === 'black') {
		if (mode === 'password')
			return `M 0 32 L 108 0 Q 115 -2 116 6 C 135 86 128 ${
				height - 90
			} 120 ${height} L 0 ${height} C 24 ${
				height - 110
			} 28 108 -4 42 Q -6 36 0 32 Z`
		return `M 8 0 L 112 0 Q 120 0 120 8 C 120 80 120 ${
			height - 80
		} 120 ${height} L 0 ${height} C 0 ${height - 80} 0 80 0 8 Q 0 0 8 0 Z`
	}
	return null
}

function hasUsableBounds(bounds) {
	return (
		bounds &&
		[bounds.left, bounds.top, bounds.width, bounds.height].every(
			Number.isFinite
		) &&
		bounds.width > 0 &&
		bounds.height > 0
	)
}

export function characterPose(clientX, clientY, bounds) {
	if (
		!hasUsableBounds(bounds) ||
		!Number.isFinite(clientX) ||
		!Number.isFinite(clientY)
	) {
		return neutralPose()
	}
	const deltaX = clientX - (bounds.left + bounds.width / 2)
	const deltaY = clientY - (bounds.top + bounds.height / 3)
	return {
		faceX: Math.max(-15, Math.min(15, deltaX / 20)),
		faceY: Math.max(-10, Math.min(10, deltaY / 30)),
		bodySkew: Math.max(-6, Math.min(6, -deltaX / 120)),
	}
}

export function pupilOffset(clientX, clientY, bounds, maxDistance = 5) {
	if (
		!hasUsableBounds(bounds) ||
		!Number.isFinite(clientX) ||
		!Number.isFinite(clientY) ||
		!Number.isFinite(maxDistance) ||
		maxDistance <= 0
	) {
		return { x: 0, y: 0 }
	}
	const deltaX = clientX - (bounds.left + bounds.width / 2)
	const deltaY = clientY - (bounds.top + bounds.height / 2)
	return limitPupilOffset({ x: deltaX, y: deltaY }, maxDistance)
}

// Apply the same eye-socket limit to scripted glances and pointer tracking.
export function limitPupilOffset(offset, maxDistance = 5) {
	if (
		!offset ||
		!Number.isFinite(offset.x) ||
		!Number.isFinite(offset.y) ||
		!Number.isFinite(maxDistance) ||
		maxDistance <= 0
	) {
		return { x: 0, y: 0 }
	}
	const distance = Math.hypot(offset.x, offset.y)
	if (distance === 0) return { x: 0, y: 0 }
	const ratio = Math.min(distance, maxDistance) / distance
	return { x: offset.x * ratio, y: offset.y * ratio }
}
