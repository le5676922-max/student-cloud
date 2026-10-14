import assert from 'node:assert/strict'
import test from 'node:test'
import {
	characterPose,
	characterOutline,
	limitPupilOffset,
	loginInteractionMode,
	pupilOffset,
} from '../src/utils/login-characters.mjs'

test('video interaction priority is privacy, result, focus, then cursor tracking', () => {
	assert.equal(loginInteractionMode(), 'idle')
	assert.equal(loginInteractionMode({ isTyping: true }), 'account')
	assert.equal(
		loginInteractionMode({ isTyping: true, passwordFocused: true }),
		'password'
	)
	for (const status of ['error', 'success', 'busy']) {
		assert.equal(
			loginInteractionMode({ status, passwordFocused: true }),
			status
		)
		assert.equal(
			loginInteractionMode({
				status,
				passwordFocused: true,
				showPassword: true,
				passwordLength: 3,
			}),
			'privacy'
		)
	}
	for (const passwordLength of [0, -1, NaN, Infinity])
		assert.equal(
			loginInteractionMode({ showPassword: true, passwordLength }),
			'idle'
		)
	assert.equal(
		loginInteractionMode({ passwordLength: 8 }),
		'idle',
		'a filled, blurred password does not keep characters leaning'
	)
	assert.equal(loginInteractionMode({ status: 'unknown' }), 'idle')
})

test('curved SVG silhouettes stay finite and have no dependency on credential values', () => {
	for (const key of ['purple', 'black']) {
		const resting = characterOutline(key, 440)
		const focused = characterOutline(key, 440, 'password')
		assert.notEqual(resting, focused)
		for (const mode of [
			'idle',
			'account',
			'password',
			'privacy',
			'error',
			'busy',
			'success',
		]) {
			const outline = characterOutline(key, 440, mode)
			assert.match(outline, /^M /)
			assert.match(outline, / Z$/)
			assert.doesNotMatch(outline, /NaN|Infinity|undefined/)
		}
	}
	assert.equal(characterOutline('orange', 200), null)
	for (const height of [0, -1, NaN, Infinity])
		assert.equal(characterOutline('purple', height, 'password'), null)
})

test('face movement and tilt stop at the reference limits outside the viewport', () => {
	const bounds = { left: 100, top: 200, width: 180, height: 400 }
	assert.deepEqual(characterPose(5000, 5000, bounds), {
		faceX: 15,
		faceY: 10,
		bodySkew: -6,
	})
	assert.deepEqual(characterPose(-5000, -5000, bounds), {
		faceX: -15,
		faceY: -10,
		bodySkew: 6,
	})
})

test('pupils preserve cursor direction while remaining inside their travel radius', () => {
	const bounds = { left: 0, top: 0, width: 18, height: 18 }
	const offset = pupilOffset(12, 13, bounds, 2)
	assert.ok(Math.abs(offset.x - 1.2) < 1e-12)
	assert.ok(Math.abs(offset.y - 1.6) < 1e-12)
	assert.ok(Math.abs(Math.hypot(offset.x, offset.y) - 2) < 1e-12)
	assert.deepEqual(pupilOffset(10, 10, bounds, 5), { x: 1, y: 1 })
})

test('a cursor at the pupil center produces a stable neutral gaze', () => {
	assert.deepEqual(
		pupilOffset(9, 9, { left: 0, top: 0, width: 18, height: 18 }),
		{ x: 0, y: 0 }
	)
})

test('hidden or unmeasured shapes and invalid pointer coordinates stay neutral', () => {
	const hiddenBounds = { left: 0, top: 0, width: 0, height: 0 }
	assert.deepEqual(characterPose(100, 200, hiddenBounds), {
		faceX: 0,
		faceY: 0,
		bodySkew: 0,
	})
	assert.deepEqual(pupilOffset(100, 200, hiddenBounds), { x: 0, y: 0 })
	assert.deepEqual(
		pupilOffset(NaN, 2, { left: 0, top: 0, width: 18, height: 18 }),
		{ x: 0, y: 0 }
	)
})

test('invalid bounds and non-finite pointer coordinates cannot produce an unsafe pose', () => {
	const neutral = { faceX: 0, faceY: 0, bodySkew: 0 }
	const bounds = { left: 100, top: 200, width: 180, height: 400 }
	for (const invalidBounds of [
		undefined,
		null,
		{},
		{ ...bounds, width: -1 },
		{ ...bounds, height: 0 },
		{ ...bounds, left: NaN },
		{ ...bounds, top: Infinity },
		{ ...bounds, width: Infinity },
		{ ...bounds, height: NaN },
	]) {
		assert.deepEqual(characterPose(300, 400, invalidBounds), neutral)
		assert.deepEqual(pupilOffset(300, 400, invalidBounds), { x: 0, y: 0 })
	}
	for (const coordinate of [NaN, Infinity, -Infinity, undefined]) {
		assert.deepEqual(characterPose(coordinate, 400, bounds), neutral)
		assert.deepEqual(characterPose(300, coordinate, bounds), neutral)
		assert.deepEqual(pupilOffset(coordinate, 400, bounds), { x: 0, y: 0 })
		assert.deepEqual(pupilOffset(300, coordinate, bounds), { x: 0, y: 0 })
	}
	for (const maxDistance of [0, -1, NaN, Infinity]) {
		assert.deepEqual(pupilOffset(300, 400, bounds, maxDistance), {
			x: 0,
			y: 0,
		})
	}
})

test('forced pupil offsets use the same radial limit as pointer tracking', () => {
	assert.deepEqual(limitPupilOffset({ x: 1, y: -2 }, 5), { x: 1, y: -2 })
	assert.deepEqual(limitPupilOffset({ x: 0, y: 0 }, 5), { x: 0, y: 0 })
	const offset = limitPupilOffset({ x: 4, y: 5 }, 5)
	assert.ok(Math.abs(Math.hypot(offset.x, offset.y) - 5) < 1e-12)
	assert.ok(Math.abs(offset.x / offset.y - 4 / 5) < 1e-12)
	const blackLook = limitPupilOffset({ x: -4, y: -4 }, 4)
	assert.ok(Math.abs(Math.hypot(blackLook.x, blackLook.y) - 4) < 1e-12)
	assert.ok(blackLook.x < 0 && blackLook.y < 0)
	for (const invalidOffset of [
		null,
		undefined,
		{},
		{ x: NaN, y: 1 },
		{ x: 1, y: Infinity },
	]) {
		assert.deepEqual(limitPupilOffset(invalidOffset, 5), { x: 0, y: 0 })
	}
	for (const maxDistance of [0, -1, NaN, Infinity])
		assert.deepEqual(limitPupilOffset({ x: 4, y: 5 }, maxDistance), {
			x: 0,
			y: 0,
		})
})
