import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import test from 'node:test'
import { createContext, Script } from 'node:vm'
import * as Vue from 'vue'
import * as calculations from '../src/utils/login-characters.mjs'

// Exercise the actual component setup with a deterministic browser clock.
// This component has no request dependency, and the harness never imports one.
const require = createRequire(import.meta.url)
const { parse, compileScript } = require('vue/compiler-sfc')
const component = await readFile(
	new URL('../src/components/auth/LoginCharacters.vue', import.meta.url),
	'utf8'
)
const { descriptor, errors } = parse(component)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'login-animation-test' })
const executable = compiled.content
	.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '')
	.replace('export default', 'globalThis.LoginCharacters =')

function eventTarget() {
	const listeners = new Map()
	const capture = (options) =>
		typeof options === 'boolean' ? options : Boolean(options?.capture)
	return {
		listeners,
		addEventListener(type, handler, options) {
			if (!listeners.has(type)) listeners.set(type, new Map())
			listeners.get(type).set(handler, capture(options))
		},
		removeEventListener(type, handler, options) {
			const bucket = listeners.get(type)
			if (bucket?.get(handler) === capture(options)) bucket.delete(handler)
			if (bucket?.size === 0) listeners.delete(type)
		},
		dispatch(type, event = {}) {
			for (const handler of [...(listeners.get(type)?.keys() || [])])
				handler(event)
		},
		count(type) {
			if (type) return listeners.get(type)?.size || 0
			return [...listeners.values()].reduce(
				(count, bucket) => count + bucket.size,
				0
			)
		},
	}
}

function createAnimation(options = {}) {
	const mounted = []
	const unmounted = []
	const timeouts = new Map()
	const frames = new Map()
	const observers = []
	const window = eventTarget()
	const document = Object.assign(eventTarget(), {
		hidden: Boolean(options.hidden),
	})
	const reducedMotion = Object.assign(eventTarget(), {
		matches: Boolean(options.reducedMotion),
	})
	const desktop = Object.assign(eventTarget(), {
		matches: options.desktop !== false,
	})
	let now = 0
	let nextId = 0
	let measurements = 0
	let rect = options.rect || { left: 100, top: 200, width: 450, height: 440 }
	const sceneElement = {
		getBoundingClientRect() {
			measurements++
			return { ...rect }
		},
	}
	window.matchMedia = (query) =>
		query.includes('prefers-reduced-motion') ? reducedMotion : desktop
	window.setTimeout = (callback, delay) => {
		const id = ++nextId
		timeouts.set(id, { callback, due: now + delay })
		return id
	}
	window.clearTimeout = (id) => timeouts.delete(id)
	window.requestAnimationFrame = (callback) => {
		const id = ++nextId
		frames.set(id, callback)
		return id
	}
	window.cancelAnimationFrame = (id) => frames.delete(id)
	class IntersectionObserver {
		constructor(callback) {
			this.callback = callback
			this.observed = null
			this.disconnected = false
			observers.push(this)
		}
		observe(element) {
			this.observed = element
		}
		disconnect() {
			this.disconnected = true
		}
		emit(isIntersecting = true, width = 450, height = 440) {
			this.callback([{ isIntersecting, intersectionRect: { width, height } }])
		}
	}
	const math = Object.create(Math)
	math.random = () => 0
	const sandbox = createContext({
		...Vue,
		...calculations,
		console,
		Math: math,
		window,
		document,
		IntersectionObserver: options.noObserver ? undefined : IntersectionObserver,
		onMounted: (callback) => mounted.push(callback),
		onBeforeUnmount: (callback) => unmounted.push(callback),
	})
	for (const [name, metadata] of Object.entries(compiled.imports)) {
		if (!(name in sandbox))
			sandbox[name] =
				metadata.source === 'vue'
					? Vue[metadata.imported]
					: calculations[metadata.imported]
	}
	new Script(executable, {
		filename: 'login-characters.setup.js',
	}).runInContext(sandbox)
	const props = Vue.reactive({
		isTyping: false,
		showPassword: false,
		passwordLength: 0,
		passwordFocused: false,
		status: 'idle',
		paused: false,
		...options.props,
	})
	const scope = Vue.effectScope()
	const state = scope.run(() =>
		sandbox.LoginCharacters.setup(props, { expose() {} })
	)
	state.scene.value = sceneElement
	let didUnmount = false
	return {
		state,
		props,
		window,
		document,
		reducedMotion,
		desktop,
		timeouts,
		frames,
		observers,
		get measurements() {
			return measurements
		},
		setRect(nextRect) {
			rect = nextRect
		},
		async mount() {
			for (const callback of mounted) await callback()
			if (options.intersects !== false) observers[0]?.emit()
			await Vue.nextTick()
		},
		unmount() {
			if (didUnmount) return
			didUnmount = true
			for (const callback of unmounted) callback()
			scope.stop()
		},
		pointer(x, y, pointerType = 'mouse') {
			window.dispatch('pointermove', { clientX: x, clientY: y, pointerType })
		},
		frame() {
			const callbacks = [...frames.values()]
			frames.clear()
			for (const callback of callbacks) callback(now)
		},
		advance(milliseconds) {
			const target = now + milliseconds
			let calls = 0
			while (true) {
				const pending = [...timeouts.entries()]
					.filter(([, timer]) => timer.due <= target)
					.sort(([idA, a], [idB, b]) => a.due - b.due || idA - idB)[0]
				if (!pending) break
				assert.ok(++calls < 1000, 'timer loop must make bounded progress')
				const [id, timer] = pending
				now = timer.due
				timeouts.delete(id)
				timer.callback()
			}
			now = target
		},
	}
}

