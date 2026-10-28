import assert from 'node:assert/strict'
import test from 'node:test'
import {
	createPrivateModelRenderer,
	privateModelMatrices,
} from '../src/utils/private-model-renderer.mjs'
import { parsePrivateModelInWorker } from '../src/utils/private-model-worker.mjs'

const model = () => ({
	positions: new Float32Array([-1, -1, 0, 1, -1, 0, -1, 1, 0]),
	normals: new Float32Array([0, 0, 1, 0, 0, 1, 0, 0, 1]),
	triangleCount: 1,
	vertexCount: 3,
})
function gpu({ shaderFailure = false, attributeFailure = false } = {}) {
	const calls = [],
		gl = {
			VERTEX_SHADER: 1,
			FRAGMENT_SHADER: 2,
			COMPILE_STATUS: 3,
			LINK_STATUS: 4,
			ARRAY_BUFFER: 5,
			STATIC_DRAW: 6,
			FLOAT: 7,
			DEPTH_TEST: 8,
			TRIANGLES: 9,
			COLOR_BUFFER_BIT: 16,
			DEPTH_BUFFER_BIT: 32,
		}
	for (const name of [
		'shaderSource',
		'compileShader',
		'attachShader',
		'linkProgram',
		'useProgram',
		'bindBuffer',
		'bufferData',
		'enableVertexAttribArray',
		'vertexAttribPointer',
		'enable',
		'clearColor',
		'viewport',
		'clear',
		'uniformMatrix4fv',
		'uniformMatrix3fv',
		'uniform3fv',
		'drawArrays',
		'deleteBuffer',
		'deleteShader',
		'deleteProgram',
	])
		gl[name] = (...args) => calls.push({ name, args })
	gl.createShader = () => ({ kind: 'shader' })
	gl.getShaderParameter = () => !shaderFailure
	gl.createProgram = () => ({ kind: 'program' })
	gl.getProgramParameter = () => true
	gl.createBuffer = () => ({ kind: 'buffer' })
	gl.getAttribLocation = () => (attributeFailure ? -1 : 0)
	gl.getUniformLocation = (_program, name) => ({ name })
	return { calls, canvas: { getContext: () => gl }, gl }
}
test('geometry rendering draws only when requested and releases buffers, shaders and program once', () => {
	const h = gpu(),
		renderer = createPrivateModelRenderer(h.canvas, model())
	assert.equal(h.calls.filter((call) => call.name === 'drawArrays').length, 0)
	renderer.resize(1000, 500, 3)
	renderer.draw({ yaw: 0, pitch: 0, zoom: 1 })
	renderer.draw({ yaw: 0.2, pitch: 0, zoom: 1 })
	assert.equal(h.canvas.width, 1600)
	assert.equal(h.canvas.height, 1000)
	assert.equal(h.calls.filter((call) => call.name === 'drawArrays').length, 2)
	assert.deepEqual(
		h.calls.filter((call) => call.name === 'drawArrays')[0].args,
		[h.gl.TRIANGLES, 0, 3]
	)
	const matrices = h.calls.filter((call) => call.name === 'uniformMatrix4fv')
	assert.notDeepEqual([...matrices[0].args[2]], [...matrices[1].args[2]])
	renderer.dispose()
	renderer.dispose()
	renderer.draw({})
	assert.equal(h.calls.filter((call) => call.name === 'deleteBuffer').length, 2)
	assert.equal(h.calls.filter((call) => call.name === 'deleteShader').length, 2)
	assert.equal(
		h.calls.filter((call) => call.name === 'deleteProgram').length,
		1
	)
	assert.equal(h.calls.filter((call) => call.name === 'drawArrays').length, 2)
})
test('failed shader and attribute initialization releases every allocated GPU resource', () => {
	const shader = gpu({ shaderFailure: true })
	assert.throws(() => createPrivateModelRenderer(shader.canvas, model()))
	assert.equal(
		shader.calls.filter((call) => call.name === 'deleteShader').length,
		1
	)
	const attribute = gpu({ attributeFailure: true })
	assert.throws(() => createPrivateModelRenderer(attribute.canvas, model()))
	assert.equal(
		attribute.calls.filter((call) => call.name === 'deleteBuffer').length,
		1
	)
	assert.equal(
		attribute.calls.filter((call) => call.name === 'deleteProgram').length,
		1
	)
})
test('unsupported WebGL and malformed geometry fail before allocating GPU state', () => {
	assert.throws(() =>
		createPrivateModelRenderer({ getContext: () => null }, model())
	)
	const h = gpu(),
		bad = model()
	bad.positions[0] = NaN
	assert.throws(() => createPrivateModelRenderer(h.canvas, bad))
	assert.equal(h.calls.length, 0)
	const matrices = privateModelMatrices(
		{ yaw: Infinity, pitch: NaN, zoom: -1 },
		NaN
	)
	assert.ok(matrices.matrix.every(Number.isFinite))
	assert.ok(matrices.rotation.every(Number.isFinite))
})
test('model worker completion, abort, timeout and malformed result terminate the owned worker', async () => {
	for (const outcome of ['success', 'abort', 'timeout', 'invalid']) {
		let timer,
			terminated = 0
		const worker = {
				postMessage(value, transfer) {
					this.value = value
					this.transfer = transfer
				},
				terminate() {
					terminated++
				},
			},
			controller = new AbortController(),
			buffer = new ArrayBuffer(8)
		const pending = parsePrivateModelInWorker(
			buffer,
			'obj',
			controller.signal,
			{
				createWorker: () => worker,
				setTimer: (callback) => {
					timer = callback
					return 1
				},
				clearTimer: () => {},
			}
		)
		assert.deepEqual(worker.transfer, [buffer])
		if (outcome === 'abort') controller.abort()
		else if (outcome === 'timeout') timer()
		else if (outcome === 'invalid')
			worker.onmessage({
				data: { ok: true, geometry: { ...model(), triangleCount: 2 } },
			})
		else worker.onmessage({ data: { ok: true, geometry: model() } })
		if (outcome === 'success') assert.equal((await pending).triangleCount, 1)
		else await assert.rejects(pending)
		worker.onmessage({ data: { ok: true, geometry: model() } })
		assert.equal(terminated, 1)
	}
})
