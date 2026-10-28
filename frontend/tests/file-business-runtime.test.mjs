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
import { createLatestRequest } from '../src/utils/latest-request.mjs'
import {
	formatOverviewBytes,
	formatOverviewCount,
	formatOverviewDate,
} from '../src/utils/admin-overview.mjs'
const dom = new JSDOM('<!doctype html><html><body></body></html>')
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
after(() => dom.window.close())
const descriptor = parse(
	await readFile(
		new URL(
			'../src/components/admin/AdminFileBusinessPanel.vue',
			import.meta.url
		),
		'utf8'
	)
).descriptor
const script = compileScript(descriptor, { id: 'file-business-runtime' }),
	template = compileTemplate({
		source: descriptor.template.content,
		filename: 'AdminFileBusinessPanel.vue',
		id: 'file-business-runtime',
		compilerOptions: { bindingMetadata: script.bindings },
	})
assert.deepEqual(template.errors, [])
for (const style of descriptor.styles)
	assert.deepEqual(
		compileStyle({
			source: style.content,
			id: 'file-business-runtime',
			scoped: true,
		}).errors,
		[]
	)
const transform = (code) =>
	code
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_m, names, module) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}}=modules[${JSON.stringify(
					module
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_m, name, module) => `const ${name}=modules[${JSON.stringify(module)}]`
		)
const flush = async () => {
	for (let n = 0; n < 12; n++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
const sample = (scope = 'GLOBAL', workspaceId = null) => ({
	scope,
	workspaceId,
	courseScope: scope === 'GLOBAL' ? 'ALL_CREATED' : 'OWN_CREATED',
	dataComplete: false,
	metrics: Object.fromEntries(
		Object.entries({
			mounts: 3,
			courses: 2,
			readyMaterials: 5,
			materialBytes: 1024,
			activeUploads: 0,
			stagingBytes: 0,
			activeShares: 7,
			trashItems: 2,
			trashBytes: 2048,
		}).map(([key, value]) => [key, { available: true, value }])
	),
	trends: {
		uploads: {
			available: true,
			days: [
				{ date: '2026-10-25', count: 0 },
				{ date: '2026-10-26', count: 3 },
			],
		},
		shares: {
			available: true,
			days: [
				{ date: '2026-10-25', count: 1 },
				{ date: '2026-10-26', count: 4 },
			],
		},
		materials: { available: false, days: [], note: '资料趋势数据源暂不可用' },
	},
})
async function mount({
	role = 'ADMIN',
	accountModel = 'independent',
	request = async () => ({ data: sample() }),
	props = {},
} = {}) {
	const calls = [],
		credential = vue.ref('token'),
		store = vue.reactive({
			globalConfig: { accountModel },
			loginInfo: {
				id: 1,
				username: 'manager',
				isLogin: true,
				role,
				tenantId: role === 'TENANT_ADMIN' ? 1 : null,
			},
		})
	const modules = {
		vue,
		'@vueuse/core': { useStorage: () => credential },
		'~/stores/storage-config': () => store,
		'~/api/admin/file-business': {
			getFileBusinessOverviewReq: (...args) => {
				calls.push(args)
				return request(...args)
			},
		},
		'~/utils/latest-request.mjs': { createLatestRequest },
		'~/utils/admin-overview.mjs': {
			formatOverviewBytes,
			formatOverviewCount,
			formatOverviewDate,
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
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(component, props)
	app.component('el-select', {
		props: ['modelValue', 'disabled', 'ariaLabel'],
		emits: ['update:modelValue'],
		setup:
			(p, { slots, emit }) =>
			() =>
				vue.h(
					'select',
					{
						value: p.modelValue,
						disabled: p.disabled,
						'aria-label': p.ariaLabel,
						onChange: (event) =>
							emit('update:modelValue', Number(event.target.value)),
					},
					slots.default?.()
				),
	})
	app.component('el-option', {
		props: ['label', 'value'],
		setup: (p) => () => vue.h('option', { value: p.value }, p.label),
	})
	const vm = app.mount(host)
	await flush()
	return {
		host,
		store,
		credential,
		calls,
		api: vm.$.setupState,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}
test('file business panel renders real values and accessible bars while unavailable sources stay unknown', async () => {
	const page = await mount()
	try {
		assert.equal(page.host.querySelectorAll('article').length, 9)
		assert.match(page.host.textContent, /1 KiB/)
		assert.match(page.host.textContent, /2 KiB/)
		assert.match(page.host.textContent, /资料趋势数据源暂不可用/)
		assert.match(page.host.textContent, /不代表0/)
		const bars = page.host.querySelectorAll('[role=img]')
		assert.equal(bars.length, 2)
		assert.match(bars[0].getAttribute('aria-label'), /2026-10-25：0/)
		assert.equal(
			page.host.querySelector('.file-business-column i').style.height,
			'0%'
		)
		assert.equal(page.calls[0][0], 7)
	} finally {
		page.dispose()
	}
})
test('workspace manager response must match own workspace and labels only their own created courses', async () => {
	const own = await mount({
		role: 'TENANT_ADMIN',
		request: async () => ({ data: sample('WORKSPACE', 1) }),
	})
	try {
		assert.match(own.host.textContent, /本人创建课程/)
		assert.match(own.host.textContent, /当前工作空间/)
	} finally {
		own.dispose()
	}
	const other = await mount({
		role: 'TENANT_ADMIN',
		request: async () => ({ data: sample('WORKSPACE', 2) }),
	})
	try {
		assert.match(other.host.textContent, /统计范围与当前账号不符/)
		assert.ok(
			[...other.host.querySelectorAll('article strong')].every(
				(node) => node.textContent === '—'
			)
		)
		assert.equal(other.host.querySelector('[role=img]'), null)
	} finally {
		other.dispose()
	}
})
test('credential or account change discards late global statistics and members never request statistics', async () => {
	let finish
	const pending = new Promise((resolve) => {
			finish = resolve
		}),
		page = await mount({ request: () => pending })
	try {
		page.store.loginInfo.role = 'USER'
		await flush()
		finish({ data: sample() })
		await flush()
		assert.equal(page.calls.length, 1)
		assert.equal(page.host.querySelector('[role=img]'), null)
		assert.match(page.host.textContent, /仅管理员/)
		assert.equal(page.calls[0][1].signal.aborted, true)
	} finally {
		page.dispose()
	}
	const legacy = await mount({ accountModel: '' })
	try {
		assert.equal(legacy.calls.length, 0)
		assert.equal(legacy.host.textContent, '')
	} finally {
		legacy.dispose()
	}
})
test('both administration pages include the shared file business panel', async () => {
	for (const path of [
		'../src/pages/admin/overview.vue',
		'../src/pages/workspace-admin.vue',
	]) {
		const code = await readFile(new URL(path, import.meta.url), 'utf8')
		assert.match(code, /<AdminFileBusinessPanel/)
		assert.match(code, /AdminFileBusinessPanel\.vue/)
	}
})

test('managed file panel renders the parent response without issuing duplicate requests and emits refresh/date changes', async () => {
	const refreshes = []
	const shared = sample()
	const page = await mount({
		props: {
			managed: true,
			overview: shared,
			onRefresh: (days) => refreshes.push(days),
		},
	})
	try {
		assert.equal(page.calls.length, 0)
		assert.match(page.host.textContent, /1 KiB/)
		page.host.querySelector('button').click()
		await flush()
		assert.deepEqual(refreshes, [7])
		const select = page.host.querySelector('select')
		select.value = '30'
		select.dispatchEvent(new Event('change', { bubbles: true }))
		await flush()
		assert.deepEqual(refreshes, [7, 30])
		assert.equal(page.calls.length, 0)
		page.store.loginInfo.role = 'USER'
		await flush()
		assert.equal(page.host.querySelector('[role=img]'), null)
		assert.ok(
			[...page.host.querySelectorAll('article strong')].every(
				(node) => node.textContent === '—'
			)
		)
	} finally {
		page.dispose()
	}
})

test('global file panel rejects workspace-scoped data instead of presenting it as a global total', async () => {
	const page = await mount({
		request: async () => ({ data: sample('WORKSPACE', 1) }),
	})
	try {
		assert.match(page.host.textContent, /统计范围与当前账号不符/)
		assert.equal(page.host.querySelector('[role=img]'), null)
	} finally {
		page.dispose()
	}
})
