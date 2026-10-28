import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import {
	parse,
	compileScript,
	compileTemplate,
	compileStyle,
} from 'vue/compiler-sfc'

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
	url: 'http://127.0.0.1:5173/',
})
for (const key of [
	'window',
	'document',
	'Document',
	'Node',
	'Element',
	'HTMLElement',
	'SVGElement',
	'Event',
	'KeyboardEvent',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
const observers = []
globalThis.ResizeObserver = class {
	constructor(callback) {
		this.callback = callback
		this.disconnected = 0
		observers.push(this)
	}
	observe() {}
	disconnect() {
		this.disconnected++
	}
}
after(() => {
	dom.window.close()
	delete globalThis.ResizeObserver
})
const source = await readFile(
		new URL(
			'../src/components/file/preview/PrivateModelPreview.vue',
			import.meta.url
		),
		'utf8'
	),
	descriptor = parse(source).descriptor
const compiled = compileScript(descriptor, { id: 'private-model-runtime' }),
	template = compileTemplate({
		source: descriptor.template.content,
		filename: 'PrivateModelPreview.vue',
		id: 'private-model-runtime',
		compilerOptions: { bindingMetadata: compiled.bindings },
	})
assert.deepEqual(template.errors, [])
const transform = (code) =>
	code.replace(
		/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
		(_match, names, module) =>
			`const {${names.replace(/\s+as\s+/g, ': ')}}=modules[${JSON.stringify(
				module
			)}]`
	)
const geometry = (vertexCount = 3) => ({
	positions: new Float32Array([-1, -1, 0, 1, -1, 0, -1, 1, 0]),
	normals: new Float32Array([0, 0, 1, 0, 0, 1, 0, 0, 1]),
	triangleCount: 1,
	vertexCount,
})
const flush = async () => {
	for (let i = 0; i < 8; i++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
async function mount({ gpuFailure = false } = {}) {
	const requests = [],
		renderers = [],
		startObserver = observers.length,
		props = vue.reactive({ buffer: new ArrayBuffer(16), format: 'obj' })
	const modules = {
		vue,
		'~/utils/private-model-worker.mjs': {
			parsePrivateModelInWorker(buffer, format, signal) {
				return new Promise((resolve) =>
					requests.push({ buffer, format, signal, resolve })
				)
			},
		},
		'~/utils/private-model-renderer.mjs': {
			createPrivateModelRenderer(canvas, model) {
				if (gpuFailure) throw new Error('private driver internals')
				const renderer = {
					canvas,
					model,
					draws: [],
					sizes: [],
					disposed: 0,
					draw(value) {
						this.draws.push(value)
					},
					resize(...size) {
						this.sizes.push(size)
					},
					dispose() {
						this.disposed++
					},
				}
				renderers.push(renderer)
				return renderer
			},
		},
	}
	const component = new Function(
		'modules',
		transform(compiled.content).replace('export default', 'return')
	)(modules)
	component.render = new Function(
		'modules',
		transform(template.code).replace(
			'export function render',
			'return function render'
		)
	)(modules)
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp({ render: () => vue.h(component, { ...props }) })
	app.mount(host)
	await flush()
	return {
		host,
		props,
		requests,
		renderers,
		get observers() {
			return observers.slice(startObserver)
		},
		close() {
			app.unmount()
			host.remove()
		},
	}
}
const click = async (host, label) => {
	Array.from(host.querySelectorAll('button'))
		.find((button) => button.textContent.trim() === label)
		.click()
	await flush()
}
test('model UI renders on demand, supports buttons and keyboard, and releases GPU/observer on unmount', async () => {
	const h = await mount()
	try {
		assert.ok(
			Array.from(h.host.querySelectorAll('button')).every(
				(button) => button.disabled
			)
		)
		assert.notEqual(h.requests[0].buffer, h.props.buffer)
		assert.equal(h.props.buffer.byteLength, 16)
		h.requests[0].resolve(geometry())
		await flush()
		assert.equal(h.renderers.length, 1)
		const renderer = h.renderers[0]
		assert.equal(renderer.draws.length, 1)
		await click(h.host, '向右旋转')
		assert.ok(renderer.draws.at(-1).yaw > renderer.draws[0].yaw)
		h.host
			.querySelector('canvas')
			.dispatchEvent(new KeyboardEvent('keydown', { key: '+', bubbles: true }))
		await flush()
		assert.ok(renderer.draws.at(-1).zoom > 1)
		await click(h.host, '还原')
		assert.equal(renderer.draws.at(-1).zoom, 1)
		assert.match(h.host.textContent, /3 个顶点/)
		h.close()
		assert.equal(renderer.disposed, 1)
		assert.equal(h.observers[0].disconnected, 1)
		assert.equal(h.requests[0].signal.aborted, true)
	} catch (error) {
		h.close()
		throw error
	}
})
test('changing or closing a model aborts old parse and ignores its late geometry', async () => {
	const h = await mount(),
		original = h.requests[0]
	h.props.buffer = new ArrayBuffer(24)
	await flush()
	assert.equal(original.signal.aborted, true)
	h.requests[1].resolve(geometry(4))
	await flush()
	assert.equal(h.renderers.length, 1)
	original.resolve(geometry(3))
	await flush()
	assert.match(h.host.textContent, /4 个顶点/)
	assert.equal(h.renderers.length, 1)
	h.close()
	assert.equal(h.renderers[0].disposed, 1)
	const closed = await mount()
	closed.close()
	assert.equal(closed.requests[0].signal.aborted, true)
	closed.requests[0].resolve(geometry())
	await flush()
	assert.equal(closed.renderers.length, 0)
})
test('unsupported graphics shows a recoverable local error with disabled controls', async () => {
	const h = await mount({ gpuFailure: true })
	try {
		h.requests[0].resolve(geometry())
		await flush()
		assert.match(h.host.querySelector('[role="alert"]').textContent, /浏览器/)
		assert.doesNotMatch(h.host.textContent, /private driver/)
		assert.ok(
			Array.from(h.host.querySelectorAll('button')).every(
				(button) => button.disabled
			)
		)
	} finally {
		h.close()
	}
})
test('model viewport styles compile and rendering has no animation polling loop', () => {
	assert.doesNotMatch(source, /requestAnimationFrame|setInterval/)
	for (const style of descriptor.styles)
		assert.deepEqual(
			compileStyle({
				source: style.content,
				filename: 'PrivateModelPreview.vue',
				id: 'model-style',
				scoped: true,
			}).errors,
			[]
		)
})
