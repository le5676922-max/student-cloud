import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of [
	'window',
	'document',
	'Document',
	'Node',
	'Element',
	'HTMLElement',
	'HTMLInputElement',
	'SVGElement',
	'Event',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
after(() => dom.window.close())
const descriptor = parse(
	await readFile(
		new URL('../src/components/auth/OwnershipTransfer.vue', import.meta.url),
		'utf8'
	)
).descriptor
const script = compileScript(descriptor, { id: 'ownership-test' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'OwnershipTransfer.vue',
	id: 'ownership-test',
	compilerOptions: { bindingMetadata: script.bindings },
})
assert.deepEqual(template.errors, [])
const transform = (code) =>
	code
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_, names, mod) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}}=modules[${JSON.stringify(
					mod
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_, name, mod) => `const ${name}=modules[${JSON.stringify(mod)}]`
		)
const flush = async () => {
	for (let n = 0; n < 15; n++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
const snapshot = {
	actorId: 1,
	resourceId: 7,
	resourceName: '合成空间',
	ownershipRevision: 0,
	canCreate: true,
	candidates: [{ id: 2, accountId: 'SC2', username: '接任同学' }],
	transfers: [],
}
async function mount({
	enabled = true,
	read = async () => ({ data: structuredClone(snapshot) }),
	write = async () => ({ data: { state: 'PENDING' } }),
	confirm = async () => {},
} = {}) {
	const store = vue.reactive({
		loginInfo: {
			isLogin: true,
			role: 'TENANT_ADMIN',
			tenantId: 7,
			username: 'first',
		},
	})
	const reads = [],
		writes = [],
		events = []
	const plain = vue.defineComponent({
		inheritAttrs: false,
		setup: (_, ctx) => () => vue.h('div', ctx.attrs, ctx.slots.default?.()),
	})
	const modules = {
		vue,
		'~/stores/storage-config': () => store,
		'element-plus': {
			ElSelect: plain,
			ElOption: plain,
			ElButton: plain,
			ElCheckbox: plain,
			ElMessageBox: { confirm },
		},
		'~/api/home/ownership-transfer': {
			getOwnershipTransfersReq: (...args) => {
				reads.push(args)
				return read(...args)
			},
			createOwnershipTransferReq: (...args) => {
				writes.push(args)
				return write(...args)
			},
			decideOwnershipTransferReq: (...args) => {
				writes.push(args)
				return write(...args)
			},
		},
	}
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
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(Component, {
		enabled,
		resourceType: 'workspace',
		onCompleted: (value) => events.push(value),
	})
	const api = app.mount(host)
	await flush()
	return {
		api,
		host,
		store,
		reads,
		writes,
		events,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}
test('feature is off by default and disabled mode makes no request', async () => {
	const x = await mount({ enabled: false })
	try {
		assert.equal(x.reads.length, 0)
		assert.match(x.host.textContent, /未启用/)
	} finally {
		x.dispose()
	}
})
test('requires named recipient and explicit impact acknowledgement before create', async () => {
	const confirmations = []
	const x = await mount({
		confirm: async (message) => confirmations.push(message),
	})
	try {
		x.api.draft.toAccountId = 2
		await x.api.create()
		assert.equal(x.writes.length, 0)
		x.api.draft.confirmed = true
		await x.api.create()
		assert.equal(x.writes.length, 1)
		const body = x.writes[0][2]
		assert.equal(body.toAccountId, 2)
		assert.equal(body.expectedRevision, 0)
		assert.match(body.idempotencyKey, /^[A-Za-z0-9._:-]+$/)
		assert.ok(body.expiresAt > Date.now())
		assert.match(confirmations[0], /接任同学.*SC2/)
		assert.match(confirmations[0], /24 小时/)
		assert.match(x.host.textContent, /私人文件/)
	} finally {
		x.dispose()
	}
})
test('unknown mutation is not retried and explicit retry preserves identical key and expiry', async () => {
	const x = await mount({
		write: async () => {
			throw Error('lost response')
		},
	})
	try {
		x.api.draft.toAccountId = 2
		x.api.draft.confirmed = true
		await x.api.create()
		assert.equal(x.writes.length, 1)
		assert.match(x.host.textContent, /未确认/)
		await x.api.create()
		assert.deepEqual(x.writes[0][2], x.writes[1][2])
	} finally {
		x.dispose()
	}
})
test('account switch invalidates late results and clears sensitive in-memory draft', async () => {
	let release
	let count = 0
	const x = await mount({
		read: () =>
			++count === 1
				? new Promise((done) => (release = done))
				: Promise.resolve({ data: { ...snapshot, resourceName: '新空间' } }),
	})
	try {
		x.api.draft.toAccountId = 2
		x.api.draft.confirmed = true
		x.store.loginInfo.username = 'second'
		await flush()
		release({ data: { ...snapshot, resourceName: '旧空间' } })
		await flush()
		assert.equal(x.api.draft.toAccountId, null)
		assert.equal(x.api.draft.confirmed, false)
		assert.match(x.host.textContent, /新空间/)
		assert.doesNotMatch(x.host.textContent, /旧空间/)
	} finally {
		x.dispose()
	}
})
test('only recipient can accept and expired result never emits completion', async () => {
	const x = await mount({ write: async () => ({ data: { state: 'EXPIRED' } }) })
	try {
		await x.api.decide(
			{ id: 'one', fromAccountId: 1, toAccountId: 2, state: 'PENDING' },
			'accept'
		)
		assert.equal(x.writes.length, 0)
		await x.api.decide(
			{ id: 'two', fromAccountId: 2, toAccountId: 1, state: 'PENDING' },
			'accept'
		)
		assert.equal(x.writes.length, 1)
		assert.equal(x.events.length, 0)
		assert.match(x.host.textContent, /已过期/)
	} finally {
		x.dispose()
	}
})
