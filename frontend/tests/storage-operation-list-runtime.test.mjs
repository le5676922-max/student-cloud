import assert from 'node:assert/strict'
import test from 'node:test'
import { existsSync, readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of ['window', 'document', 'Document', 'Node', 'Element', 'HTMLElement', 'SVGElement', 'Event']) globalThis[key] = dom.window[key]
const vue = await import('vue')
const file = new URL('../src/components/file/StorageOperationList.vue', import.meta.url)
const transform = code => code.replace(/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g, (_m, names, mod) => `const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(mod)}]`)
const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await vue.nextTick() } }
const receipt = (id = 'one', state = 'INDETERMINATE') => ({ id, type: 'UPLOAD', state, createdAt: 1793494800000, resultRef: null })
async function mount(load) {
    assert.ok(existsSync(file), 'B02 operation receipt component must exist')
    const descriptor = parse(readFileSync(file, 'utf8')).descriptor
    const script = compileScript(descriptor, { id: 'operations-test' })
    const template = compileTemplate({ source: descriptor.template.content, filename: 'StorageOperationList.vue', id: 'operations-test', compilerOptions: { bindingMetadata: script.bindings } })
    assert.deepEqual(template.errors, [])
    const modules = { vue, '~/utils/latest-request.mjs': { createLatestRequest }, '~/api/home/drive-operations': { listDriveOperationsReq: load } }
    const Component = new Function('modules', transform(script.content).replace('export default', 'return'))(modules)
    Component.render = new Function('modules', transform(template.code).replace('export function render', 'return function render'))(modules)
    const props = vue.reactive({ driveKey: 'drive-a', identityKey: 'member-3' })
    const host = document.createElement('div');document.body.append(host)
    const app = vue.createApp({ render: () => vue.h(Component, props) });app.mount(host);await flush()
    return { host, props, dispose() { app.unmount();host.remove() } }
}
test('unknown receipt explains uncertainty without offering a write replay or force action', async () => {
    const ui = await mount(async () => ({ data: { items: [receipt()], nextCursor: null } }))
    try {
        assert.match(ui.host.textContent, /待核查/)
        assert.match(ui.host.textContent, /不要重复提交/)
        assert.doesNotMatch(ui.host.textContent, /整体回滚|强制成功|强制删除/)
        assert.deepEqual([...ui.host.querySelectorAll('button')].map(b => b.textContent.trim()), ['刷新记录'])
        assert.match(ui.host.querySelector('time').textContent, /2026/)
    } finally { ui.dispose() }
})
test('late source or account response cannot restore old private receipts', async () => {
    const pending = [];const ui = await mount((key, params, config) => new Promise(resolve => pending.push({key, params, config, resolve})))
    try {
        ui.props.identityKey = 'member-4';ui.props.driveKey = 'drive-b';await flush()
        assert.equal(pending[0].config.signal.aborted, true)
        pending[1].resolve({ data: { items: [receipt('new-member', 'SUCCEEDED')], nextCursor: null } });await flush()
        pending[0].resolve({ data: { items: [receipt('private-old-member')], nextCursor: null } });await flush()
        assert.match(ui.host.textContent, /new-member/);assert.doesNotMatch(ui.host.textContent, /private-old-member/)
    } finally { ui.dispose() }
})
test('bounded load-more uses returned cursor and failed refresh leaves visibly stale rows', async () => {
    const calls = [];let fail = false
    const ui = await mount(async (_key, params) => { calls.push(params);if (fail) throw new Error('secret provider failure');return { data: { items: [receipt(params.cursor ? 'second' : 'first', 'FAILED')], nextCursor: params.cursor ? null : 'cursor-1' } } })
    try {
        const more = [...ui.host.querySelectorAll('button')].find(b => b.textContent.includes('更多'));more.click();await flush()
        assert.deepEqual(calls[1], {cursor: 'cursor-1', limit: 20})
        assert.match(ui.host.textContent, /first/);assert.match(ui.host.textContent, /second/)
        fail = true;ui.host.querySelector('button').click();await flush()
        assert.match(ui.host.textContent, /上次读取/);assert.match(ui.host.textContent, /first/)
        assert.ok(ui.host.querySelector('[role="alert"]'));assert.doesNotMatch(ui.host.textContent, /secret provider/)
    } finally { ui.dispose() }
})
test('request adapter encodes drive key and passes bounded query through the shared request data field', async () => {
    const file = new URL('../src/api/home/drive-operations.js', import.meta.url)
    const source = readFileSync(file, 'utf8').replace("import axios from '~/http/request'", '').replace('export const listDriveOperationsReq', 'const listDriveOperationsReq')
    let sent
    const request = new Function('axios', `${source};return listDriveOperationsReq`)(options => { sent = options })
    const controller = new AbortController()
    request('drive/a', { cursor: '22', limit: 20 }, { signal: controller.signal })
    assert.equal(sent.url, '/api/drives/drive%2Fa/operations')
    assert.deepEqual(sent.data, { cursor: '22', limit: 20 })
    assert.equal(sent.config.signal, controller.signal)
})
test('logout cancels reading and clears existing receipts immediately', async () => {
    const ui = await mount(async () => ({ data: { items: [receipt('private-owner')], nextCursor: null } }))
    try {
        assert.match(ui.host.textContent, /private-owner/)
        ui.props.identityKey = '';await flush()
        assert.doesNotMatch(ui.host.textContent, /private-owner/)
        assert.equal(ui.host.querySelector('button').disabled, true)
    } finally { ui.dispose() }
})
test('revoked authorization clears receipts rather than keeping private stale rows', async () => {
    let revoked = false
    const ui = await mount(async () => { if (revoked) throw { response: { status: 403 } };return { data: { items: [receipt('revoked-private-receipt')], nextCursor: 'more' } } })
    try {
        assert.match(ui.host.textContent, /revoked-private-receipt/)
        revoked = true;ui.host.querySelector('button').click();await flush()
        assert.doesNotMatch(ui.host.textContent, /revoked-private-receipt/)
        assert.doesNotMatch(ui.host.textContent, /上次读取|加载更多/)
        assert.ok(ui.host.querySelector('[role="alert"]'))
    } finally { ui.dispose() }
})