const snapshot = (value) => JSON.parse(JSON.stringify(value))
const character = (view, key) =>
	view.state.characters.value.find((item) => item.key === key)
const pupil = (view, key) => character(view, key).eyes[0].pupilStyle.transform
const neutralPupil = 'translate(0px, 0px)'

function assertPupilsInBounds(view) {
	for (const item of view.state.characters.value) {
		for (const eye of item.eyes) {
			const match = eye.pupilStyle.transform.match(
				/^translate\(([-\d.e+]+)px, ([-\d.e+]+)px\)$/
			)
			assert.ok(match, `${eye.key} has a finite translation`)
			const [, x, y] = match.map(Number)
			assert.ok(Number.isFinite(x) && Number.isFinite(y))
			assert.ok(
				Math.hypot(x, y) <= item.maxDistance + 1e-12,
				`${eye.key} stays inside its travel radius`
			)
		}
	}
}

function assertStopped(view) {
	assert.equal(view.state.motionActive.value, false)
	assert.equal(view.window.count('pointermove'), 0)
	assert.equal(view.frames.size, 0)
	assert.equal(view.timeouts.size, 0)
	assert.deepEqual(snapshot(view.state.poses.value), {})
	assert.deepEqual(snapshot(view.state.pupilOffsets.value), {})
}

test('both authentication pages use one original four-character scene', () => {
	const view = createAnimation()
	assert.deepEqual(
		snapshot(view.state.characters.value.map((item) => item.key)),
		['purple', 'black', 'orange', 'yellow']
	)
	for (const [key, width, height, color] of [
		['purple', 180, 400, '#6C3FF5'],
		['black', 120, 310, '#2D2D2D'],
		['orange', 240, 200, '#FF9B6B'],
		['yellow', 140, 230, '#E8D754'],
	]) {
		const item = character(view, key)
		assert.equal(item.bodyStyle.width, `${width}px`)
		assert.equal(item.bodyStyle.height, `${height}px`)
		assert.equal(item.bodyStyle.backgroundColor, color)
		assert.equal(item.eyes.length, 2)
		assert.equal(item.mouth.visible, key !== 'black')
	}
	assert.doesNotMatch(component, /\b(?:videoDefinitions|isVideo)\b/)
	assert.match(descriptor.template.content, /aria-hidden="true"/)
	assert.match(
		descriptor.styles[0].content,
		/\.login-characters\s*\{[^}]*width:\s*450px;[^}]*height:\s*440px;/
	)
	view.unmount()
})

