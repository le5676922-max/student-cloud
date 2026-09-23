<template>
	<span
		ref="spriteRef"
		class="ai-mascot-sprite"
		:class="{ 'is-compact': compact }"
		:style="spriteStyle"
		aria-hidden="true"
	/>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import mascotSpritesheetUrl from '~/assets/ai/assistant-mascot-spritesheet.webp'

const props = defineProps({
	hovered: Boolean,
	dragging: Boolean,
	dragDirection: {
		type: String,
		default: 'right',
	},
	compact: Boolean,
	interactive: {
		type: Boolean,
		default: true,
	},
	action: {
		type: String,
		default: '',
	},
})

const IDLE_DURATIONS = [280, 110, 110, 140, 140, 320]
const DRAG_DURATIONS = [120, 120, 120, 120, 120, 120, 120, 220]
const WAVE_DURATIONS = [140, 140, 140, 280]
const RECEIVE_DURATIONS = [180, 160, 160, 180, 160, 160, 180, 260]
const CATCH_DURATIONS = [90, 90, 100, 110, 120, 140, 160, 240]
const LOOK_DISTANCE = 360
const LOOK_DEAD_ZONE = 46

const spriteRef = ref()
const animationFrame = ref(0)
const lookDirection = ref(null)
const reducedMotion = ref(false)
let animationTimer = null
let motionQuery = null

const spriteState = computed(() => {
	if (props.action === 'catch') {
		return { row: 4, durations: CATCH_DURATIONS }
	}

	if (props.action === 'receive') {
		return { row: 6, durations: RECEIVE_DURATIONS }
	}

	if (props.dragging) {
		return {
			row: props.dragDirection === 'left' ? 2 : 1,
			durations: DRAG_DURATIONS,
		}
	}

	if (props.hovered) {
		return { row: 3, durations: WAVE_DURATIONS }
	}

	if (lookDirection.value !== null) {
		return lookDirection.value < 8
			? { row: 9, frame: lookDirection.value }
			: { row: 10, frame: lookDirection.value - 8 }
	}

	return { row: 0, durations: IDLE_DURATIONS }
})

const spriteStyle = computed(() => {
	const frame = spriteState.value.durations
		? animationFrame.value % spriteState.value.durations.length
		: spriteState.value.frame
	return {
		'--mascot-sheet': `url(${mascotSpritesheetUrl})`,
		'--mascot-column': `${(frame / 7) * 100}%`,
		'--mascot-row': `${(spriteState.value.row / 10) * 100}%`,
	}
})

watch(
	() => [
		props.action,
		props.hovered,
		props.dragging,
		props.dragDirection,
		lookDirection.value,
		reducedMotion.value,
	],
	restartAnimation,
	{ immediate: true }
)

onMounted(() => {
	motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
	reducedMotion.value = motionQuery.matches
	motionQuery.addEventListener?.('change', handleMotionPreference)
	if (props.interactive) {
		window.addEventListener('pointermove', trackPointer, { passive: true })
	}
})

onBeforeUnmount(() => {
	window.clearTimeout(animationTimer)
	motionQuery?.removeEventListener?.('change', handleMotionPreference)
	window.removeEventListener('pointermove', trackPointer)
})

function restartAnimation() {
	window.clearTimeout(animationTimer)
	animationFrame.value = 0
	scheduleNextFrame()
}

function scheduleNextFrame() {
	const durations = spriteState.value.durations
	if (!durations || reducedMotion.value) {
		return
	}
	animationTimer = window.setTimeout(() => {
		animationFrame.value = (animationFrame.value + 1) % durations.length
		scheduleNextFrame()
	}, durations[animationFrame.value])
}

function trackPointer(event) {
	if (props.action || props.hovered || props.dragging || !spriteRef.value) {
		return
	}
	const rect = spriteRef.value.getBoundingClientRect()
	const deltaX = event.clientX - (rect.left + rect.width / 2)
	const deltaY = event.clientY - (rect.top + rect.height / 2)
	const distance = Math.hypot(deltaX, deltaY)

	if (distance < LOOK_DEAD_ZONE || distance > LOOK_DISTANCE) {
		lookDirection.value = null
		return
	}

	const clockwiseFromUp = (Math.atan2(deltaX, -deltaY) * 180) / Math.PI
	const normalizedAngle = (clockwiseFromUp + 360) % 360
	lookDirection.value = Math.round(normalizedAngle / 22.5) % 16
}

function handleMotionPreference(event) {
	reducedMotion.value = event.matches
}
</script>

<style scoped>
.ai-mascot-sprite {
	display: block;
	width: 96px;
	height: 104px;
	pointer-events: none;
	background-image: var(--mascot-sheet);
	background-repeat: no-repeat;
	background-position: var(--mascot-column) var(--mascot-row);
	background-size: 800% 1100%;
	filter: drop-shadow(0 5px 4px rgb(17 24 39 / 22%));
	image-rendering: pixelated;
	user-select: none;
}

.ai-mascot-sprite.is-compact {
	width: 30px;
	height: 32.5px;
	filter: none;
}
</style>
