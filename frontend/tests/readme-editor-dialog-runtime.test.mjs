import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createContext, Script } from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'

const filename = '../src/pages/admin/storage-readme/readme-editor-dialog.vue'
const { descriptor } = parse(await readFile(new URL(filename, import.meta.url), 'utf8'))
const compiled = compileScript(descriptor, { id: 'readme-dialog-test' })
const executable = compiled.content.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '').replace('export default', 'globalThis.Dialog =')
function setup(visible = true) {
	const mounted = [], events = []
	const sandbox = createContext({ ...vue, onMounted: callback => mounted.push(callback), defineAsyncComponent: () => ({}) })
	new Script(executable).runInContext(sandbox)
	const props = vue.reactive({ visible, modelValue: '# Original', title: '说明' })
	const scope = vue.effectScope()
	const api = scope.run(() => sandbox.Dialog.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
	for (const callback of mounted) callback()
	return { api, props, events, dispose: () => scope.stop() }
}
test('readme dialog compiles against current Vue with a writable model rather than a const literal', () => {
	const output = compileTemplate({ source: descriptor.template.content, filename, id: 'readme-dialog-test', compilerOptions: { bindingMetadata: compiled.bindings } })
	assert.deepEqual(output.errors, [])
})
test('dialog visibility follows parent and dialog model updates do not overwrite content', (t) => {
	const h = setup(false)
	t.after(h.dispose)
	assert.equal(h.api.isShow.value, false)
	h.props.visible = true
	assert.equal(h.api.isShow.value, true)
	h.api.isShow.value = false
	assert.deepEqual(h.events, [['update:visible', false]])
	assert.equal(h.api.readmeText.value, '# Original')
})
test('cancel discards edits while save emits text before closing, closed events do not save', (t) => {
	const cancelled = setup()
	t.after(cancelled.dispose)
	cancelled.api.readmeText.value = '# Draft'
	cancelled.api.closeDialog()
	assert.deepEqual(cancelled.events, [['update:visible', false]])
	const saved = setup()
	t.after(saved.dispose)
	saved.api.readmeText.value = '# Saved'
	saved.api.saveDialog()
	assert.deepEqual(saved.events, [['update:modelValue', '# Saved'], ['update:visible', false]])
	saved.props.visible = false
	saved.api.closeDialog()
	assert.equal(saved.events.length, 2)
})