test('motion starts only when mounted, visible, intersecting and allowed by preferences', async () => {
	for (const options of [
		{ hidden: true },
		{ desktop: false },
		{ reducedMotion: true },
		{ props: { paused: true } },
		{ intersects: false },
	]) {
		const view = createAnimation(options)
		assertStopped(view)
		await view.mount()
		assertStopped(view)
		view.unmount()
	}
	const view = createAnimation({ noObserver: true })
	await view.mount()
	assert.equal(view.state.motionActive.value, true)
	assert.equal(view.window.count('pointermove'), 1)
	assert.equal(view.timeouts.size, 2)
	assert.equal(
		view.frames.size,
		0,
		'idle animation needs no continuous RAF loop'
	)
	view.unmount()
})

test('pointer events share one frame and read only the stable scene geometry', async () => {
	const view = createAnimation()
	await view.mount()
	view.pointer(300, 400)
	view.pointer(450, 350)
	view.pointer(500, 500)
	assert.equal(view.frames.size, 1)
	const before = view.measurements
	view.frame()
	assert.equal(view.measurements - before, 1)
	assert.equal(view.frames.size, 0)
	assert.notDeepEqual(snapshot(view.state.poses.value), {})
	assertPupilsInBounds(view)
	const first = snapshot(view.state.poses.value)
	view.pointer(500, 500)
	view.frame()
	assert.deepEqual(
		snapshot(view.state.poses.value),
		first,
		'repeating a pointer cannot feed a transformed body rect back into its pose'
	)
	view.unmount()
})

test('touch and invalid coordinates neither schedule frames nor overwrite a valid pointer', async () => {
	const view = createAnimation()
	await view.mount()
	view.pointer(300, 400, 'touch')
	view.pointer(NaN, 400)
	view.pointer(300, Infinity)
	assert.equal(view.frames.size, 0)
	view.pointer(350, 450)
	view.frame()
	const first = snapshot(view.state.poses.value)
	view.pointer(Infinity, 450)
	view.pointer(350, NaN)
	view.pointer(900, 800, 'touch')
	assert.equal(view.frames.size, 0)
	assert.deepEqual(snapshot(view.state.poses.value), first)
	view.unmount()
})

test('equivalent pointer positions produce the same pose after scene scaling and translation', async () => {
	const view = createAnimation()
	await view.mount()
	view.pointer(400, 400)
	view.frame()
	const firstPoses = snapshot(view.state.poses.value)
	const firstCharacters = snapshot(view.state.characters.value)
	view.setRect({ left: 800, top: 100, width: 225, height: 220 })
	view.pointer(950, 200)
	view.frame()
	assert.deepEqual(snapshot(view.state.poses.value), firstPoses)
	assert.deepEqual(snapshot(view.state.characters.value), firstCharacters)
	view.unmount()
})

test('zero-sized or invalid scene geometry resets prior gaze instead of emitting invalid transforms', async () => {
	const view = createAnimation()
	await view.mount()
	const valid = { left: 100, top: 200, width: 450, height: 440 }
	for (const invalid of [
		{ ...valid, width: 0 },
		{ ...valid, height: 0 },
		{ ...valid, width: -1 },
		{ ...valid, left: NaN },
		{ ...valid, top: Infinity },
		{ ...valid, width: Infinity },
	]) {
		view.setRect(valid)
		view.pointer(500, 500)
		view.frame()
		assert.notDeepEqual(snapshot(view.state.poses.value), {})
		view.setRect(invalid)
		view.window.dispatch('resize')
		view.frame()
		assert.deepEqual(snapshot(view.state.poses.value), {})
		assert.deepEqual(snapshot(view.state.pupilOffsets.value), {})
		for (const item of view.state.characters.value) {
			assert.doesNotMatch(item.bodyStyle.transform, /NaN|Infinity/)
			assert.doesNotMatch(item.faceStyle.transform, /NaN|Infinity/)
		}
		assertPupilsInBounds(view)
	}
	view.unmount()
})

