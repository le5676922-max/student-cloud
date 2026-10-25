import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as supportTools from '../src/utils/support-realtime.mjs'

// Real Vue component/template in an isolated DOM; only synthetic identity and transport.
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
	url: 'https://cloud.invalid/',
})
for (const key of [
	'window',
	'document',
	'Document',
	'Node',
	'Element',
	'HTMLElement',
	'HTMLTextAreaElement',
	'SVGElement',
	'Event',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
const descriptor = parse(
	await readFile(
		new URL(
			'../src/components/support/AssistantSupportChat.vue',
			import.meta.url
		),
		'utf8'
	)
).descriptor
const compiled = compileScript(descriptor, { id: 'assistant-support-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'AssistantSupportChat.vue',
	id: 'assistant-support-runtime',
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
	for (let index = 0; index < 20; index++) {
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
const conversation = (status = 'WAITING') => ({
	id: 'confirmed-conversation',
	status,
	updatedAt: 20,
	createdAt: 10,
})
const entry = (id = 1, content = '确认的问题', role = 'USER') => ({
	id,
	conversationId: 'confirmed-conversation',
	content,
	senderRole: role,
	clientMessageId: `message-${id}`,
	createdAt: 30,
})
async function mountSupport({
	login,
	issue = '我上传失败，需要人工帮助。',
	key = 'confirmed-unique-id',
	handler,
} = {}) {
	const store = vue.reactive({
		loginInfo: login || {
			isLogin: true,
			role: 'USER',
			username: 'member',
			tenantId: 9,
		},
	})
	const credential = vue.ref('synthetic-token')
	const props = vue.reactive({
		initialIssue: issue,
		clientMessageId: key,
		identityKey: 'confirmed-identity',
	})
	const calls = [],
		realtimes = [],
		emitted = []
	const record = conversation(),
		entries = []
	let nextId = 1
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
				const realtime = {
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
				realtimes.push(realtime)
				return realtime
			},
		},
		'~/api/home/support': {
			supportRequest: async (method, path, data, config) => {
				const call = { method, path, data, config }
				calls.push(call)
				if (handler) {
					const result = handler(call, { record, entries })
					if (result !== undefined) return result
				}
				if (method === 'post' && path === '/conversations') {
					if (
						!entries.some(
							(message) => message.clientMessageId === data.clientMessageId
						)
					)
						entries.push({
							...entry(nextId++, data.content),
							clientMessageId: data.clientMessageId,
						})
					return { data: { ...record } }
				}
				if (
					method === 'get' &&
					path === '/conversations/confirmed-conversation'
				)
					return { data: { ...record } }
				if (method === 'get' && path.endsWith('/messages'))
					return {
						data: {
							items: entries.filter(
								(message) =>
									(!data.afterId || message.id > data.afterId) &&
									(!data.beforeId || message.id < data.beforeId)
							),
							hasMore: false,
						},
					}
				if (method === 'post' && path.endsWith('/messages')) {
					const previous = entries.find(
						(message) => message.clientMessageId === data.clientMessageId
					)
					const saved = previous || {
						...entry(nextId++, data.content),
						clientMessageId: data.clientMessageId,
					}
					if (!previous) entries.push(saved)
					return { data: saved }
				}
				if (method === 'post' && path.endsWith('/close')) {
					record.status = 'CLOSED'
					return { data: { ...record } }
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
	Component.setup = (values, context) => {
		bindings = setup(values, context)
		return bindings
	}
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp({
		setup: () => () =>
			vue.h(Component, {
				...props,
				onBack: () => emitted.push(['back']),
				onStarted: (value) => emitted.push(['started', value]),
				onClosed: (value) => emitted.push(['closed', value]),
			}),
	})
	app.mount(host)
	await flush()
	return {
		root: host,
		state: bindings,
		calls,
		realtimes,
		store,
		credential,
		props,
		record,
		entries,
		emitted,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}

test('confirmed handoff sends only the explicitly confirmed issue in the same assistant window', async (t) => {
	const view = await mountSupport()
	t.after(() => view.dispose())
	const open = view.calls.find((call) => call.method === 'post')
	assert.equal(open.path, '/conversations')
	assert.deepEqual(open.data, {
		content: '我上传失败，需要人工帮助。',
		clientMessageId: 'confirmed-unique-id',
	})
	assert.match(view.root.textContent, /人工客服/)
	assert.match(view.root.textContent, /已进入客服队列/)
	assert.match(view.root.textContent, /不自动共享 AI 对话、附件或长期记忆/)
	assert.equal(view.root.querySelector('a'), null)
	assert.equal(view.realtimes[0].starts, 1)
	assert.equal(view.emitted[0][0], 'started')
	view.root.querySelector('.assistant-support-heading button').click()
	await flush()
	assert.ok(view.emitted.some(([name]) => name === 'back'))
	assert.equal(
		view.calls.some((call) => call.path.endsWith('/close')),
		false
	)
})

test('missing confirmation data, anonymous users and global admins never open a conversation', async (t) => {
	for (const options of [
		{ issue: '' },
		{ key: '' },
		{ login: { isLogin: false, role: 'USER' } },
		{ login: { isLogin: true, role: 'ADMIN', username: 'admin' } },
	]) {
		const view = await mountSupport(options)
		t.after(() => view.dispose())
		assert.equal(view.calls.length, 0)
		assert.equal(view.realtimes[0].starts, 0)
	}
})

test('uncertain initial submission retries the parent confirmation key without copying other chat context', async (t) => {
	let tries = 0
	const view = await mountSupport({
		handler(call) {
			if (
				call.method === 'post' &&
				call.path === '/conversations' &&
				++tries === 1
			)
				return Promise.reject(new Error('Synthetic lost acknowledgement'))
		},
	})
	t.after(() => view.dispose())
	assert.match(view.root.textContent, /转接状态未确认/)
	await view.state.startConversation()
	await flush()
	const writes = view.calls.filter((call) => call.method === 'post')
	assert.equal(writes.length, 2)
	assert.equal(writes[0].data.clientMessageId, writes[1].data.clientMessageId)
	assert.equal(view.entries.length, 1)
})

test('reply retry retains the draft and idempotency key and does not send through WebSocket', async (t) => {
	let tries = 0
	const view = await mountSupport({
		handler(call) {
			if (
				call.method === 'post' &&
				call.path.endsWith('/messages') &&
				++tries === 1
			)
				return Promise.reject(new Error('Synthetic timeout'))
		},
	})
	t.after(() => view.dispose())
	view.state.draft.value = '补充说明'
	await view.state.sendMessage()
	await flush()
	assert.equal(view.state.draft.value, '补充说明')
	assert.match(view.root.textContent, /发送状态未确认/)
	await view.state.sendMessage()
	await flush()
	const writes = view.calls.filter(
		(call) => call.method === 'post' && call.path.endsWith('/messages')
	)
	assert.equal(writes.length, 2)
	assert.equal(writes[0].data.clientMessageId, writes[1].data.clientMessageId)
	assert.equal(view.state.draft.value, '')
	assert.match(view.root.textContent, /补充说明/)
})

test('metadata notifications load administrator replies and closed state in the inline transcript', async (t) => {
	const view = await mountSupport()
	t.after(() => view.dispose())
	view.record.status = 'ACTIVE'
	view.entries.push(entry(2, '你好，请描述上传失败的提示。', 'ADMIN'))
	view.realtimes[0].options.onState('connected')
	view.realtimes[0].options.onChange('confirmed-conversation')
	await new Promise((resolve) => setTimeout(resolve, 230))
	await flush()
	assert.match(view.root.textContent, /你好，请描述上传失败的提示/)
	assert.match(view.root.textContent, /人工客服 · 全局管理员/)
	assert.match(view.root.textContent, /沟通中/)
	view.record.status = 'CLOSED'
	view.realtimes[0].options.onChange('confirmed-conversation')
	await new Promise((resolve) => setTimeout(resolve, 230))
	await flush()
	assert.equal(view.root.querySelector('textarea'), null)
	assert.match(view.root.textContent, /人工会话已结束/)
	assert.equal(view.emitted.filter(([name]) => name === 'closed').length, 1)
	assert.equal(
		view.emitted.find(([name]) => name === 'closed')[1].status,
		'CLOSED'
	)
	await view.state.refreshMessages()
	assert.equal(view.emitted.filter(([name]) => name === 'closed').length, 1)
	const writes = view.calls.filter((call) => call.method === 'post').length
	view.state.draft.value = 'must not send'
	await view.state.sendMessage()
	assert.equal(
		view.calls.filter((call) => call.method === 'post').length,
		writes
	)
})

test('identity changes abort old requests and never submit the previous account confirmed issue for another account', async (t) => {
	const delayed = deferred()
	const view = await mountSupport({
		handler(call) {
			if (call.path === '/conversations') return delayed.promise
		},
	})
	t.after(() => view.dispose())
	const first = view.calls[0]
	view.store.loginInfo.username = 'other-member'
	view.credential.value = 'other-synthetic-token'
	await flush()
	assert.equal(first.config.signal.aborted, true)
	assert.equal(view.state.invalidated.value, true)
	delayed.resolve({ data: conversation() })
	await flush()
	assert.equal(view.state.conversation.value, null)
	assert.equal(view.state.messages.value.length, 0)
	assert.equal(view.calls.length, 1)
	assert.equal(view.realtimes[0].starts, 0)
	assert.equal(view.emitted.length, 0)
	assert.match(view.root.textContent, /登录身份已变化/)
})

test('closing needs explicit confirmation; returning to AI does not close, and HTML stays text', async (t) => {
	const malicious = '<img src=x onerror=alert(1)>'
	const view = await mountSupport({ issue: malicious })
	t.after(() => view.dispose())
	assert.ok(view.root.textContent.includes(malicious))
	assert.equal(view.root.querySelector('.assistant-support-history img'), null)
	await view.state.closeConversation()
	assert.equal(
		view.calls.some((call) => call.path.endsWith('/close')),
		false
	)
	view.state.confirmClose.value = true
	await view.state.closeConversation()
	await flush()
	assert.equal(view.state.conversation.value.status, 'CLOSED')
	assert.equal(view.root.querySelector('textarea'), null)
	assert.ok(view.emitted.some(([name]) => name === 'closed'))
})

test('unmount aborts unfinished work and stops realtime callbacks', async () => {
	const delayed = deferred()
	const view = await mountSupport({
		handler(call) {
			if (call.path.endsWith('/messages')) return delayed.promise
		},
	})
	const pending = view.calls.find((call) => call.path.endsWith('/messages'))
	view.dispose()
	assert.equal(pending.config.signal.aborted, true)
	assert.ok(view.realtimes[0].stops > 0)
	delayed.resolve({
		data: { items: [entry(2, 'late reply', 'ADMIN')], hasMore: false },
	})
	await flush()
	assert.equal(view.state.messages.value.length, 0)
})

test('a delayed refresh cannot reopen a conversation already closed by the member', async (t) => {
	const delayed = deferred()
	let detailRequests = 0
	const view = await mountSupport({
		handler(call) {
			if (
				call.method === 'get' &&
				call.path === '/conversations/confirmed-conversation' &&
				++detailRequests === 2
			)
				return delayed.promise
		},
	})
	t.after(() => view.dispose())
	const refreshing = view.state.refreshMessages()
	view.state.confirmClose.value = true
	await view.state.closeConversation()
	delayed.resolve({ data: conversation('ACTIVE') })
	await refreshing
	await flush()
	assert.equal(view.state.conversation.value.status, 'CLOSED')
	assert.equal(view.root.querySelector('textarea'), null)
})
