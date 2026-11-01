import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { quotaPresentation } from '../src/utils/quota-presentation.mjs'
const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of ['window', 'document', 'Document', 'Node', 'Element', 'HTMLElement', 'SVGElement', 'Event']) globalThis[key] = dom.window[key]
const vue = await import('vue')
const transform = code => code.replace(/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g, (_m, names, mod) => `const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(mod)}]`)
const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await vue.nextTick() } }
const exact = (usedBytes = 1234) => ({ usageState: 'EXACT', limitBytes: 1048576, usedBytes, reservedBytes: 0 })
async function mount(read) {
    const descriptor = parse(readFileSync(new URL('../src/components/file/DriveQuota.vue', import.meta.url), 'utf8')).descriptor
    const script = compileScript(descriptor, { id: 'quota-runtime' })
    const template = compileTemplate({ source: descriptor.template.content, filename: 'DriveQuota.vue', id: 'quota-runtime', compilerOptions: { bindingMetadata: script.bindings } })
    assert.deepEqual(template.errors, [])
    const modules = { vue, '~/api/home/drive-quota': { getDriveQuota: read, reconcileDriveQuota: read }, '~/utils/quota-presentation.mjs': { quotaPresentation } }
    const Component = new Function('modules', transform(script.content).replace('export default', 'return'))(modules)
    Component.render = new Function('modules', transform(template.code).replace('export function render', 'return function render'))(modules)
    const props = vue.reactive({ driveKey: 'drive-a', identityKey: 'member-a' })
    const host = document.createElement('div'); document.body.append(host)
    const Button = { props: ['disabled'], setup: (p, { attrs, slots }) => () => vue.h('button', { ...attrs, disabled: p.disabled, type: 'button' }, slots.default?.()) }
    const app = vue.createApp({ render: () => vue.h(Component, props) }); app.component('el-button', Button); app.mount(host); await flush()
    return { host, props, dispose() { app.unmount(); host.remove() } }
}
test('remote unsupported 501 hides the local-ledger widget instead of showing a fabricated zero', async () => {
    const ui = await mount(async () => { throw { response: { status: 501, data: { code: 'QUOTA_UNAVAILABLE', msg: 'remote secret' } } } })
    try { assert.equal(ui.host.querySelector('.drive-quota'), null); assert.doesNotMatch(ui.host.textContent, /remote secret|已用 0/) } finally { ui.dispose() }
})
test('late quota response from an old account is canceled and cannot replace the new account value', async () => {
    const pending = []
    const ui = await mount((key, signal) => new Promise(resolve => pending.push({ key, signal, resolve })))
    try {
        ui.props.identityKey = 'member-b'; await flush()
        assert.equal(pending[0].signal.aborted, true)
        pending[1].resolve({ data: exact(2048) }); await flush()
        pending[0].resolve({ data: exact(999999) }); await flush()
        assert.match(ui.host.textContent, /已用 2.0 KiB/); assert.doesNotMatch(ui.host.textContent, /976.6 KiB/)
    } finally { ui.dispose() }
})
test('unknown capacity has no numeric progressbar and explicitly says it is not measured zero', async () => {
    const ui = await mount(async () => ({ data: { usageState: 'UNKNOWN', limitBytes: 1048576, usedBytes: null, reservedBytes: null } }))
    try { assert.match(ui.host.textContent, /容量尚未校准/); assert.match(ui.host.textContent, /不会按 0 计算/); assert.equal(ui.host.querySelector('[role=progressbar]'), null); assert.doesNotMatch(ui.host.textContent, /已用 0/) } finally { ui.dispose() }
})
test('401 refresh clears previously shown private quota rather than retaining stale numbers', async () => {
    let expired = false
    const ui = await mount(async () => { if (expired) throw { response: { status: 401, data: { msg: 'private-auth-detail' } } }; return { data: exact(524288) } })
    try {
        assert.match(ui.host.textContent, /已用 512.0 KiB/)
        expired = true; [...ui.host.querySelectorAll('button')].find(b => b.textContent === '刷新容量').click(); await flush()
        assert.doesNotMatch(ui.host.textContent, /512.0 KiB|private-auth-detail/)
        assert.ok(ui.host.querySelector('[role=alert]')); assert.equal(ui.host.querySelector('[role=progressbar]'), null)
    } finally { ui.dispose() }
})