test('pause, hidden tabs, reduced motion, small screens and zero intersection cancel every animation resource', async () => {
	const stoppingConditions = [
		async (view) => {
			view.props.paused = true
			await Vue.nextTick()
		},
		async (view) => {
			view.document.hidden = true
			view.document.dispatch('visibilitychange')
		},
		async (view) => {
			view.reducedMotion.matches = true
			view.reducedMotion.dispatch('change')
		},
		async (view) => {
			view.desktop.matches = false
			view.desktop.dispatch('change')
		},
		async (view) => {
			view.observers[0].emit(false)
		},
		async (view) => {
			view.observers[0].emit(true, 0, 440)
		},
		async (view) => {
			view.observers[0].emit(true, 450, 0)
		},
	]
	for (const stop of stoppingConditions) {
		const view = createAnimation({
			props: { isTyping: true, showPassword: true, passwordLength: 3 },
		})
		await view.mount()
		view.pointer(500, 500)
		view.frame()
		view.pointer(600, 500)
		assert.equal(view.frames.size, 1)
		await stop(view)
		assertStopped(view)
		view.advance(10000)
		assertStopped(view)
		view.unmount()
	}
})

test('resuming motion creates one listener and clears stale pointer positions', async () => {
	const view = createAnimation()
	await view.mount()
	view.pointer(500, 500)
	view.frame()
	for (let repeat = 0; repeat < 3; repeat++) {
		view.props.paused = true
		await Vue.nextTick()
		assertStopped(view)
		view.props.paused = false
		await Vue.nextTick()
		assert.equal(view.window.count('pointermove'), 1)
		assert.equal(view.timeouts.size, 2)
		assert.equal(view.frames.size, 0)
		assert.deepEqual(snapshot(view.state.poses.value), {})
	}
	view.unmount()
})

test('pointer leave and window blur reset gaze and resize or scroll refresh one pending frame', async () => {
	const view = createAnimation()
	await view.mount()
	for (const [target, event] of [
		[view.document, 'pointerleave'],
		[view.window, 'blur'],
	]) {
		view.pointer(500, 500)
		view.frame()
		view.pointer(600, 500)
		target.dispatch(event)
		assert.equal(view.frames.size, 0)
		assert.deepEqual(snapshot(view.state.poses.value), {})
		assert.deepEqual(snapshot(view.state.pupilOffsets.value), {})
		view.window.dispatch('resize')
		view.document.dispatch('scroll')
		assert.equal(
			view.frames.size,
			0,
			'reset pointer must stay neutral during layout changes'
		)
	}
	view.pointer(500, 500)
	view.frame()
	const initial = snapshot(view.state.poses.value)
	view.setRect({ left: 400, top: 300, width: 450, height: 440 })
	view.window.dispatch('resize')
	view.document.dispatch('scroll')
	assert.equal(view.frames.size, 1)
	view.frame()
	assert.notDeepEqual(snapshot(view.state.poses.value), initial)
	view.unmount()
})

