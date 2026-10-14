import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createContext, Script } from 'node:vm'
import * as Vue from 'vue'
import { driveSelectionActions } from '../src/utils/drive-selection-actions.mjs'

const require = createRequire(import.meta.url)
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
const source = await readFile(new URL('../src/components/file/FileSelectionToolbar.vue', import.meta.url), 'utf8')
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'selection-toolbar-test' })
const executable = compiled.content.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '').replace('export default', 'globalThis.Toolbar =')

function setup() {
	const calls = []
	const rows = Vue.ref([{ type: 'FILE', name: 'notes.txt' }])
	const loading = Vue.ref(false)
	const busy = Vue.ref(false)
	const storage = Vue.reactive({ permission: { download: true, createShareLink: true, rename: true, move: true, copy: true, delete: true }, folderConfig: { metadata: {} } })
	const callback = (name) => () => calls.push(name)
	const sandbox = createContext({
		...Vue, driveSelectionActions,
		useFileSelect: () => ({ selectRows: rows, clearSelection: () => { rows.value = []; calls.push('clear') } }),
		useFileLoading: () => ({ loading }),
		useFileOperator: () => ({ operationBusy: busy, batchDownloadFile: callback('download'), rename: callback('rename'), moveTo: callback('move'), copyTo: callback('copy'), batchDelete: callback('delete') }),
		useFileShare: () => ({ openCreateShareDialog: callback('share') }),
		useStorageConfigStore: () => storage,
	})
	for (const name of Object.keys(compiled.imports)) if (!(name in sandbox)) sandbox[name] = {}
	new Script(executable).runInContext(sandbox)
	const scope = Vue.effectScope()
	const api = scope.run(() => sandbox.Toolbar.setup({}, { expose() {} }))
	return { api, rows, loading, busy, storage, calls, dispose: () => scope.stop() }
}

test('real toolbar setup executes only current authorized actions and guards busy/loading', (t) => {
	const h = setup()
	t.after(h.dispose)
	h.api.runAction('share')
	assert.deepEqual(h.calls, ['share'])
	h.busy.value = true
	h.api.runAction('delete')
	h.busy.value = false
	h.loading.value = true
	h.api.runAction('rename')
	h.loading.value = false
	h.storage.permission.delete = false
	h.api.runAction('delete')
	assert.deepEqual(h.calls, ['share'])
	h.storage.permission.delete = true
	h.api.runAction('delete')
	assert.deepEqual(h.calls, ['share', 'delete'])
})

test('selection changes immediately update toolbar affordances and clear hides the toolbar', (t) => {
	const h = setup()
	t.after(h.dispose)
	h.rows.value.push({ type: 'FOLDER', name: 'course' })
	assert.equal(h.api.actions.value.count, 2)
	for (const action of ['download', 'rename', 'copy', 'move']) h.api.runAction(action)
	assert.deepEqual(h.calls, [])
	h.api.clearSelection()
	assert.equal(h.api.actions.value.count, 0)
	h.api.runAction('share')
	assert.deepEqual(h.calls, ['clear'])
})

test('toolbar template compiles with accessible live selection and disabled actions', () => {
	const result = compileTemplate({ source: descriptor.template.content, filename: 'FileSelectionToolbar.vue', id: 'selection-toolbar-test', compilerOptions: { bindingMetadata: compiled.bindings } })
	assert.deepEqual(result.errors, [])
	assert.match(descriptor.template.content, /aria-label="已选文件操作"/)
	assert.match(descriptor.template.content, /aria-live="polite"/)
})
