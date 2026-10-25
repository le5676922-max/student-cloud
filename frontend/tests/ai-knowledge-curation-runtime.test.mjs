import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate, compileStyle } from 'vue/compiler-sfc'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of ['window', 'document', 'Document', 'Node', 'Element', 'HTMLElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'HTMLSelectElement', 'SVGElement', 'Event']) globalThis[key] = dom.window[key]
const vue = await import('vue')
after(() => dom.window.close())
const source = await readFile(new URL('../src/components/admin/AiKnowledgeCuration.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
const script = compileScript(descriptor, { id: 'knowledge-curation-test' })
const template = compileTemplate({ source: descriptor.template.content, filename: 'AiKnowledgeCuration.vue', id: 'knowledge-curation-test', compilerOptions: { bindingMetadata: script.bindings } })
assert.deepEqual(template.errors, [])
const transform = code => code.replace(/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g, (_match, names, module) => `const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(module)}]`).replace(/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g, (_match, name, module) => `const ${name} = modules[${JSON.stringify(module)}]`)
const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await vue.nextTick() } }
const deferred = () => { let resolve, reject; const promise = new Promise((done, fail) => { resolve = done; reject = fail }); return { resolve, reject, promise } }
const id = 'a'.repeat(32)
const detail = () => ({ id, question: '资料上传失败怎么办？', answer: '原模型错误回答不得入库', status: 'RESOLVED', correctedAnswer: '确认目录授权后重新上传。', tenantId: 9, updatedAt: 10000 })
async function mount({ role = 'ADMIN', state, refine, publish, withdraw, history, savedStatus = 'RESOLVED', answer } = {}) {
	const store = vue.reactive({ loginInfo: { isLogin: true, role, username: 'fixture-admin' } }), credential = vue.ref('fixture-token')
	const calls = { state: [], refine: [], publish: [], withdraw: [], history: [], apply: [] }
	const props = vue.reactive({ detail: detail(), answer: answer ?? detail().correctedAnswer, savedStatus })
	const apiCall = (name, implementation, fallback) => (...args) => { calls[name].push(args); return implementation ? implementation(...args) : Promise.resolve({ data: fallback }) }
	const modules = {
		vue, '@vueuse/core': { useStorage: () => credential }, '~/stores/storage-config': () => store,
		'~/utils/latest-request.mjs': { createLatestRequest },
		'~/api/admin/admin-ai-feedback': {
			getAiKnowledgeStateReq: apiCall('state', state, { feedbackId: id, version: 0, state: 'UNPUBLISHED', updatedAt: 0 }),
			refineAiKnowledgeReq: apiCall('refine', refine, { answer: '<img src=x onerror=alert(1)>整理草稿', requiresReview: true, sourceUpdatedAt: 10000 }),
			publishAiKnowledgeReq: apiCall('publish', publish, [{ feedbackId: id, version: 1, state: 'PUBLISHED', updatedAt: 20000 }]),
			withdrawAiKnowledgeReq: apiCall('withdraw', withdraw, { feedbackId: id, version: 1, state: 'WITHDRAWN', updatedAt: 30000 }),
			getAiKnowledgeHistoryReq: apiCall('history', history, []),
		},
	}
	const Component = new Function('modules', transform(script.content).replace('export default', 'return'))(modules)
	Component.render = new Function('modules', transform(template.code).replace('export function render', 'return function render'))(modules)
	const host = document.createElement('div'); document.body.append(host)
	let child
	const app = vue.createApp({ render: () => vue.h(Component, { ...props, ref: value => { child = value }, onApply: value => calls.apply.push(value) }) })
	app.mount(host); await flush()
	return { host, api: child.$.setupState, props, store, credential, calls, dispose() { app.unmount(); host.remove() } }
}
const button = (app, label) => [...app.host.querySelectorAll('button')].find(item => item.textContent.includes(label))

test('AI editing is explicit and returns a safe draft without saving, publishing or ingesting the original answer', async () => {
	const app = await mount()
	try {
		assert.equal(app.calls.state.length, 1); assert.equal(app.calls.refine.length, 0); assert.equal(app.calls.publish.length, 0)
		button(app, 'AI 辅助润色').click(); await flush()
		assert.deepEqual(app.calls.refine[0][0], { feedbackId: id, expectedUpdatedAt: 10000, administratorAnswer: detail().correctedAnswer })
		assert.equal(app.host.querySelector('img'), null)
		assert.match(app.host.querySelector('textarea').value, /整理草稿/)
		button(app, '采用草稿').click(); await flush()
		assert.deepEqual(app.calls.apply, ['<img src=x onerror=alert(1)>整理草稿'])
		assert.equal(app.calls.publish.length, 0); assert.equal(app.api.preview, null)
	} finally { app.dispose() }
})

test('publishing requires a saved human correction and explicit global-scope confirmation; withdrawal uses current version', async () => {
	const app = await mount({ savedStatus: 'OPEN' })
	try {
		assert.equal(app.api.canPublish, false)
		await app.api.publish(); assert.equal(app.calls.publish.length, 0)
		app.props.savedStatus = 'RESOLVED'; await flush()
		assert.equal(app.api.canPublish, true)
		app.api.scope = 'GLOBAL'; await flush(); assert.equal(app.api.canPublish, false)
		app.api.confirmGlobal = true; await flush()
		button(app, '确认发布到知识库').click(); await flush()
		assert.equal(app.calls.publish.length, 1)
		assert.deepEqual(app.calls.publish[0][0], { entries: [{ feedbackId: id, expectedUpdatedAt: 10000, expectedVersion: 0, title: detail().question, answer: detail().correctedAnswer, scope: 'GLOBAL', confirmGlobal: true }] })
		assert.match(app.host.textContent, /已发布 · 版本 1/)
		button(app, '撤回当前版本').click(); await flush()
		assert.deepEqual(app.calls.withdraw[0][0], { feedbackId: id, expectedVersion: 1 })
		assert.match(app.host.textContent, /已撤回 · 版本 1/)
	} finally { app.dispose() }
})

test('a changed unsaved correction cannot be published even when the saved feedback is resolved', async () => {
	const app = await mount({ answer: '刚编辑还未保存的回答' })
	try {
		assert.equal(app.api.canPublish, false)
		await app.api.publish(); assert.equal(app.calls.publish.length, 0)
	} finally { app.dispose() }
})

test('role or credential changes abort and clear pending private drafts and ignore late provider responses', async () => {
	const pending = deferred(), app = await mount({ refine: () => pending.promise })
	try {
		const running = app.api.refine(); await flush()
		assert.equal(app.api.busy, true)
		app.store.loginInfo.role = 'USER'; app.credential.value = 'different-account'; await flush()
		assert.equal(app.calls.refine[0][1].signal.aborted, true)
		pending.resolve({ data: { answer: '不该显示的前账号草稿', requiresReview: true, sourceUpdatedAt: 10000 } }); await running; await flush()
		assert.equal(app.api.preview, null); assert.equal(app.api.state, null); assert.equal(app.api.canPublish, false)
		assert.doesNotMatch(app.host.textContent, /不该显示/)
		await app.api.refine(); await app.api.publish(); assert.equal(app.calls.refine.length, 1); assert.equal(app.calls.publish.length, 0)
	} finally { app.dispose() }
})

test('stale detail changes drop previews, history text is escaped and malformed state prevents publication', async () => {
	const pending = deferred(), app = await mount({ refine: () => pending.promise, history: async () => ({ data: [{ feedbackId: id, version: 1, scope: 'TENANT', title: '<script>x</script>', answer: '<img src=x>旧版回答' }] }) })
	try {
		const running = app.api.refine(); app.props.detail = { ...detail(), updatedAt: 10001 }; await flush()
		pending.resolve({ data: { answer: '旧问题草稿', requiresReview: true, sourceUpdatedAt: 10000 } }); await running
		assert.equal(app.api.preview, null)
		await app.api.loadHistory(); await flush(); assert.equal(app.host.querySelector('img'), null); assert.match(app.host.textContent, /旧版回答/)
	} finally { app.dispose() }
	const malformed = await mount({ state: async () => ({ data: { feedbackId: 'wrong', state: 'PUBLISHED', version: -1 } }) })
	try { assert.equal(malformed.api.state, null); assert.equal(malformed.api.canPublish, false); assert.match(malformed.host.textContent, /状态读取失败/) }
	finally { malformed.dispose() }
})

test('unknown publication results remain unresolved and are never retried automatically', async () => {
	const app = await mount({ publish: async () => { throw new Error('network interrupted') } })
	try {
		await app.api.publish(); await flush()
		assert.equal(app.calls.publish.length, 1); assert.equal(app.api.state.state, 'UNPUBLISHED')
		assert.match(app.host.textContent, /操作结果未确认/)
		assert.doesNotMatch(app.host.textContent, /已发布，之后/)
	} finally { app.dispose() }
})

test('course-origin feedback is restricted to its source course and never offers broader scope', async () => {
	const app = await mount()
	try {
		app.props.detail = { ...detail(), courseId: 'c'.repeat(32) }; await flush()
		assert.equal(app.api.scope, 'COURSE')
		assert.deepEqual([...app.host.querySelector('select').options].map(option => option.value), ['COURSE'])
		assert.equal(app.api.canPublish, true)
		app.api.scope = 'GLOBAL'; app.api.confirmGlobal = true; await flush()
		assert.equal(app.api.canPublish, false)
		await app.api.publish(); assert.equal(app.calls.publish.length, 0)
	} finally { app.dispose() }
})

test('scoped component styles compile and never render HTML from external or user text', () => {
	assert.doesNotMatch(source, /v-html/)
	for (const style of descriptor.styles) assert.deepEqual(compileStyle({ source: style.content, filename: 'AiKnowledgeCuration.vue', id: 'knowledge-curation-test', scoped: true }).errors, [])
})