test('focused password bends the columns and visible password overrides all typing poses', async () => {
	const view = createAnimation()
	await view.mount()
	view.props.isTyping = true
	await Vue.nextTick()
	assert.equal(character(view, 'purple').bodyStyle.height, '440px')
	assertPupilsInBounds(view)
	view.advance(800)
	assert.equal(character(view, 'purple').bodyStyle.height, '440px')
	view.props.isTyping = false
	view.props.passwordLength = 3
	view.props.passwordFocused = true
	await Vue.nextTick()
	assert.equal(character(view, 'purple').bodyStyle.height, '440px')
	assertPupilsInBounds(view)
	view.props.showPassword = true
	view.props.isTyping = true
	await Vue.nextTick()
	assert.equal(
		character(view, 'purple').bodyStyle.height,
		'400px',
		'showing a password takes precedence over typing height'
	)
	for (const item of view.state.characters.value)
		assert.match(
			item.bodyStyle.transform,
			/^skewX\(0deg\)(?: translateX\(0px\))?$/
		)
	assertPupilsInBounds(view)
	view.props.passwordLength = 0
	view.props.isTyping = false
	view.props.passwordFocused = false
	await Vue.nextTick()
	for (const item of view.state.characters.value)
		assert.equal(item.eyes[0].pupilStyle.transform, neutralPupil)
	assert.equal(character(view, 'purple').bodyStyle.height, '400px')
	view.unmount()
})

test('visible-password privacy never peeks, and returns to tracking on hiding or clearing', async () => {
	const view = createAnimation({
		props: { showPassword: true, passwordLength: 1 },
	})
	await view.mount()
	const lookingAway = pupil(view, 'purple')
	view.advance(1500)
	view.props.passwordLength = 5
	await Vue.nextTick()
	view.advance(500)
	assert.equal(
		pupil(view, 'purple'),
		lookingAway,
		'editing a visible password keeps the privacy gaze'
	)
	assertPupilsInBounds(view)
	view.advance(800)
	assert.equal(pupil(view, 'purple'), lookingAway)
	view.props.showPassword = false
	await Vue.nextTick()
	view.advance(10000)
	assert.equal(pupil(view, 'purple'), neutralPupil)
	view.props.showPassword = true
	await Vue.nextTick()
	view.advance(2000)
	assert.equal(pupil(view, 'purple'), lookingAway)
	view.props.passwordLength = 0
	await Vue.nextTick()
	view.advance(10000)
	assert.equal(pupil(view, 'purple'), neutralPupil)
	view.unmount()
})

test('password focus creates curved outlines before typing, and blur restores the idle columns', async () => {
	const view = createAnimation()
	await view.mount()
	const resting = character(view, 'purple').outline
	view.props.passwordFocused = true
	await Vue.nextTick()
	assert.equal(view.state.interactionMode.value, 'password')
	assert.notEqual(character(view, 'purple').outline, resting)
	assert.match(character(view, 'purple').faceStyle.transform, /rotate\(32deg\)/)
	assert.equal(view.state.lookingAtEachOther.value, false)
	view.props.passwordLength = 8
	await Vue.nextTick()
	assert.equal(view.state.interactionMode.value, 'password')
	view.props.passwordFocused = false
	await Vue.nextTick()
	assert.equal(view.state.interactionMode.value, 'idle')
	assert.equal(character(view, 'purple').outline, resting)
	assert.equal(character(view, 'purple').bodyStyle.height, '400px')
	view.unmount()
})

test('real feedback changes expressions, while visible-password privacy remains highest priority', async () => {
	const view = createAnimation()
	await view.mount()
	const happy = character(view, 'orange').mouth.path
	view.props.status = 'error'
	await Vue.nextTick()
	assert.equal(view.state.interactionMode.value, 'error')
	assert.notEqual(character(view, 'orange').mouth.path, happy)
	assert.match(character(view, 'purple').outline, /L -22 172/)
	view.props.showPassword = true
	view.props.passwordLength = 1
	await Vue.nextTick()
	assert.equal(view.state.interactionMode.value, 'privacy')
	assert.ok(character(view, 'orange').eyes.every((eye) => eye.closed))
	assert.ok(
		character(view, 'orange').eyes.every((eye) => eye.pupilStyle.opacity === 0)
	)
	view.props.showPassword = false
	view.props.status = 'success'
	await Vue.nextTick()
	assert.equal(view.state.interactionMode.value, 'success')
	assert.equal(character(view, 'orange').mouth.path, happy)
	view.props.status = 'busy'
	await Vue.nextTick()
	assert.equal(view.state.interactionMode.value, 'busy')
	assertPupilsInBounds(view)
	view.unmount()
})

