import assert from 'node:assert/strict'
import test from 'node:test'
import { existsSync, readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createLatestRequest } from '../src/utils/latest-request.mjs'
const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of ['window', 'document', 'Document', 'Node', 'Element', 'HTMLElement', 'SVGElement', 'Event']) globalThis[key] = dom.window[key]
const vue = await import('vue')
const file = new URL('../src/components/ai/DocumentResearchPanel.vue', import.meta.url)
const transform = code => code.replace(/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g, (_m, names, mod) => `const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(mod)}]`)
const flush = async () => { for (let i = 0; i < 15; i++) { await Promise.resolve(); await vue.nextTick() } }
const version = '22222222-2222-2222-2222-222222222222'
const documentFixture = { fileVersionId: version, title: '实验指导书', state: 'PARTIAL' }
async function mount(api, extra = {}) {
    assert.ok(existsSync(file), 'B04–B06 gated document research panel must exist')
    const descriptor = parse(readFileSync(file, 'utf8')).descriptor
    const script = compileScript(descriptor, { id: 'document-test' })
    const template = compileTemplate({ source: descriptor.template.content, filename: 'DocumentResearchPanel.vue', id: 'document-test', compilerOptions: { bindingMetadata: script.bindings } })
    assert.deepEqual(template.errors, [])
    const modules = { vue, '~/utils/latest-request.mjs': { createLatestRequest }, '~/api/home/document-research': api }
    const Component = new Function('modules', transform(script.content).replace('export default', 'return'))(modules)
    Component.render = new Function('modules', transform(template.code).replace('export function render', 'return function render'))(modules)
    const props = vue.reactive({ enabled: false, identityKey: 'member-a', documents: [documentFixture], ...extra })
    const host = document.createElement('div'); document.body.append(host)
    const app = vue.createApp({ render: () => vue.h(Component, props) }); app.mount(host); await flush()
    return { host, props, dispose() { app.unmount(); host.remove() } }
}
const capable = async () => ({ data: { mode: 'LEXICAL', sourceVersionSupport: true, externalEmbeddingEnabled: false } })
async function selectAndAsk(ui) {
    const selection = ui.host.querySelector('input[type=checkbox]'); selection.checked = true; selection.dispatchEvent(new Event('change')); await flush()
    const input = ui.host.querySelector('textarea'); input.value = '实验如何预处理'; input.dispatchEvent(new Event('input'))
    const consent = ui.host.querySelector('[data-context-consent]'); consent.checked = true; consent.dispatchEvent(new Event('change')); await flush()
    ui.host.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flush()
}
test('off by default makes zero requests and exposes no unusable search controls', async () => {
    let calls = 0; const unexpected = async () => { calls++; throw new Error('must not request') }
    const ui = await mount({ documentCapabilitiesReq: unexpected, answerDocumentsReq: unexpected })
    try { assert.equal(calls, 0); assert.equal(ui.host.querySelector('form'), null); assert.match(ui.host.textContent, /未启用/) } finally { ui.dispose() }
})
test('unavailable immutable versions explain the dependency without pretending indexing is ready', async () => {
    const ui = await mount({ documentCapabilitiesReq: async () => ({ data: { sourceVersionSupport: false, mode: 'LEXICAL' } }) }, { enabled: true })
    try { assert.equal(ui.host.querySelector('form'), null); assert.match(ui.host.textContent, /资料版本接口尚未接通/); assert.doesNotMatch(ui.host.textContent, /索引已完成|混合检索成功/) } finally { ui.dispose() }
})
test('selected immutable versions and consent are submitted; partial coverage and evidence stay visible', async () => {
    let sent
    const ui = await mount({ documentCapabilitiesReq: capable, answerDocumentsReq: async (input) => { sent = input; return { data: { answer: '资料覆盖不足。需要归一化。', mode: 'LEXICAL', coverage: 'PARTIAL', citations: [{ fileId: 'file-a', fileVersionId: version, chunkId: 'chunk-a', page: 3, excerpt: '实验先归一化' }], sourceRevisionSet: [] } } } }, { enabled: true })
    try {
        await selectAndAsk(ui)
        assert.deepEqual(sent, { query: '实验如何预处理', fileVersionIds: [version], optInPrivateContext: true })
        assert.match(ui.host.textContent, /词法检索/); assert.match(ui.host.textContent, /覆盖不足/); assert.match(ui.host.textContent, /实验指导书/); assert.match(ui.host.textContent, /第3页/)
        assert.match(ui.host.querySelector('details').textContent, /实验先归一化/)
    } finally { ui.dispose() }
})
test('account switch cancels buffered private answers and clears question, consent and selection', async () => {
    const pending = []
    const ui = await mount({ documentCapabilitiesReq: capable, answerDocumentsReq: (_input, config) => new Promise(resolve => pending.push({ config, resolve })) }, { enabled: true })
    try {
        await selectAndAsk(ui); assert.equal(pending.length, 1)
        ui.props.identityKey = 'member-b'; await flush()
        assert.equal(pending[0].config.signal.aborted, true)
        pending[0].resolve({ data: { answer: 'old private answer', mode: 'LEXICAL', coverage: 'COMPLETE', citations: [] } }); await flush()
        assert.doesNotMatch(ui.host.textContent, /old private answer/)
        assert.equal(ui.host.querySelector('textarea').value, '')
        assert.equal(ui.host.querySelector('[data-context-consent]').checked, false)
        assert.equal(ui.host.querySelector('input[type=checkbox]').checked, false)
    } finally { ui.dispose() }
})
