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
])
	globalThis[key] = dom.window[key]
const vue = await import('vue'),
	hljs = (await import('highlight.js/lib/core')).default,
	DOMPurify = (await import('dompurify')).default
after(() => dom.window.close())
const source = await readFile(
	new URL(
		'../src/components/file/preview/PrivateTextPreview.vue',
		import.meta.url
	),
	'utf8'
)
const descriptor = parse(source).descriptor,
	script = compileScript(descriptor, { id: 'private-text-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'PrivateTextPreview.vue',
	id: 'private-text-runtime',
	compilerOptions: { bindingMetadata: script.bindings },
})
assert.deepEqual(template.errors, [])
const renderInputs = []
const modules = {
	vue,
	dompurify: DOMPurify,
	'highlight.js/lib/core': {
		registerLanguage: (...args) => hljs.registerLanguage(...args),
		highlight: (text, options) => {
			renderInputs.push(text.length)
			return hljs.highlight(text, options)
		},
	},
}
for (const name of [
	'javascript',
	'typescript',
	'java',
	'python',
	'css',
	'json',
	'xml',
	'bash',
	'sql',
	'cpp',
	'yaml',
])
	modules[`highlight.js/lib/languages/${name}`] = (
		await import(`highlight.js/lib/languages/${name}`)
	).default
const transform = (code) =>
	code
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_match, names, module) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}}=modules[${JSON.stringify(
					module
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_match, name, module) =>
				`const ${name}=modules[${JSON.stringify(module)}]`
		)
const Component = new Function(
	'modules',
	transform(script.content).replace('export default', 'return')
)(modules)
Component.render = new Function(
	'modules',
	transform(template.code).replace(
		'export function render',
		'return function render'
	)
)(modules)
const flush = async () => {
	for (let i = 0; i < 4; i++) await vue.nextTick()
}
async function mount(text, name) {
	renderInputs.length = 0
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(Component, { text, name })
	app.mount(host)
	await flush()
	return {
		host,
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

test('code preview highlights a known language as inert spans without interpreting HTML or loading resources', async () => {
	const text =
		'<img src="https://external.invalid/pixel" onerror="alert(1)"><script>alert(1)</script>'
	const app = await mount(text, 'example.html')
	try {
		assert.ok(app.host.querySelector('span.hljs-name'))
		assert.equal(app.host.querySelector('img,script,iframe,a'), null)
		assert.equal(app.host.querySelector('pre').textContent, text)
		assert.ok(renderInputs.length)
	} finally {
		app.close()
	}
})
test('unknown suffixes remain complete plain text and do not trigger language autodetection', async () => {
	const text = 'raw <svg onload=alert(1)> preserved',
		app = await mount(text, 'data.unknown')
	try {
		assert.equal(app.host.querySelector('pre').textContent, text)
		assert.equal(app.host.querySelector('pre span'), null)
		assert.equal(renderInputs.length, 0)
	} finally {
		app.close()
	}
})
test('code is bounded before highlighting and full source plus wrap toggle remain available', async () => {
	const text = '// ' + 'A'.repeat(100000) + '\nconst FULL_SOURCE_END = 2;',
		app = await mount(text, 'large.js')
	try {
		assert.ok(renderInputs.every((length) => length <= 100000))
		assert.doesNotMatch(
			app.host.querySelector('pre').textContent,
			/FULL_SOURCE_END/
		)
		assert.match(app.host.textContent, /前.*10.*万/)
		await click(app.host, '完整源码')
		assert.equal(app.host.querySelector('pre').textContent, text)
		await click(app.host, '自动换行')
		assert.equal(app.host.querySelector('pre').style.whiteSpace, 'pre')
	} finally {
		app.close()
	}
})
test('highlight styles compile using existing theme tokens', () => {
	for (const style of descriptor.styles)
		assert.deepEqual(
			compileStyle({
				source: style.content,
				filename: 'PrivateTextPreview.vue',
				id: 'private-text-style',
				scoped: true,
			}).errors,
			[]
		)
})