test('typing glances end after 800ms and pose transitions return to pointer timing after 550ms', async () => {
	const view = createAnimation()
	await view.mount()
	view.props.isTyping = true
	await Vue.nextTick()
	assert.equal(view.state.lookingAtEachOther.value, true)
	assert.equal(view.state.changingPose.value, true)
	view.advance(549)
	assert.equal(view.state.changingPose.value, true)
	view.advance(1)
	assert.equal(view.state.changingPose.value, false)
	view.advance(249)
	assert.equal(view.state.lookingAtEachOther.value, true)
	view.advance(1)
	assert.equal(view.state.lookingAtEachOther.value, false)
	assert.equal(
		view.state.changingPose.value,
		true,
		'ending a glance returns gently to the pointer pose'
	)
	view.advance(549)
	assert.equal(view.state.changingPose.value, true)
	view.advance(1)
	assert.equal(view.state.changingPose.value, false)
	view.props.isTyping = false
	await Vue.nextTick()
	view.props.isTyping = true
	await Vue.nextTick()
	assert.equal(view.state.lookingAtEachOther.value, true)
	view.props.paused = true
	await Vue.nextTick()
	assert.equal(view.state.lookingAtEachOther.value, false)
	assert.equal(view.state.changingPose.value, false)
	assertStopped(view)
	view.unmount()
})

test('random blinks close only purple and black eyes for 150ms and recur without multiplying timers', async () => {
	const view = createAnimation()
	await view.mount()
	view.advance(2999)
	assert.equal(character(view, 'purple').eyes[0].style.height, '18px')
	assert.equal(character(view, 'black').eyes[0].style.height, '16px')
	view.advance(1)
	for (const key of ['purple', 'black']) {
		for (const eye of character(view, key).eyes) {
			assert.equal(eye.style.height, '2px')
			assert.equal(eye.pupilStyle.opacity, 0)
		}
	}
	assert.equal(character(view, 'orange').eyes[0].style.height, '12px')
	assert.equal(character(view, 'yellow').eyes[0].style.height, '12px')
	view.advance(149)
	assert.equal(character(view, 'purple').eyes[0].style.height, '2px')
	view.advance(1)
	assert.equal(character(view, 'purple').eyes[0].style.height, '18px')
	assert.equal(character(view, 'black').eyes[0].style.height, '16px')
	assert.equal(view.timeouts.size, 2)
	view.advance(3000)
	assert.equal(character(view, 'purple').eyes[0].style.height, '2px')
	assert.equal(view.timeouts.size, 2)
	view.unmount()
	assertStopped(view)
})

test('unmount cancels pending work and unregisters every global listener and observer', async () => {
	const view = createAnimation({
		props: { isTyping: true, showPassword: true, passwordLength: 3 },
	})
	await view.mount()
	view.pointer(500, 500)
	assert.equal(view.frames.size, 1)
	const staleFrame = [...view.frames.values()][0]
	const staleTimers = [...view.timeouts.values()].map((timer) => timer.callback)
	view.unmount()
	assertStopped(view)
	assert.equal(view.window.count(), 0)
	assert.equal(view.document.count(), 0)
	assert.equal(view.reducedMotion.count(), 0)
	assert.equal(view.desktop.count(), 0)
	assert.equal(view.observers[0].disconnected, true)
	staleFrame()
	for (const callback of staleTimers) callback()
	view.observers[0].emit()
	assertStopped(view)
	assert.equal(view.window.count(), 0)
	assert.equal(view.document.count(), 0)
})
