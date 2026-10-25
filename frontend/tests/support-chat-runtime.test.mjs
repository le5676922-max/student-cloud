import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as supportTools from '../src/utils/support-realtime.mjs'

// Actual Vue setup and template in a local DOM; all credentials, transports and requests are synthetic.
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
	url: 'https://cloud.invalid/support',
})
for (const key of [
	'window',
	'document',
	'Document',
	'Node',
	'Element',
	'HTMLElement',
	'HTMLTextAreaElement',
	'HTMLSelectElement',
	'SVGElement',
	'Event',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
const descriptor = parse(
	await readFile(
		new URL('../src/components/support/SupportChat.vue', import.meta.url),
		'utf8'
	)
).descriptor
const compiled = compileScript(descriptor, { id: 'support-chat-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'SupportChat.vue',
	id: 'support-chat-runtime',
	compilerOptions: { bindingMetadata: compiled.bindings },
})
assert.deepEqual(template.errors, [])
const imports = (code) =>
	code
		.replace(
			/import \{([\s\S]*?)\} from ['"]([^'"]+)['"]/g,
			(_match, names, module) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					module
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from ['"]([^'"]+)['"]/g,
			(_match, name, module) =>
				`const ${name} = modules[${JSON.stringify(module)}]`
		)
after(() => dom.window.close())
const flush = async () => {
	for (let index = 0; index < 15; index++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
function deferred() {
	let resolve, reject
	const promise = new Promise((accept, decline) => {
		resolve = accept
		reject = decline
	})
	return { promise, resolve, reject }
}
const conversation = (id = 'first', status = 'WAITING') => ({
	id,
	status,
	ownerUserId: 12,
	ownerAccountId: 'SC0123456789ABCDEF',
	ownerName: '同学',
	tenantId: 9,
	tenantName: '课程空间',
	subject: `${id} 的求助`,
	createdAt: 10,
	updatedAt: 20,
})
const entry = (id = 1, conversationId = 'first', content = '初始问题') => ({
	id,
	conversationId,
	senderUserId: 12,
	senderRole: 'USER',
	content,
	clientMessageId: `synthetic-${id}`,
	createdAt: 30,
})
async function mountSupport({
	admin = false,
	login,
	rows = [],
	history = [],
	handler,
} = {}) {
	const store = vue.reactive({
		loginInfo: login || {
			isLogin: true,
			isAdmin: admin,
			username: 'member',
			role: admin ? 'ADMIN' : 'USER',
			tenantId: admin ? null : 9,
		},
	})
	const credential = vue.ref('synthetic-token')
	const calls = [],
		realtimes = []
	const records = new Map(rows.map((row) => [row.id, { ...row }]))
	const entries = [...history]
	let nextMessage = Math.max(0, ...entries.map((message) => message.id)) + 1
	const modules = {
		vue,
		'@vueuse/core': { useStorage: () => credential },
		'~/stores/storage-config': () => store,
		'~/stores/global-config': () => ({
			studentCloudConfig: { baseUrl: 'https://api.invalid' },
		}),
		'~/utils/support-realtime.mjs': {
			...supportTools,
			createSupportRealtime(options) {
				const session = {
					options,
					starts: 0,
					stops: 0,
					start() {
						this.starts++
					},
					stop() {
						this.stops++
					},
					retry() {
						this.start()
					},
				}
				realtimes.push(session)
				return session
			},
		},
		'~/api/home/support': {
			supportRequest: async (method, path, data, config) => {
				const call = { method, path, data, config }
				calls.push(call)
				if (handler) {
					const result = handler(call, { records, entries })
					if (result !== undefined) return result
				}
				if (method === 'get' && path === '/conversations') {
					const items = [...records.values()].filter(
						(row) => !data.status || row.status === data.status
					)
					return {
						data: {
							items,
							total: items.length,
							page: data.page,
							pageSize: data.pageSize,
						},
					}
				}
				if (method === 'post' && path === '/conversations') {
					const row = conversation('new')
					records.set(row.id, row)
					entries.push({
						...entry(nextMessage++, row.id, data.content),
						clientMessageId: data.clientMessageId,
					})
					return { data: { ...row } }
				}
				const [, id, action] =
					path.match(
						/^\/conversations\/([^/]+)(?:\/(messages|accept|close))?$/
					) || []
				if (method === 'get' && !action) return { data: { ...records.get(id) } }
				if (method === 'get' && action === 'messages')
					return {
						data: {
							items: entries.filter(
								(message) =>
									message.conversationId === id &&
									(!data.afterId || message.id > data.afterId) &&
									(!data.beforeId || message.id < data.beforeId)
							),
							hasMore: false,
						},
					}
				if (method === 'post' && action === 'messages') {
					const existing = entries.find(
						(message) => message.clientMessageId === data.clientMessageId
					)
					const saved = existing || {
						...entry(nextMessage++, id, data.content),
						senderRole: admin ? 'ADMIN' : 'USER',
						clientMessageId: data.clientMessageId,
					}
					if (!existing) entries.push(saved)
					return { data: saved }
				}
				if (method === 'post' && ['accept', 'close'].includes(action)) {
					records.get(id).status = action === 'close' ? 'CLOSED' : 'ACTIVE'
					return { data: { ...records.get(id) } }
				}
				throw new Error(`Unexpected request ${method} ${path}`)
			},
		},
	}
	const Component = new Function(
		'modules',
		imports(compiled.content).replace('export default', 'return')
	)(modules)
	Component.render = new Function(
		'modules',
		imports(template.code).replace(
			'export function render',
			'return function render'
		)
	)(modules)
	let bindings
	const setup = Component.setup
	Component.setup = (props, context) => {
		bindings = setup(props, context)
		return bindings
	}
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(Component, { admin })
	app.component('RouterLink', {
		props: ['to'],
		setup:
			(props, { slots }) =>
			() =>
				vue.h('a', { href: props.to }, slots.default?.()),
	})
	app.mount(host)
	await flush()
	return {
		root: host,
		state: bindings,
		store,
		credential,
		calls,
		realtimes,
		records,
		entries,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}

test('member starts only explicitly confirmed text, without AI history or attachments', async (t) => {
	const view = await mountSupport()
	t.after(() => view.dispose())
	assert.match(view.root.textContent, /不会自动共享 AI 对话/)
	assert.equal(
		view.calls.some((call) => call.method === 'post'),
		false
	)
	view.state.draft.value = '如何上传课程资料？'
	await view.state.submitMessage(true)
	await flush()
	const opening = view.calls.find((call) => call.method === 'post')
	assert.equal(opening.path, '/conversations')
	assert.deepEqual(Object.keys(opening.data).sort(), [
		'clientMessageId',
		'content',
	])
	assert.equal(opening.data.content, '如何上传课程资料？')
	assert.match(view.root.textContent, /如何上传课程资料？/)
	assert.equal(view.state.draft.value, '')
})

test('unauthenticated and workspace-admin-to-global-console access make no requests or socket connections', async (t) => {
	for (const options of [
		{ login: { isLogin: false, role: null } },
		{
			admin: true,
			login: {
				isLogin: true,
				isAdmin: false,
				role: 'TENANT_ADMIN',
				username: 'manager',
			},
		},
	]) {
		const view = await mountSupport(options)
		t.after(() => view.dispose())
		assert.equal(view.calls.length, 0)
		assert.equal(view.realtimes[0].starts, 0)
		assert.equal(view.root.querySelector('textarea'), null)
	}
})

test('administrator accepts a selected waiting conversation before replying and can close it', async (t) => {
	const view = await mountSupport({
		admin: true,
		rows: [conversation()],
		history: [entry()],
	})
	t.after(() => view.dispose())
	view.state.draft.value = '请检查当前存储源。'
	await flush()
	assert.equal(Boolean(view.state.canSend.value), false)
	await view.state.acceptConversation()
	await flush()
	assert.equal(view.state.selected.value.status, 'ACTIVE')
	assert.equal(view.state.canSend.value, true)
	await view.state.submitMessage(false)
	await flush()
	assert.match(view.root.textContent, /请检查当前存储源/)
	view.state.confirmClose.value = 'first'
	await view.state.closeConversation()
	await flush()
	assert.equal(view.state.selected.value.status, 'CLOSED')
	assert.equal(view.root.querySelector('#support-reply'), null)
	assert.equal(
		view.calls.filter((call) => call.path.endsWith('/accept')).length,
		1
	)
	assert.equal(
		view.calls.filter((call) => call.path.endsWith('/close')).length,
		1
	)
})

test('unknown delivery retains the draft and reuses the client ID on a safe retry', async (t) => {
	let sends = 0
	const view = await mountSupport({
		rows: [conversation('first', 'ACTIVE')],
		handler(call) {
			if (
				call.method === 'post' &&
				call.path.endsWith('/messages') &&
				++sends === 1
			)
				return Promise.reject(new Error('Synthetic timeout'))
		},
	})
	t.after(() => view.dispose())
	view.state.draft.value = '这条消息需要确认'
	await view.state.submitMessage(false)
	await flush()
	assert.equal(view.state.draft.value, '这条消息需要确认')
	assert.match(view.root.textContent, /发送状态未确认/)
	await view.state.submitMessage(false)
	await flush()
	const writes = view.calls.filter(
		(call) => call.method === 'post' && call.path.endsWith('/messages')
	)
	assert.equal(writes.length, 2)
	assert.equal(writes[0].data.clientMessageId, writes[1].data.clientMessageId)
	assert.equal(view.state.draft.value, '')
})

test('identity or token change aborts requests and clears private conversation state before late replies', async (t) => {
	const delayed = deferred()
	let first = true
	const view = await mountSupport({
		rows: [conversation()],
		handler(call) {
			if (call.path === '/conversations' && first) {
				first = false
				return delayed.promise
			}
		},
	})
	t.after(() => view.dispose())
	const firstRequest = view.calls[0]
	view.state.draft.value = 'private draft'
	view.credential.value = 'replacement-token'
	await flush()
	assert.equal(firstRequest.config.signal.aborted, true)
	assert.equal(view.state.draft.value, '')
	delayed.resolve({ data: { items: [conversation('foreign-old')], total: 1 } })
	await flush()
	assert.doesNotMatch(view.root.textContent, /foreign-old/)
	view.store.loginInfo.isLogin = false
	await flush()
	assert.equal(view.state.messages.value.length, 0)
	assert.equal(view.state.selected.value, null)
	assert.ok(view.realtimes[0].stops >= 2)
})

test('queue refresh after an uncertain opening cannot silently discard the retained question', async (t) => {
	const view = await mountSupport({
		handler(call, { records }) {
			if (call.method === 'post' && call.path === '/conversations') {
				records.set('possibly-saved', conversation('possibly-saved'))
				return Promise.reject(new Error('Synthetic lost acknowledgement'))
			}
		},
	})
	t.after(() => view.dispose())
	view.state.draft.value = '保留我的问题'
	await view.state.submitMessage(true)
	await view.state.loadList()
	await flush()
	assert.equal(view.state.draft.value, '保留我的问题')
	assert.equal(view.state.selected.value, null)
	assert.match(view.root.textContent, /发送状态未确认/)
})

test('switching conversations ignores late history and never gets stuck behind an old load', async (t) => {
	const delayed = deferred()
	const view = await mountSupport({
		rows: [conversation('first'), conversation('second')],
		history: [entry(2, 'second', '第二个会话')],
		handler(call) {
			if (
				call.method === 'get' &&
				call.path === '/conversations/first/messages'
			)
				return delayed.promise
		},
	})
	t.after(() => view.dispose())
	await view.state.selectConversation(conversation('second'))
	await flush()
	assert.equal(view.state.selected.value.id, 'second')
	assert.equal(view.state.detailLoading.value, false)
	delayed.resolve({
		data: { items: [entry(1, 'first', '迟到的私人记录')], hasMore: false },
	})
	await flush()
	assert.doesNotMatch(view.root.textContent, /迟到的私人记录/)
	assert.match(view.root.textContent, /第二个会话/)
})

test('messages render as literal text and closed conversations cannot send', async (t) => {
	const payload = '<img src=x onerror=alert(1)>'
	const view = await mountSupport({
		rows: [conversation('first', 'CLOSED')],
		history: [entry(1, 'first', payload)],
	})
	t.after(() => view.dispose())
	assert.ok(view.root.textContent.includes(payload))
	assert.equal(view.root.querySelector('.message-list img'), null)
	view.state.draft.value = 'should not send'
	await view.state.submitMessage(false)
	assert.equal(
		view.calls.some((call) => call.method === 'post'),
		false
	)
	assert.match(view.root.textContent, /会话已结束/)
})
