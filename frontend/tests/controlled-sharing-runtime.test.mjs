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
import * as utility from '../src/utils/controlled-sharing.mjs'

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
	url: 'https://cloud.test',
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
const vue = await import('vue')
const links = [],
	urls = [],
	revoked = []
const originalCreate = URL.createObjectURL,
	originalRevoke = URL.revokeObjectURL
URL.createObjectURL = () => {
	const url = `blob:controlled-${urls.length}`
	urls.push(url)
	return url
}
URL.revokeObjectURL = (url) => revoked.push(url)
dom.window.HTMLAnchorElement.prototype.click = function () {
	links.push({ href: this.href, download: this.download })
}
after(() => {
	URL.createObjectURL = originalCreate
	URL.revokeObjectURL = originalRevoke
	dom.window.close()
})
const flush = async () => {
	for (let n = 0; n < 12; n++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
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
const Button = {
	props: ['disabled', 'loading'],
	setup:
		(props, { slots, emit }) =>
		() =>
			vue.h(
				'button',
				{ disabled: props.disabled, onClick: () => emit('click') },
				slots.default?.()
			),
}
const Link = {
	props: ['to'],
	setup:
		(props, { slots }) =>
		() =>
			vue.h('a', { href: props.to }, slots.default?.()),
}
async function mountFile(path, modules) {
	const descriptor = parse(
		await readFile(new URL(path, import.meta.url), 'utf8')
	).descriptor
	const script = compileScript(descriptor, { id: 'controlled-runtime' }),
		template = compileTemplate({
			source: descriptor.template.content,
			filename: path,
			id: 'controlled-runtime',
			compilerOptions: { bindingMetadata: script.bindings },
		})
	assert.deepEqual(template.errors, [])
	for (const style of descriptor.styles)
		assert.deepEqual(
			compileStyle({
				source: style.content,
				id: 'controlled-runtime',
				scoped: true,
			}).errors,
			[]
		)
	const allModules = {
			vue,
			'@vueuse/core': { useStorage: () => vue.ref('session') },
			...modules,
		},
		component = new Function(
			'modules',
			transform(script.content).replace('export default', 'return')
		)(allModules)
	component.render = new Function(
		'modules',
		transform(template.code).replace(
			'export function render',
			'return function render'
		)
	)(allModules)
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(component)
	app.component('ElButton', Button)
	app.component('RouterLink', Link)
	for (const name of ['ElForm', 'ElFormItem'])
		app.component(name, {
			setup:
				(_props, { slots }) =>
				() =>
					vue.h('div', slots.default?.()),
		})
	app.component('ElCheckbox', {
		props: ['modelValue', 'disabled'],
		setup:
			(props, { slots, emit }) =>
			() =>
				vue.h('label', [
					vue.h('input', {
						type: 'checkbox',
						checked: props.modelValue,
						disabled: props.disabled,
						onChange: (event) =>
							emit('update:modelValue', event.target.checked),
					}),
					slots.default?.(),
				]),
	})
	app.component('ElSelect', {
		props: ['modelValue'],
		setup:
			(props, { slots, emit }) =>
			() =>
				vue.h(
					'select',
					{
						value: props.modelValue,
						onChange: (event) => emit('update:modelValue', event.target.value),
					},
					slots.default?.()
				),
	})
	app.component('ElOption', {
		props: ['label', 'value'],
		setup: (props) => () =>
			vue.h('option', { value: props.value }, props.label),
	})
	app.component('ElInput', {
		props: ['modelValue'],
		setup: (props) => () => vue.h('input', { value: props.modelValue }),
	})
	const instance = app.mount(host)
	await flush()
	return {
		host,
		instance,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}
const id = 'a'.repeat(43)
const metadata = {
	id,
	name: 'private-note.txt',
	size: 7,
	audience: 'OWN_WORKSPACE',
	expiresAt: Date.now() + 60000,
}
test('real dialog requires workspace audience consent before creating and revokes only a share', async () => {
	const route = vue.reactive({ fullPath: '/private-drive/notes' }),
		store = vue.reactive({
			loginInfo: { id: 3, isLogin: true, role: 'USER', tenantId: 1 },
		}),
		calls = []
	const dialog = {
		props: ['modelValue'],
		setup:
			(props, { slots }) =>
			() =>
				props.modelValue ? vue.h('div', slots.default?.()) : null,
	}
	const page = await mountFile(
		'../src/components/file/ControlledShareDialog.vue',
		{
			'vue-router': { useRoute: () => route },
			'~/stores/storage-config': () => store,
			'~/utils/controlled-sharing.mjs': utility,
			'element-plus': { ElMessage: { success() {} } },
			'~/components/common/dialog/ZDialog.vue': dialog,
			'~/api/home/courses': { courseRequest: async () => ({ data: [] }) },
			'~/api/home/controlled-shares': {
				controlledSharesRequest: async (path, method, data) => {
					calls.push({ path, method, data })
					return { data: { ...metadata, version: 1 } }
				},
			},
		}
	)
	try {
		await page.instance.openDialog(
			Object.freeze({
				storageKey: 'private-drive',
				path: '/notes/private-note.txt',
			}),
			'private-note.txt'
		)
		await flush()
		assert.match(page.host.textContent, /仅本空间已登录成员可访问/)
		assert.equal(page.host.querySelector('button').disabled, true)
		assert.equal(calls.length, 0)
		const consent = page.host.querySelector('input[type=checkbox]')
		consent.checked = true
		consent.dispatchEvent(new dom.window.Event('change', { bubbles: true }))
		await flush()
		page.host.querySelector('button').click()
		await flush()
		assert.equal(calls[0].data.audience, 'OWN_WORKSPACE')
		assert.equal(calls[0].data.path, '/notes/private-note.txt')
		assert.equal(
			page.host.querySelector('#controlled-share-url').value,
			`https://cloud.test/controlled-shares/${id}`
		)
		const revoke = [...page.host.querySelectorAll('button')].find(
			(button) => button.textContent === '撤销分享'
		)
		revoke.click()
		await flush()
		assert.deepEqual(calls.at(-1), {
			path: `/${id}`,
			method: 'delete',
			data: { version: 1 },
		})
		assert.match(page.host.textContent, /已撤销/)
		route.fullPath = '/other-drive'
		await flush()
		assert.equal(page.host.textContent, '')
	} finally {
		page.dispose()
	}
})
test('protected page downloads only an authenticated Blob and clears access errors', async () => {
	const route = vue.reactive({ params: { id } }),
		store = vue.reactive({
			loginInfo: {
				id: 3,
				accountId: 'a3',
				username: 'member',
				isLogin: true,
				role: 'USER',
				tenantId: 1,
			},
		})
	let failed = false
	const calls = []
	const page = await mountFile('../src/pages/controlled-shares/[id].vue', {
		'vue-router': { useRoute: () => route },
		'~/stores/storage-config': () => store,
		'~/utils/controlled-sharing.mjs': utility,
		'~/api/home/controlled-shares': {
			controlledSharesRequest: async (path) => {
				calls.push(path)
				return { data: metadata }
			},
			downloadControlledShare: async (value) => {
				calls.push(value)
				if (failed) throw new Error('分享已撤销或过期')
				return new Blob(['private'])
			},
		},
	})
	try {
		assert.match(page.host.textContent, /仅本空间已登录成员可访问/)
		page.host.querySelector('button').click()
		await flush()
		assert.deepEqual(links.at(-1), {
			href: urls.at(-1),
			download: 'private-note.txt',
		})
		assert.equal(page.host.querySelector('iframe, img, object'), null)
		failed = true
		page.host.querySelector('button').click()
		await flush()
		assert.match(page.host.textContent, /分享已撤销或过期/)
		assert.doesNotMatch(page.host.textContent, /下载文件/)
		assert.ok(calls.every((path) => !/token=|https?:/.test(path)))
	} finally {
		page.dispose()
	}
})
test('switching account while download is pending never creates a private Blob URL', async () => {
	const route = vue.reactive({ params: { id } }),
		store = vue.reactive({
			loginInfo: { id: 3, isLogin: true, role: 'USER', tenantId: 1 },
		})
	let finish
	const pending = new Promise((resolve) => {
			finish = resolve
		}),
		before = urls.length
	const page = await mountFile('../src/pages/controlled-shares/[id].vue', {
		'vue-router': { useRoute: () => route },
		'~/stores/storage-config': () => store,
		'~/utils/controlled-sharing.mjs': utility,
		'~/api/home/controlled-shares': {
			controlledSharesRequest: async () => ({ data: metadata }),
			downloadControlledShare: () => pending,
		},
	})
	try {
		page.host.querySelector('button').click()
		await flush()
		store.loginInfo.id = 4
		await flush()
		finish(new Blob(['private']))
		await flush()
		assert.equal(urls.length, before)
	} finally {
		page.dispose()
	}
})
