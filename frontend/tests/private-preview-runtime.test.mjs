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
import { createPrivatePreviewSession } from '../src/utils/private-file-preview.mjs'

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
	url: 'http://127.0.0.1:5173/',
})
for (const key of [
	'window',
	'document',
	'Document',
	'DOMParser',
	'Node',
	'Element',
	'HTMLElement',
	'SVGElement',
	'Event',
	'KeyboardEvent',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
dom.window.HTMLMediaElement.prototype.pause = () => {}
after(() => dom.window.close())
const source = await readFile(
	new URL(
		'../src/components/file/preview/PrivateFilePreview.vue',
		import.meta.url
	),
	'utf8'
)
const descriptor = parse(source).descriptor,
	compiled = compileScript(descriptor, { id: 'private-preview-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'PrivateFilePreview.vue',
	id: 'private-preview-runtime',
	compilerOptions: { bindingMetadata: compiled.bindings },
})
assert.deepEqual(template.errors, [])
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
const { renderAssistantContent } = await import(
	'../src/utils/assistant-answer.mjs'
)
const markdownRenderInputs = []
async function childComponent(filename) {
	const childSource = await readFile(
		new URL(`../src/components/file/preview/${filename}.vue`, import.meta.url),
		'utf8'
	)
	const descriptor = parse(childSource).descriptor,
		script = compileScript(descriptor, { id: filename })
	const template = compileTemplate({
		source: descriptor.template.content,
		filename,
		id: filename,
		compilerOptions: { bindingMetadata: script.bindings },
	})
	assert.deepEqual(template.errors, [])
	const modules = {
		vue,
		'~/utils/assistant-answer.mjs': {
			renderAssistantContent: (text) => {
				markdownRenderInputs.push(text.length)
				return renderAssistantContent(text)
			},
		},
	}
	const component = new Function(
		'modules',
		transform(script.content).replace('export default', 'return')
	)(modules)
	component.render = new Function(
		'modules',
		transform(template.code).replace(
			'export function render',
			'return function render'
		)
	)(modules)
	return component
}
const MarkdownComponent = await childComponent('PrivateMarkdownPreview'),
	OfficeComponent = await childComponent('PrivateOfficePreview')
const flush = async () => {
	for (let i = 0; i < 10; i++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
const textRow = {
	name: 'note.md',
	path: '/',
	size: 7,
	authenticatedContent: true,
	previewAvailable: true,
	previewKind: 'text',
	previewUrl: '/api/drives/private-drive/preview?path=%2Fnote.md',
}
async function mount(
	request = async () => ({
		data: new Blob(['private'], { type: 'text/plain' }),
	}),
	parseDocument
) {
	const state = vue.shallowRef({ visible: false }),
		revoked = [],
		calls = []
	const session = createPrivatePreviewSession({
		parseDocument,
		request: (...args) => {
			calls.push(args)
			return request(...args)
		},
		onChange: (value) => {
			state.value = value
		},
		createUrl: () => 'blob:owned-preview',
		revokeUrl: (url) => revoked.push(url),
	})
	const store = vue.reactive({
		loginInfo: { isLogin: true, username: 'member', role: 'USER', tenantId: 9 },
		folderConfig: { permission: { preview: true } },
	})
	const route = vue.reactive({ fullPath: '/private-drive' }),
		credential = vue.ref('synthetic-token')
	const Dialog = {
		props: ['modelValue'],
		setup:
			(props, { slots, emit }) =>
			() =>
				props.modelValue
					? vue.h('div', [
							vue.h('button', { onClick: () => emit('close') }, '关闭预览'),
							slots.default?.(),
					  ])
					: null,
	}
	const modules = {
		vue: {
			...vue,
			defineAsyncComponent: (loader) =>
				loader.toString().includes('PrivateMarkdownPreview')
					? MarkdownComponent
					: loader.toString().includes('PrivateOfficePreview')
					? OfficeComponent
					: loader.toString().includes('PrivateTextPreview')
					? {
							props: ['text', 'name'],
							render() {
								return vue.h('pre', this.text)
							},
					  }
					: loader.toString().includes('PrivateModelPreview')
					? {
							props: ['buffer', 'format'],
							render() {
								return vue.h('div', { 'data-private-model': this.format })
							},
					  }
					: { render: () => vue.h('div', { 'data-private-pdf': true }) },
		},
		'vue-router': { useRoute: () => route },
		'@vueuse/core': { useStorage: () => credential },
		'~/components/common/dialog/ZDialog.vue': Dialog,
		'~/composables/file/usePrivatePreviewDialog': () => ({ state, ...session }),
		'~/stores/storage-config': () => store,
	}
	const Component = new Function(
		'modules',
		transform(compiled.content).replace('export default', 'return')
	)(modules)
	Component.render = new Function(
		'modules',
		transform(template.code).replace(
			'export function render',
			'return function render'
		)
	)(modules)
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(Component)
	app.mount(host)
	await flush()
	return {
		host,
		state,
		store,
		route,
		credential,
		revoked,
		calls,
		session,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}
test('private Markdown is rendered as source text and never creates executable markup or remote resources', async () => {
	const app = await mount(async () => ({
		data: new Blob(
			['<img src="https://external.invalid/x" onerror="alert(1)">'],
			{ type: 'text/plain' }
		),
	}))
	try {
		await app.session.open(
			{
				...textRow,
				size: new Blob([
					'<img src="https://external.invalid/x" onerror="alert(1)">',
				]).size,
			},
			'private-drive'
		)
		await flush()
		assert.equal(app.host.querySelector('img'), null)
		assert.equal(app.host.querySelector('iframe'), null)
		assert.match(app.host.querySelector('pre').textContent, /<img src=/)
		assert.equal(app.calls.length, 1)
	} finally {
		app.dispose()
	}
})
test('a route, identity, credential or grant change closes the preview and releases its owned object URL', async () => {
	const app = await mount(async () => ({
		data: new Blob(['png'], { type: 'image/png' }),
	}))
	const image = {
		...textRow,
		name: 'a.png',
		size: 3,
		previewKind: 'image',
		previewUrl: '/api/drives/private-drive/preview?path=%2Fa.png',
	}
	try {
		for (const change of [
			() => {
				app.route.fullPath = '/another-drive'
			},
			() => {
				app.store.loginInfo.username = 'peer'
			},
			() => {
				app.credential.value = 'rotated-token'
			},
			() => {
				app.store.folderConfig.permission.preview = false
			},
		]) {
			await app.session.open(image, 'private-drive')
			await flush()
			assert.equal(
				app.host.querySelector('img').getAttribute('src'),
				'blob:owned-preview'
			)
			const before = app.revoked.length
			change()
			assert.equal(app.state.value.visible, false)
			assert.equal(app.revoked.length, before + 1)
			await flush()
			assert.equal(app.host.querySelector('img'), null)
		}
	} finally {
		app.dispose()
	}
})
test('closing and unmounting abort pending preview transport without displaying late private bytes', async () => {
	let resolve
	const app = await mount(
		() =>
			new Promise((done) => {
				resolve = done
			})
	)
	const pending = app.session.open(textRow, 'private-drive')
	await flush()
	app.host.querySelector('button').click()
	assert.equal(app.calls[0][1].aborted, true)
	resolve({ data: new Blob(['late secret'], { type: 'text/plain' }) })
	await pending
	await flush()
	assert.doesNotMatch(app.host.textContent, /late secret/)
	app.dispose()
})
test('private preview styles compile without legacy Office or unsandboxed HTML viewers', () => {
	assert.doesNotMatch(source, /v-html|OfficeViewer|<iframe/)
	for (const style of descriptor.styles)
		assert.deepEqual(
			compileStyle({
				source: style.content,
				filename: 'PrivateFilePreview.vue',
				id: 'private-preview-style',
				scoped: true,
			}).errors,
			[]
		)
})
test('Markdown preview formats headings and tables while dropping images, scripts and executable links', async () => {
	const text =
		'# 课程\n\n**重点**\n\n|章|资料|\n|-|-|\n|一|讲义|\n\n<img src="https://external.invalid/pixel"><script>alert(1)</script>\n\n[危险](javascript:alert(1)) [文档](https://example.invalid/help)'
	const app = await mount(async () => ({
		data: new Blob([text], { type: 'text/plain' }),
	}))
	try {
		await app.session.open(
			{ ...textRow, size: new Blob([text]).size, previewKind: 'markdown' },
			'private-drive'
		)
		await flush()
		assert.equal(app.host.querySelector('h1').textContent, '课程')
		assert.ok(app.host.querySelector('table'))
		assert.equal(app.host.querySelector('img,script,iframe'), null)
		assert.equal(app.host.querySelector('a[href^="javascript:"]'), null)
		assert.equal(
			app.host.querySelector('a[href^="https:"]').getAttribute('rel'),
			'noopener noreferrer'
		)
	} finally {
		app.dispose()
	}
})
test('media previews use owned Blob URLs, native controls and never autoplay', async () => {
	for (const [kind, type, name] of [
		['video', 'video/mp4', 'lesson.mp4'],
		['audio', 'audio/mpeg', 'lesson.mp3'],
	]) {
		const app = await mount(async () => ({
			data: new Blob(['media'], { type }),
		}))
		try {
			await app.session.open(
				{
					...textRow,
					name,
					size: 5,
					previewKind: kind,
					previewUrl: `/api/drives/private-drive/preview?path=%2F${name}`,
				},
				'private-drive'
			)
			await flush()
			const media = app.host.querySelector(kind)
			assert.ok(media.hasAttribute('controls'))
			assert.equal(media.getAttribute('preload'), 'metadata')
			assert.equal(media.getAttribute('src'), 'blob:owned-preview')
			assert.equal(media.hasAttribute('autoplay'), false)
		} finally {
			app.dispose()
		}
	}
})
test('local spreadsheet views render inert values, paginate rows and switch worksheets', async () => {
	const model = {
		kind: 'workbook',
		sheets: [
			{
				name: '课程',
				rows: Array.from({ length: 65 }, (_, i) => [
					String(i),
					'<img src=x onerror=alert(1)>',
				]),
			},
			{ name: '其他', rows: [['hello']] },
		],
		truncated: false,
	}
	const app = await mount(
		async () => ({
			data: new Blob(['office'], {
				type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
			}),
		}),
		async () => model
	)
	try {
		await app.session.open(
			{
				...textRow,
				name: 'course.xlsx',
				size: 6,
				previewKind: 'office',
				previewUrl: '/api/drives/private-drive/preview?path=%2Fcourse.xlsx',
			},
			'private-drive'
		)
		await flush()
		assert.equal(app.host.querySelectorAll('tbody tr').length, 50)
		assert.equal(app.host.querySelector('img'), null)
		Array.from(app.host.querySelectorAll('button'))
			.find((button) => button.textContent.trim() === '下一页')
			.click()
		await flush()
		assert.equal(app.host.querySelectorAll('tbody tr').length, 15)
		Array.from(app.host.querySelectorAll('[role="tab"]'))
			.find((button) => button.textContent.trim() === '其他')
			.click()
		await flush()
		assert.equal(app.host.querySelector('td').textContent, 'hello')
		const tab = app.host.querySelector('[role="tab"][aria-selected="true"]')
		tab.dispatchEvent(
			new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true })
		)
		await flush()
		assert.equal(
			app.host
				.querySelector('[role="tab"][aria-selected="true"]')
				.textContent.trim(),
			'课程'
		)
		assert.equal(app.host.querySelectorAll('tbody tr').length, 50)
	} finally {
		app.dispose()
	}
})
test('image preview tools zoom, rotate and reset without requesting another file', async () => {
	const app = await mount(async () => ({
		data: new Blob(['png'], { type: 'image/png' }),
	}))
	try {
		await app.session.open(
			{
				...textRow,
				name: 'a.png',
				size: 3,
				previewKind: 'image',
				previewUrl: '/api/drives/private-drive/preview?path=%2Fa.png',
			},
			'private-drive'
		)
		await flush()
		const click = async (text) => {
			Array.from(app.host.querySelectorAll('button'))
				.find((button) => button.textContent.trim() === text)
				.click()
			await flush()
		}
		await click('放大')
		await click('旋转')
		assert.match(
			app.host.querySelector('img').style.transform,
			/rotate\(90deg\) scale\(1.25\)/
		)
		await click('还原')
		assert.match(
			app.host.querySelector('img').style.transform,
			/rotate\(0deg\) scale\(1\)/
		)
		assert.equal(app.calls.length, 1)
	} finally {
		app.dispose()
	}
})
test('large Markdown is capped before formatting while full source remains readable', async () => {
	const text = 'A'.repeat(100000) + '\n\n# FULL_SOURCE_END'
	const app = await mount(async () => ({
		data: new Blob([text], { type: 'text/plain' }),
	}))
	try {
		markdownRenderInputs.length = 0
		await app.session.open(
			{ ...textRow, size: new Blob([text]).size, previewKind: 'markdown' },
			'private-drive'
		)
		await flush()
		assert.ok(markdownRenderInputs.length > 0)
		assert.ok(markdownRenderInputs.every((length) => length <= 100000))
		assert.match(app.host.textContent, /排版预览.*前.*10.*万/)
		assert.doesNotMatch(
			app.host.querySelector('article').textContent,
			/FULL_SOURCE_END/
		)
		Array.from(app.host.querySelectorAll('button'))
			.find((button) => button.textContent.trim() === '查看源码')
			.click()
		await flush()
		assert.equal(app.host.querySelector('pre').textContent, text)
		assert.equal(app.calls.length, 1)
	} finally {
		app.dispose()
	}
})
