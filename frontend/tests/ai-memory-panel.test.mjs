import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import {
	createAiMemoryManager,
	filterMemoryItems,
	normalizeMemoryState,
} from '../src/utils/ai-memory-panel.mjs'

const panelSource = await readFile(
	new URL('../src/components/ai/AiMemoryPanel.vue', import.meta.url),
	'utf8'
)
const panelCompiled = compileScript(parse(panelSource).descriptor, {
	id: 'memory-runtime',
}).content
function setupPanel(request, identity = 'alice') {
	const cleanups = []
	const modules = {
		vue: { ...vue, onBeforeUnmount: (callback) => cleanups.push(callback) },
		'~/api/home/ai-memory': { aiMemoryRequest: request },
		'~/utils/ai-memory-panel.mjs': { createAiMemoryManager, filterMemoryItems },
	}
	const executable = panelCompiled
		.replace(
			/import \{([\s\S]*?)\} from ['"]([^'"]+)['"]/g,
			(_whole, names, name) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					name
				)}]`
		)
		.replace('export default', 'return')
	const Panel = new Function('modules', executable)(modules)
	const props = vue.reactive({ identityKey: identity })
	const scope = vue.effectScope()
	const bindings = scope.run(() => Panel.setup(props, { expose() {} }))
	return {
		props,
		bindings,
		dispose() {
			cleanups.forEach((cleanup) => cleanup())
			scope.stop()
		},
	}
}

const sample = (items = [], enabled = true) => ({
	enabled,
	maxEntries: 50,
	maxChars: 500,
	items,
})
const item = (id, content = '讲解时请给出简短示例') => ({
	id,
	content,
	createdAt: '2026-10-22T00:00:00Z',
})
function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
function managerWith(request) {
	const calls = [],
		changes = []
	const manager = createAiMemoryManager({
		request: (...args) => {
			calls.push(args)
			return request(...args)
		},
		onChange: (state) => changes.push(state),
	})
	return { manager, calls, changes }
}

test('memory panel loads only for an explicit signed-in identity and never sends it as an owner', async () => {
	const { manager, calls } = managerWith(async () => ({ data: sample() }))
	await manager.setIdentity('')
	assert.equal(calls.length, 0)
	await manager.setIdentity('alice\n2')
	assert.equal(calls.length, 1)
	assert.deepEqual(calls[0].slice(0, 3), ['get', '', undefined])
	assert.ok(calls[0][3].signal instanceof AbortSignal)
	assert.equal(manager.state().loaded, true)
	assert.equal(manager.state().enabled, true)
	manager.dispose()
})

test('memory panel saves trimmed content without history, files or client ownership', async () => {
	const { manager, calls } = managerWith(async (method, _path, data) => ({
		data:
			method === 'post' ? sample([item('memory-one', data.content)]) : sample(),
	}))
	await manager.setIdentity('alice')
	assert.equal(await manager.remember('  我希望使用中文解释  '), true)
	assert.deepEqual(calls[1].slice(0, 3), [
		'post',
		'',
		{ content: '我希望使用中文解释' },
	])
	assert.equal(manager.state().items[0].content, '我希望使用中文解释')
	assert.match(manager.state().notice, /已保存/)
	manager.dispose()
})

test('memory panel ignores late old-account responses even if the transport ignores abort', async () => {
	const alice = deferred(),
		bob = deferred()
	const { manager, calls, changes } = managerWith(() =>
		calls.length === 1 ? alice.promise : bob.promise
	)
	const first = manager.setIdentity('alice')
	const second = manager.setIdentity('bob')
	assert.equal(calls[0][3].signal.aborted, true)
	bob.resolve({ data: sample([item('bob-memory', 'Bob 的偏好')]) })
	await second
	alice.resolve({ data: sample([item('alice-memory', 'Alice 的偏好')]) })
	await first
	assert.equal(manager.state().items[0].id, 'bob-memory')
	assert.ok(
		changes.every((state) =>
			state.items.every((entry) => entry.id !== 'alice-memory')
		)
	)
	manager.dispose()
})

test('logout immediately clears notes and confirmation, cancels mutation and ignores its result', async () => {
	const write = deferred()
	const { manager, calls } = managerWith(async (method) =>
		method === 'get' ? { data: sample([item('one')]) } : write.promise
	)
	await manager.setIdentity('alice')
	const saving = manager.remember('这条不应出现在退出后的面板')
	await manager.setIdentity('')
	assert.equal(calls[1][3].signal.aborted, true)
	assert.deepEqual(manager.state().items, [])
	assert.equal(manager.state().loaded, false)
	write.resolve({ data: sample([item('late')]) })
	assert.equal(await saving, false)
	assert.deepEqual(manager.state().items, [])
	manager.dispose()
})

test('unmount cancels requests and cannot publish a late state or error', async () => {
	const response = deferred()
	const { manager, calls, changes } = managerWith(() => response.promise)
	const loading = manager.setIdentity('alice')
	manager.dispose()
	const before = changes.length
	assert.equal(calls[0][3].signal.aborted, true)
	response.reject(new Error('late transport failure'))
	assert.equal(await loading, false)
	assert.equal(changes.length, before)
})

test('clear requires a separate explicit confirmation and cancel never sends DELETE', async () => {
	const { manager, calls } = managerWith(async (method) => ({
		data: method === 'get' ? sample([item('one')]) : sample(),
	}))
	await manager.setIdentity('alice')
	assert.equal(await manager.clear(), false)
	assert.equal(calls.length, 1)
	manager.askClear()
	assert.equal(manager.state().confirmClear, true)
	manager.cancelClear()
	assert.equal(await manager.clear(), false)
	assert.equal(calls.length, 1)
	manager.askClear()
	assert.equal(await manager.clear(), true)
	assert.deepEqual(calls[1].slice(0, 3), ['delete', '', undefined])
	assert.deepEqual(manager.state().items, [])
	manager.dispose()
})

test('changing accounts discards the old clear confirmation', async () => {
	const { manager, calls } = managerWith(async () => ({
		data: sample([item('one')]),
	}))
	await manager.setIdentity('alice')
	manager.askClear()
	await manager.setIdentity('bob')
	assert.equal(manager.state().confirmClear, false)
	assert.equal(await manager.clear(), false)
	assert.equal(calls.length, 2)
	manager.dispose()
})

test('busy state prevents duplicate writes and refreshes', async () => {
	const response = deferred()
	const { manager, calls } = managerWith(async (method) =>
		method === 'get' ? { data: sample() } : response.promise
	)
	await manager.setIdentity('alice')
	const first = manager.remember('偏好')
	assert.equal(await manager.remember('偏好'), false)
	assert.equal(await manager.setEnabled(false), false)
	assert.equal(await manager.load(), false)
	assert.equal(calls.length, 2)
	response.resolve({ data: sample([item('one')]) })
	await first
	assert.equal(manager.state().busy, false)
	manager.dispose()
})

test('disabled memory rejects saves but still allows deleting retained entries', async () => {
	const { manager, calls } = managerWith(async (method) => ({
		data: method === 'get' ? sample([item('one')], false) : sample([], false),
	}))
	await manager.setIdentity('alice')
	assert.equal(await manager.remember('偏好'), false)
	assert.equal(await manager.forget('not-owned'), false)
	assert.equal(await manager.forget('one'), true)
	assert.deepEqual(calls[1].slice(0, 3), ['delete', '/one', undefined])
	manager.dispose()
})

test('empty, overlength and full-list writes are blocked without requests', async () => {
	const { manager, calls } = managerWith(async () => ({ data: sample() }))
	await manager.setIdentity('alice')
	for (const content of ['', '  ', '字'.repeat(501)])
		assert.equal(await manager.remember(content), false)
	assert.equal(calls.length, 1)
	manager.dispose()
	const full = managerWith(async () => ({
		data: sample(Array.from({ length: 50 }, (_unused, i) => item(String(i)))),
	}))
	await full.manager.setIdentity('alice')
	assert.equal(await full.manager.remember('新增偏好'), false)
	assert.equal(full.calls.length, 1)
	full.manager.dispose()
})

test('failed writes preserve draft state and require a refresh before another mutation', async () => {
	const { manager, calls } = managerWith(async (method) => {
		if (method !== 'get') throw { data: { msg: '记忆已更新，请刷新' } }
		return { data: sample() }
	})
	await manager.setIdentity('alice')
	assert.equal(await manager.remember('偏好'), false)
	assert.match(manager.state().error, /请刷新/)
	assert.equal(await manager.remember('再次保存'), false)
	assert.equal(calls.length, 2)
	await manager.load()
	assert.equal(manager.state().error, '')
	assert.equal(manager.state().loaded, true)
	manager.dispose()
})

test('malformed server notes fail closed rather than rendering uncontrolled lists', async () => {
	for (const value of [
		null,
		{},
		sample([{ id: 'one', content: '字'.repeat(501) }]),
		sample(Array.from({ length: 51 }, (_unused, i) => item(String(i)))),
	]) {
		assert.throws(() => normalizeMemoryState(value))
	}
	const { manager } = managerWith(async () => ({ data: null }))
	await manager.setIdentity('alice')
	assert.equal(manager.state().loaded, false)
	assert.ok(manager.state().error)
	assert.deepEqual(manager.state().items, [])
	manager.dispose()
})

test('actual panel setup saves notes and synchronously clears its draft on identity change', async () => {
	const panel = setupPanel(async (method, _path, data) => ({
		data: method === 'post' ? sample([item('one', data.content)]) : sample(),
	}))
	try {
		await Promise.resolve()
		assert.equal(panel.bindings.state.value.loaded, true)
		panel.bindings.draft.value = '我希望回答简洁'
		assert.equal(panel.bindings.canSave.value, true)
		await panel.bindings.saveMemory()
		assert.equal(panel.bindings.draft.value, '')
		assert.equal(panel.bindings.state.value.items[0].content, '我希望回答简洁')
		panel.bindings.draft.value = '不应泄漏给新账号'
		panel.props.identityKey = 'bob'
		assert.equal(panel.bindings.draft.value, '')
		assert.deepEqual(panel.bindings.state.value.items, [])
	} finally {
		panel.dispose()
	}
})

test('actual panel unmount aborts its request and does not accept a late account response', async () => {
	const pending = deferred(),
		requests = []
	const panel = setupPanel((...args) => {
		requests.push(args)
		return pending.promise
	})
	assert.equal(requests.length, 1)
	panel.dispose()
	assert.equal(requests[0][3].signal.aborted, true)
	pending.resolve({ data: sample([item('late')]) })
	await Promise.resolve()
	assert.deepEqual(panel.bindings.state.value.items, [])
})

test('memory view compiles, escapes notes, provides real confirmation and cancels on unmount', async () => {
	const source = await readFile(
		new URL('../src/components/ai/AiMemoryPanel.vue', import.meta.url),
		'utf8'
	)
	const { descriptor, errors } = parse(source)
	assert.deepEqual(errors, [])
	assert.doesNotThrow(() =>
		compileScript(descriptor, { id: 'memory-panel-test', inlineTemplate: true })
	)
	assert.match(source, /\{\{ item\.content \}\}/)
	assert.doesNotMatch(source, /v-html|localStorage|sessionStorage/)
	assert.match(source, /确认清空/)
	assert.match(source, /manager\.askClear\(\)/)
	assert.match(
		source,
		/onBeforeUnmount\(\(\) => \{[\s\S]*?manager\.dispose\(\)/
	)
	assert.match(source, /flush: 'sync'/)
	assert.match(source, /清空对话不会删除长期记忆/)
	assert.match(source, /删除记忆不会清除当前聊天，已发送的模型请求无法撤回/)
})

test('assistant adds only a scoped management entry and uses existing authenticated API wrapper', async () => {
	const assistant = await readFile(
		new URL('../src/components/ai/AiAssistant.vue', import.meta.url),
		'utf8'
	)
	const api = await readFile(
		new URL('../src/api/home/ai-memory.js', import.meta.url),
		'utf8'
	)
	assert.match(assistant, /aria-label="管理长期记忆"/)
	assert.match(assistant, /<i-lucide-brain \/>/)
	assert.doesNotMatch(assistant, /i-lucide-notebook-pen/)
	assert.match(
		assistant,
		/<AiMemoryPanel[^>]+:identity-key="assistantIdentity"/
	)
	assert.match(
		assistant,
		/function closePanel\(\) \{\s*memoryOpen.value = false/
	)
	assert.match(api, /from '~\/http\/request'/)
	assert.match(api, /\/api\/ai\/memories/)
	assert.doesNotMatch(
		api,
		/localStorage|sessionStorage|Authorization|ownerUserId/
	)
})

test('editing uses an owned ID PUT and keeps count and creation metadata supplied by the server', async () => {
	const original = item('one', '原来的偏好')
	const { manager, calls } = managerWith(async (method, _path, data) => ({
		data: sample([
			{
				...original,
				content: method === 'put' ? data.content : original.content,
			},
		]),
	}))
	await manager.setIdentity('alice')
	assert.equal(await manager.update('one', '  更新后的偏好  '), true)
	assert.deepEqual(calls[1].slice(0, 3), [
		'put',
		'/one',
		{ content: '更新后的偏好', expectedContent: '原来的偏好' },
	])
	assert.deepEqual(manager.state().items, [
		{ ...original, content: '更新后的偏好' },
	])
	assert.match(manager.state().notice, /已更新/)
	assert.equal(await manager.update('not-owned', '不能修改'), false)
	assert.equal(calls.length, 2)
	manager.dispose()
})

test('disabled and invalid-content edits never issue a mutation', async () => {
	const disabled = managerWith(async () => ({
		data: sample([item('one')], false),
	}))
	await disabled.manager.setIdentity('alice')
	assert.equal(await disabled.manager.update('one', '新偏好'), false)
	assert.equal(disabled.calls.length, 1)
	disabled.manager.dispose()
	const active = managerWith(async () => ({ data: sample([item('one')]) }))
	await active.manager.setIdentity('alice')
	for (const value of ['', '  ', '字'.repeat(501)])
		assert.equal(await active.manager.update('one', value), false)
	assert.equal(active.calls.length, 1)
	active.manager.dispose()
})

test('explicit content rejection is shown inline and can be corrected without another GET', async () => {
	let attempts = 0
	const { manager, calls } = managerWith(async (method, _path, data) => {
		if (method === 'get') return { data: sample([item('one')]) }
		if (++attempts === 1)
			throw {
				response: {
					status: 400,
					data: {
						code: '50000',
						msg: '已有相同内容的记忆，请修改内容后再保存',
					},
				},
			}
		return { data: sample([item('one', data.content)]) }
	})
	await manager.setIdentity('alice')
	assert.equal(await manager.update('one', '重复偏好'), false)
	assert.equal(manager.state().error, '')
	assert.match(manager.state().formError, /已有相同内容/)
	manager.clearFormError()
	assert.equal(manager.state().formError, '')
	assert.equal(await manager.update('one', '修正后的偏好'), true)
	assert.equal(calls.length, 3)
	assert.equal(manager.state().items[0].content, '修正后的偏好')
	manager.dispose()
})

test('structured content validation is recoverable but unrelated parameter failures are not', async () => {
	for (const field of ['content', 'id']) {
		const { manager, calls } = managerWith(async (method) => {
			if (method === 'get') return { data: sample() }
			throw {
				response: {
					status: 400,
					data: {
						code: '41000',
						msg: '请求参数异常',
						data: { [field]: '输入不能超过限制' },
					},
				},
			}
		})
		await manager.setIdentity('alice')
		assert.equal(await manager.remember('偏好'), false)
		assert.equal(Boolean(manager.state().formError), field === 'content')
		assert.equal(Boolean(manager.state().error), field !== 'content')
		if (field === 'id') assert.equal(await manager.remember('重试'), false)
		assert.equal(calls.length, 2)
		manager.dispose()
	}
})

test('network, server and CAS failures lock further mutations until an explicit refresh', async () => {
	for (const failure of [
		Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }),
		{
			response: {
				status: 503,
				data: { msg: '记忆内容不能为空且不能超过 500 个字符' },
			},
		},
		{
			response: {
				status: 400,
				data: { msg: '记忆已在其他窗口更新，请刷新后重试' },
			},
		},
	]) {
		const { manager, calls } = managerWith(async (method) => {
			if (method === 'get') return { data: sample([item('one')]) }
			throw failure
		})
		await manager.setIdentity('alice')
		assert.equal(await manager.update('one', '新偏好'), false)
		assert.ok(manager.state().error)
		assert.equal(manager.state().formError, '')
		assert.equal(await manager.update('one', '重复保存'), false)
		assert.equal(calls.length, 2)
		await manager.load()
		assert.equal(manager.state().error, '')
		assert.equal(calls.length, 3)
		manager.dispose()
	}
})

test('authentication failures remove displayed notes and do not permit inline retry', async () => {
	const { manager } = managerWith(async (method) => {
		if (method === 'get') return { data: sample([item('one')]) }
		throw {
			response: { status: 401, data: { code: '42000', msg: '请重新登录' } },
		}
	})
	await manager.setIdentity('alice')
	assert.equal(await manager.update('one', '新偏好'), false)
	assert.deepEqual(manager.state().items, [])
	assert.equal(manager.state().loaded, false)
	assert.equal(manager.state().enabled, false)
	assert.equal(manager.state().formError, '')
	assert.equal(await manager.update('one', '重试'), false)
	manager.dispose()
})

test('local filter matches every keyword without changing or requesting account data', async () => {
	const entries = [
		item('one', 'Java 入门 示例'),
		item('two', 'Java 并发'),
		item('three', '数据库 入门'),
	]
	const { manager, calls } = managerWith(async () => ({
		data: sample(entries),
	}))
	await manager.setIdentity('alice')
	assert.deepEqual(
		filterMemoryItems(manager.state().items, 'JAVA 示例').map(
			(entry) => entry.id
		),
		['one']
	)
	assert.deepEqual(
		filterMemoryItems(manager.state().items, '入门').map((entry) => entry.id),
		['one', 'three']
	)
	assert.deepEqual(filterMemoryItems(manager.state().items, '不存在'), [])
	assert.deepEqual(filterMemoryItems(manager.state().items, '  '), entries)
	assert.deepEqual(manager.state().items, entries)
	assert.equal(calls.length, 1)
	manager.dispose()
})

test('actual panel edits and cancels locally; full store still permits editing an existing note', async () => {
	const entries = Array.from({ length: 50 }, (_value, i) =>
		item(String(i), `偏好 ${i}`)
	)
	const calls = []
	const panel = setupPanel(async (method, path, data) => {
		calls.push([method, path, data])
		return {
			data: sample(
				entries.map((entry) =>
					method === 'put' && path === `/${entry.id}`
						? { ...entry, content: data.content }
						: entry
				)
			),
		}
	})
	try {
		await Promise.resolve()
		panel.bindings.beginEdit(entries[0])
		assert.equal(panel.bindings.editingId.value, '0')
		assert.equal(panel.bindings.draft.value, '偏好 0')
		panel.bindings.draft.value = '更新偏好'
		assert.equal(panel.bindings.canSave.value, true)
		await panel.bindings.saveMemory()
		assert.deepEqual(calls[1], [
			'put',
			'/0',
			{ content: '更新偏好', expectedContent: '偏好 0' },
		])
		assert.equal(panel.bindings.editingId.value, '')
		assert.equal(panel.bindings.editingOriginal.value, '')
		assert.equal(panel.bindings.draft.value, '')
		panel.bindings.beginEdit(entries[1])
		panel.bindings.cancelEdit()
		assert.equal(panel.bindings.editingId.value, '')
		assert.equal(panel.bindings.editingOriginal.value, '')
		assert.equal(panel.bindings.draft.value, '')
		assert.equal(calls.length, 2)
	} finally {
		panel.dispose()
	}
})

test('account change clears the edit and filter synchronously and suppresses a late PUT', async () => {
	const updating = deferred()
	const panel = setupPanel(async (method) =>
		method === 'get' ? { data: sample([item('one')]) } : updating.promise
	)
	try {
		await Promise.resolve()
		panel.bindings.beginEdit(item('one'))
		panel.bindings.draft.value = '旧账号的编辑'
		panel.bindings.filterQuery.value = '旧账号'
		const saving = panel.bindings.saveMemory()
		panel.props.identityKey = 'bob'
		assert.equal(panel.bindings.editingId.value, '')
		assert.equal(panel.bindings.draft.value, '')
		assert.equal(panel.bindings.filterQuery.value, '')
		assert.deepEqual(panel.bindings.state.value.items, [])
		updating.resolve({ data: sample([item('old', '不能显示给新账号')]) })
		await saving
		assert.ok(
			panel.bindings.state.value.items.every((entry) => entry.id !== 'old')
		)
	} finally {
		panel.dispose()
	}
})

test('unmount removes local edit text and filtering while aborting its pending save', async () => {
	const updating = deferred()
	const calls = []
	const panel = setupPanel(async (...args) => {
		calls.push(args)
		return args[0] === 'get'
			? { data: sample([item('one')]) }
			: updating.promise
	})
	await Promise.resolve()
	panel.bindings.beginEdit(item('one'))
	panel.bindings.draft.value = '关闭时不保留'
	panel.bindings.filterQuery.value = '关闭'
	const saving = panel.bindings.saveMemory()
	panel.dispose()
	assert.equal(calls[1][3].signal.aborted, true)
	assert.equal(panel.bindings.editingId.value, '')
	assert.equal(panel.bindings.editingOriginal.value, '')
	assert.equal(panel.bindings.draft.value, '')
	assert.equal(panel.bindings.filterQuery.value, '')
	updating.resolve({ data: sample([item('one', '迟到响应')]) })
	await saving
	assert.equal(panel.bindings.draft.value, '')
})

test('actual panel preserves a rejected draft for correction instead of forcing a reload', async () => {
	let fail = true
	const panel = setupPanel(async (method, _path, data) => {
		if (method === 'get') return { data: sample() }
		if (fail)
			throw {
				response: {
					status: 400,
					data: { msg: '请勿在长期记忆中保存密码、验证码、密钥或令牌' },
				},
			}
		return { data: sample([item('one', data.content)]) }
	})
	try {
		await Promise.resolve()
		panel.bindings.draft.value = '输入需要修正'
		await panel.bindings.saveMemory()
		assert.equal(panel.bindings.draft.value, '输入需要修正')
		assert.equal(panel.bindings.state.value.error, '')
		assert.ok(panel.bindings.state.value.formError)
		panel.bindings.draft.value = '改为普通偏好'
		assert.equal(panel.bindings.canSave.value, true)
		fail = false
		await panel.bindings.saveMemory()
		assert.equal(panel.bindings.draft.value, '')
		assert.equal(panel.bindings.state.value.formError, '')
	} finally {
		panel.dispose()
	}
})

test('refreshing during editing never rebases the original content used to reject a stale overwrite', async () => {
	const original = item('one', '最初保存的偏好')
	const calls = []
	let reads = 0
	const panel = setupPanel(async (method, path, data) => {
		calls.push([method, path, data])
		if (method === 'get')
			return {
				data: sample([
					{
						...original,
						content: ++reads === 1 ? original.content : '另一个窗口的新偏好',
					},
				]),
			}
		throw {
			response: {
				status: 400,
				data: { msg: '这条记忆已在其他窗口修改，请刷新后重试' },
			},
		}
	})
	try {
		await Promise.resolve()
		panel.bindings.beginEdit(original)
		panel.bindings.draft.value = '本窗口的草稿'
		await panel.bindings.saveMemory()
		assert.deepEqual(calls[1], [
			'put',
			'/one',
			{ content: '本窗口的草稿', expectedContent: original.content },
		])
		assert.match(panel.bindings.state.value.error, /其他窗口修改/)
		await panel.bindings.manager.load()
		assert.equal(
			panel.bindings.state.value.items[0].content,
			'另一个窗口的新偏好'
		)
		assert.equal(panel.bindings.editingOriginal.value, original.content)
		assert.equal(panel.bindings.editStale.value, true)
		assert.equal(panel.bindings.canSave.value, false)
		await panel.bindings.saveMemory()
		assert.equal(
			calls.length,
			3,
			'Refresh must not enable repeated conflicting PUTs'
		)
		assert.equal(panel.bindings.state.value.error, '')
		assert.equal(panel.bindings.state.value.formError, '')
		assert.equal(panel.bindings.draft.value, '本窗口的草稿')
		assert.equal(panel.bindings.canSave.value, false)
		panel.bindings.cancelEdit()
		assert.equal(panel.bindings.editingOriginal.value, '')
		assert.equal(panel.bindings.editStale.value, false)
		panel.bindings.beginEdit(panel.bindings.state.value.items[0])
		assert.equal(panel.bindings.editingOriginal.value, '另一个窗口的新偏好')
		assert.equal(panel.bindings.editStale.value, false)
		assert.equal(panel.bindings.canSave.value, true)
	} finally {
		panel.dispose()
	}
})

test('editing notice explains stale content without silently rebasing or discarding the draft', async () => {
	const original = item('one', '最初的偏好')
	let reads = 0
	const panel = setupPanel(async () => ({
		data: sample([reads++ ? item('one', '新的偏好') : original]),
	}))
	try {
		await Promise.resolve()
		panel.bindings.beginEdit(original)
		panel.bindings.draft.value = '仍需要复制的草稿'
		assert.equal(panel.bindings.editStale.value, false)
		await panel.bindings.manager.load()
		assert.equal(panel.bindings.editStale.value, true)
		assert.equal(panel.bindings.editingOriginal.value, original.content)
		assert.equal(panel.bindings.draft.value, '仍需要复制的草稿')
		assert.equal(panel.bindings.canSave.value, false)
		assert.match(
			panelSource,
			/这条记忆已更新，请取消编辑，查看最新内容后重新编辑；草稿保留供复制/
		)
		assert.match(panelSource, /v-if="editStale"/)
	} finally {
		panel.dispose()
	}
})

test('a successful authenticated refresh resets denial state without reviving the discarded draft', async () => {
	const original = item('one', '当前账号的偏好')
	let rejectSave = true
	const panel = setupPanel(async (method, _path, data) => {
		if (method === 'get') return { data: sample([original]) }
		if (rejectSave) {
			rejectSave = false
			throw { response: { status: 401, data: { code: '42000', msg: '请重新登录' } } }
		}
		return { data: sample([original, item('two', data.content)]) }
	})
	try {
		await Promise.resolve()
		panel.bindings.draft.value = '鉴权失败时必须丢弃的草稿'
		await panel.bindings.saveMemory()
		assert.equal(panel.bindings.state.value.identityDenied, true)
		assert.equal(panel.bindings.state.value.items.length, 0)
		assert.equal(panel.bindings.draft.value, '')
		await panel.bindings.manager.load()
		assert.equal(panel.bindings.state.value.identityDenied, false)
		assert.equal(panel.bindings.draft.value, '')
		panel.bindings.draft.value = '重新确认身份后填写的新草稿'
		assert.equal(panel.bindings.canSave.value, true)
		await panel.bindings.saveMemory()
		assert.equal(panel.bindings.state.value.items[1].content, '重新确认身份后填写的新草稿')
		assert.equal(panel.bindings.state.value.identityDenied, false)
	} finally {
		panel.dispose()
	}
})
