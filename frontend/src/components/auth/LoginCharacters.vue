<script setup>
/*
 * Vue adaptation of CareerCompass's animated-characters.tsx.
 * Copyright (c) 2025 arsh342
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
import {
	computed,
	onBeforeUnmount,
	onMounted,
	ref,
	shallowRef,
	watch,
} from 'vue'
import {
	characterPose,
	characterOutline,
	limitPupilOffset,
	loginInteractionMode,
	pupilOffset,
} from '~/utils/login-characters.mjs'

const props = defineProps({
	isTyping: { type: Boolean, default: false },
	showPassword: { type: Boolean, default: false },
	passwordLength: { type: Number, default: 0 },
	passwordFocused: { type: Boolean, default: false },
	status: { type: String, default: 'idle' },
	paused: { type: Boolean, default: false },
})

// Restore the reference silhouettes; the occupied width is 450px, not 550px.
const sceneSize = { width: 450, height: 440 }
const definitions = [
	{
		key: 'purple',
		left: 70,
		width: 180,
		height: 400,
		color: '#6C3FF5',
		radius: '10px 10px 0 0',
		layer: 1,
		eyeSize: 18,
		pupilSize: 7,
		eyeGap: 32,
		maxDistance: 5,
		whiteEyes: true,
	},
	{
		key: 'black',
		left: 240,
		width: 120,
		height: 310,
		color: '#2D2D2D',
		radius: '8px 8px 0 0',
		layer: 2,
		eyeSize: 16,
		pupilSize: 6,
		eyeGap: 24,
		maxDistance: 4,
		whiteEyes: true,
	},
	{
		key: 'orange',
		left: 0,
		width: 240,
		height: 200,
		color: '#FF9B6B',
		radius: '120px 120px 0 0',
		layer: 3,
		eyeSize: 12,
		pupilSize: 12,
		eyeGap: 32,
		maxDistance: 5,
		whiteEyes: false,
	},
	{
		key: 'yellow',
		left: 310,
		width: 140,
		height: 230,
		color: '#E8D754',
		radius: '70px 70px 0 0',
		layer: 4,
		eyeSize: 12,
		pupilSize: 12,
		eyeGap: 24,
		maxDistance: 5,
		whiteEyes: false,
	},
]
const restingFaces = {
	purple: { x: 45, y: 40 },
	black: { x: 26, y: 32 },
	orange: { x: 82, y: 90 },
	yellow: { x: 52, y: 40 },
}
const privateFaces = {
	purple: { x: 20, y: 35 },
	black: { x: 10, y: 28 },
	orange: { x: 50, y: 85 },
	yellow: { x: 20, y: 35 },
}

const scene = ref(null)
const motionActive = ref(false)
const changingPose = ref(false)
const purpleBlinking = ref(false)
const blackBlinking = ref(false)
const lookingAtEachOther = ref(false)
const interactionMode = computed(() => loginInteractionMode(props))
const visiblePassword = computed(
	() => props.passwordLength > 0 && props.showPassword
)
const poses = shallowRef({})
const pupilOffsets = shallowRef({})
const timers = {
	purple: new Set(),
	black: new Set(),
	glance: new Set(),
	pose: new Set(),
}
let pointer = null
let frame = 0
let mounted = false
let intersects = false
let reducedMotionQuery
let desktopQuery
let intersectionObserver

function forcedLook(key) {
	if (visiblePassword.value) {
		return { x: key === 'black' ? -4 : -5, y: -4 }
	}
	if (interactionMode.value === 'password') {
		if (key === 'purple') return { x: 3, y: 4 }
		if (key === 'black') return { x: 0, y: -4 }
	}
	if (interactionMode.value === 'busy') return { x: 4, y: 1 }
	if (lookingAtEachOther.value && key === 'purple') return { x: 3, y: 4 }
	if (lookingAtEachOther.value && key === 'black') return { x: 0, y: -4 }
	return null
}

function facePosition(key, position) {
	if (visiblePassword.value) return privateFaces[key]
	if (interactionMode.value === 'password') {
		if (key === 'purple') return { x: 110, y: 76 }
		if (key === 'black') return { x: 30, y: 32 }
	}
	if (interactionMode.value === 'error' && key === 'purple')
		return { x: 75, y: 58 }
	if (lookingAtEachOther.value) {
		if (key === 'purple') return { x: 55, y: 65 }
		if (key === 'black') return { x: 32, y: 12 }
	}
	return {
		x: restingFaces[key].x + position.faceX,
		y: restingFaces[key].y + position.faceY,
	}
}

function bodyPose(key, position) {
	// Revealing a password takes precedence over all typing poses.
	if (visiblePassword.value) return { skew: 0, shift: 0 }
	if (['password', 'error'].includes(interactionMode.value))
		return { skew: 0, shift: 0 }
	if (key === 'purple' && interactionMode.value === 'account')
		return { skew: position.bodySkew - 12, shift: 40 }
	if (key === 'black') {
		if (lookingAtEachOther.value)
			return { skew: position.bodySkew * 1.5 + 10, shift: 20 }
		if (interactionMode.value === 'account')
			return { skew: position.bodySkew * 1.5, shift: 0 }
	}
	return { skew: position.bodySkew, shift: 0 }
}

function bodyHeight(definition) {
	return (
		definition.height +
		(definition.key === 'purple' &&
		['account', 'password'].includes(interactionMode.value)
			? 40
			: 0)
	)
}

function mouthExpression(key, face, position) {
	const mode = interactionMode.value
	if (key === 'black') return { visible: false }
	if (key === 'yellow') {
		const short = mode === 'privacy'
		return {
			visible: true,
			path: mode === 'error' ? 'M 2 9 Q 10 3 18 9 T 34 9' : 'M 2 9 L 78 9',
			viewBox: '0 0 80 18',
			style: {
				width: `${short || mode === 'error' ? 54 : 80}px`,
				height: '18px',
				transform: `translate(${short ? 0 : 40 + position.faceX}px, ${
					short ? 73 : 73 + position.faceY
				}px)`,
			},
		}
	}
	const concerned = mode === 'error' || (mode === 'privacy' && key === 'purple')
	const path = concerned
		? 'M 7 18 Q 20 4 33 18'
		: mode === 'password' && key === 'purple'
		? 'M 17 10 L 23 10 L 23 16 L 17 16 Z'
		: mode === 'privacy' && key === 'orange'
		? 'M 19 12 L 21 12'
		: 'M 7 9 Q 20 23 33 9'
	return {
		visible: true,
		path,
		viewBox: '0 0 40 26',
		style: {
			width: `${key === 'purple' ? 28 : 34}px`,
			height: '23px',
			transform: `translate(${face.x + (key === 'purple' ? 19 : 11)}px, ${
				face.y + 20
			}px)${mode === 'password' && key === 'purple' ? ' rotate(32deg)' : ''}`,
		},
	}
}

const characters = computed(() =>
	definitions.map((definition) => {
		const { key } = definition
		const position = poses.value[key] || { faceX: 0, faceY: 0, bodySkew: 0 }
		const face = facePosition(key, position)
		const body = bodyPose(key, position)
		const height = bodyHeight(definition)
		const blinking =
			key === 'purple'
				? purpleBlinking.value
				: key === 'black' && blackBlinking.value
		const look = forcedLook(key)
		return {
			...definition,
			outline: characterOutline(key, height, interactionMode.value),
			outlineViewBox: `0 0 ${definition.width} ${height}`,
			bodyStyle: {
				left: `${definition.left}px`,
				width: `${definition.width}px`,
				height: `${height}px`,
				backgroundColor: definition.color,
				borderRadius: definition.radius,
				zIndex: definition.layer,
				transform: `skewX(${body.skew}deg) translateX(${body.shift}px)`,
			},
			faceStyle: {
				transform: `translate(${face.x}px, ${face.y}px)${
					interactionMode.value === 'password' && key === 'purple'
						? ' rotate(32deg)'
						: interactionMode.value === 'password' && key === 'black'
						? ' rotate(-14deg)'
						: ''
				}`,
				gap: `${definition.eyeGap}px`,
			},
			eyes: Array.from({ length: 2 }, (_, index) => {
				const eyeKey = `${key}-${index}`
				const offset = limitPupilOffset(
					look || pupilOffsets.value[eyeKey] || { x: 0, y: 0 },
					definition.maxDistance
				)
				return {
					key: eyeKey,
					closed: key === 'orange' && interactionMode.value === 'privacy',
					style: {
						width: `${definition.eyeSize}px`,
						height: `${blinking ? 2 : definition.eyeSize}px`,
						backgroundColor: definition.whiteEyes ? '#FFFFFF' : 'transparent',
					},
					pupilStyle: {
						width: `${definition.pupilSize}px`,
						height: `${definition.pupilSize}px`,
						transform: `translate(${offset.x}px, ${offset.y}px)`,
						opacity:
							blinking ||
							(key === 'orange' && interactionMode.value === 'privacy')
								? 0
								: 1,
					},
				}
			}),
			mouth: mouthExpression(key, face, position),
		}
	})
)

function queuePointer() {
	if (mounted && motionActive.value && pointer && !frame)
		frame = window.requestAnimationFrame(updatePointer)
}

// One untransformed scene measurement avoids feeding animated DOM positions back
// into the next pose. No per-eye listeners or permanently running RAF loop.
function updatePointer() {
	frame = 0
	if (!motionActive.value || !pointer || !scene.value) return
	const bounds = scene.value.getBoundingClientRect()
	if (
		![bounds.left, bounds.top, bounds.width, bounds.height].every(
			Number.isFinite
		) ||
		bounds.width <= 0 ||
		bounds.height <= 0
	) {
		poses.value = {}
		pupilOffsets.value = {}
		return
	}
	const scaleX = bounds.width / sceneSize.width
	const scaleY = bounds.height / sceneSize.height
	const localX = (pointer.x - bounds.left) / scaleX
	const localY = (pointer.y - bounds.top) / scaleY
	const nextPoses = {}
	const nextPupils = {}
	for (const definition of definitions) {
		const height = bodyHeight(definition)
		const bodyTop = sceneSize.height - height
		const position = characterPose(localX, localY, {
			left: definition.left,
			top: bodyTop,
			width: definition.width,
			height,
		})
		nextPoses[definition.key] = position
		const face = facePosition(definition.key, position)
		const body = bodyPose(definition.key, position)
		for (let index = 0; index < 2; index++) {
			const eyeX = face.x + index * (definition.eyeSize + definition.eyeGap)
			const skewShift =
				Math.tan((body.skew * Math.PI) / 180) *
				(face.y + definition.eyeSize / 2 - height)
			nextPupils[`${definition.key}-${index}`] = pupilOffset(
				localX,
				localY,
				{
					left: definition.left + eyeX + body.shift + skewShift,
					top: bodyTop + face.y,
					width: definition.eyeSize,
					height: definition.eyeSize,
				},
				definition.maxDistance
			)
		}
	}
	poses.value = nextPoses
	pupilOffsets.value = nextPupils
}

function handlePointer(event) {
	if (
		!motionActive.value ||
		event.pointerType === 'touch' ||
		!Number.isFinite(event.clientX) ||
		!Number.isFinite(event.clientY)
	)
		return
	pointer = { x: event.clientX, y: event.clientY }
	queuePointer()
}

function clearPointer() {
	pointer = null
	if (frame) window.cancelAnimationFrame(frame)
	frame = 0
	poses.value = {}
	pupilOffsets.value = {}
}

function clearTimers(bucket) {
	for (const timer of timers[bucket]) window.clearTimeout(timer)
	timers[bucket].clear()
}

function schedule(bucket, callback, delay) {
	const timer = window.setTimeout(() => {
		timers[bucket].delete(timer)
		if (mounted && motionActive.value) callback()
	}, delay)
	timers[bucket].add(timer)
}

function scheduleBlink(key) {
	const blinking = key === 'purple' ? purpleBlinking : blackBlinking
	schedule(
		key,
		() => {
			blinking.value = true
			schedule(
				key,
				() => {
					blinking.value = false
					scheduleBlink(key)
				},
				150
			)
		},
		3000 + Math.random() * 4000
	)
}

function updateGlance() {
	clearTimers('glance')
	lookingAtEachOther.value =
		motionActive.value && interactionMode.value === 'account'
	if (lookingAtEachOther.value)
		schedule(
			'glance',
			() => {
				lookingAtEachOther.value = false
				transitionPose()
			},
			800
		)
}

function transitionPose() {
	clearTimers('pose')
	changingPose.value = motionActive.value
	if (changingPose.value)
		schedule(
			'pose',
			() => {
				changingPose.value = false
			},
			550
		)
	queuePointer()
}

function stopMotion() {
	window.removeEventListener('pointermove', handlePointer)
	clearPointer()
	for (const bucket of Object.keys(timers)) clearTimers(bucket)
	changingPose.value = false
	purpleBlinking.value = false
	blackBlinking.value = false
	lookingAtEachOther.value = false
}

function syncActivity() {
	if (!mounted) return
	const active =
		!props.paused &&
		!document.hidden &&
		!reducedMotionQuery.matches &&
		desktopQuery.matches &&
		intersects
	if (motionActive.value === active) return
	motionActive.value = active
	if (!active) {
		stopMotion()
		return
	}
	window.addEventListener('pointermove', handlePointer, { passive: true })
	scheduleBlink('purple')
	scheduleBlink('black')
	updateGlance()
}

watch(() => props.paused, syncActivity)
// Focus and feedback change the pose once, not on every password keystroke.
watch(interactionMode, () => {
	updateGlance()
	transitionPose()
})
watch(lookingAtEachOther, queuePointer)

onMounted(() => {
	mounted = true
	reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
	desktopQuery = window.matchMedia('(min-width: 1024px)')
	reducedMotionQuery.addEventListener('change', syncActivity)
	desktopQuery.addEventListener('change', syncActivity)
	document.addEventListener('visibilitychange', syncActivity)
	document.addEventListener('pointerleave', clearPointer)
	document.addEventListener('scroll', queuePointer, {
		passive: true,
		capture: true,
	})
	window.addEventListener('blur', clearPointer)
	window.addEventListener('resize', queuePointer, { passive: true })
	if (typeof IntersectionObserver === 'undefined') {
		intersects = true
	} else {
		intersectionObserver = new IntersectionObserver(([entry]) => {
			intersects = Boolean(
				entry?.isIntersecting &&
					entry.intersectionRect.width > 0 &&
					entry.intersectionRect.height > 0
			)
			syncActivity()
		})
		intersectionObserver.observe(scene.value)
	}
	syncActivity()
})

onBeforeUnmount(() => {
	mounted = false
	motionActive.value = false
	stopMotion()
	reducedMotionQuery?.removeEventListener('change', syncActivity)
	desktopQuery?.removeEventListener('change', syncActivity)
	document.removeEventListener('visibilitychange', syncActivity)
	document.removeEventListener('pointerleave', clearPointer)
	document.removeEventListener('scroll', queuePointer, true)
	window.removeEventListener('blur', clearPointer)
	window.removeEventListener('resize', queuePointer)
	intersectionObserver?.disconnect()
})
</script>

<template>
	<div
		ref="scene"
		class="login-characters"
		:class="{ 'is-static': !motionActive, 'is-changing-pose': changingPose }"
		:data-interaction="interactionMode"
		aria-hidden="true"
	>
		<div
			v-for="character in characters"
			:key="character.key"
			class="login-character"
			:class="[
				`login-character--${character.key}`,
				{ 'has-shaped-body': character.outline },
			]"
			:style="character.bodyStyle"
		>
			<svg
				v-if="character.outline"
				class="character-outline"
				:viewBox="character.outlineViewBox"
				preserveAspectRatio="none"
				focusable="false"
			>
				<path :d="character.outline" :fill="character.color" />
			</svg>
			<div class="character-face" :style="character.faceStyle">
				<div
					v-for="eye in character.eyes"
					:key="eye.key"
					class="character-eye"
					:class="{ 'character-eye--dots': !character.whiteEyes }"
					:style="eye.style"
				>
					<div class="character-pupil" :style="eye.pupilStyle" />
					<svg
						v-if="eye.closed"
						class="character-eyelid"
						viewBox="0 0 18 12"
						focusable="false"
					>
						<path d="M 2 8 Q 9 1 16 8" />
					</svg>
				</div>
			</div>
			<svg
				v-if="character.mouth.visible"
				class="character-mouth"
				:style="character.mouth.style"
				:viewBox="character.mouth.viewBox"
				focusable="false"
			>
				<path :d="character.mouth.path" />
			</svg>
		</div>
	</div>
</template>

<style scoped>
.login-characters {
	position: relative;
	width: 450px;
	height: 440px;
	flex-shrink: 0;
	pointer-events: none;
	user-select: none;
}

.login-character,
.character-face,
.character-eye,
.character-pupil,
.character-mouth {
	box-sizing: border-box;
}

.login-character {
	position: absolute;
	bottom: 0;
	transform-origin: bottom center;
	transition: transform 220ms ease-out,
		height 550ms cubic-bezier(0.22, 1, 0.36, 1);
}

.login-character.has-shaped-body {
	background-color: transparent !important;
}

.character-outline {
	position: absolute;
	inset: 0;
	width: 100%;
	height: 100%;
	overflow: visible;
}

.character-face {
	position: absolute;
	top: 0;
	left: 0;
	display: flex;
	transition: transform 180ms ease-out;
}

.is-changing-pose .login-character,
.is-changing-pose .character-face,
.is-changing-pose .character-mouth {
	transition-duration: 550ms;
	transition-timing-function: cubic-bezier(0.22, 1, 0.36, 1);
}

.character-eye {
	position: relative;
	display: flex;
	align-items: center;
	justify-content: center;
	flex-shrink: 0;
	overflow: hidden;
	border-radius: 50%;
	transition: height 150ms ease;
}

.character-eye--dots {
	overflow: visible;
}

.character-pupil {
	flex-shrink: 0;
	border-radius: 50%;
	background: #2d2d2d;
	transition: transform 100ms ease-out;
}

.character-mouth {
	position: absolute;
	top: 0;
	left: 0;
	overflow: visible;
	transition: transform 180ms ease-out;
}

.character-mouth path,
.character-eyelid path {
	fill: none;
	stroke: #2d2d2d;
	stroke-width: 4;
	stroke-linecap: round;
	stroke-linejoin: round;
}

.character-eyelid {
	position: absolute;
	inset: 0;
	left: -3px;
	width: 18px;
	height: 12px;
}

.character-eyelid path {
	stroke-width: 2.5;
}

.login-characters.is-static .login-character,
.login-characters.is-static .character-face,
.login-characters.is-static .character-eye,
.login-characters.is-static .character-pupil,
.login-characters.is-static .character-mouth {
	transition: none;
}

@media (prefers-reduced-motion: reduce) {
	.login-character,
	.character-face,
	.character-eye,
	.character-pupil,
	.character-mouth {
		transition: none;
	}
}

@media (max-width: 1023px) {
	.login-characters {
		display: none;
	}
}
</style>
