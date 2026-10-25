import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'

test('upload illustration has local explicit dimensions instead of its intrinsic SVG size', async () => {
	const component = await readFile(
		new URL('../src/components/file/ZUpload.vue', import.meta.url),
		'utf8'
	)
	assert.match(
		component,
		/<i-custom-upload class="upload-dropzone-icon" aria-hidden="true"/
	)
	const iconRule = component.match(/\.upload-dropzone-icon\s*\{([^}]+)\}/)?.[1]
	assert.ok(iconRule)
	assert.match(iconRule, /width:\s*80px;/)
	assert.match(iconRule, /height:\s*72px;/)
	assert.match(iconRule, /max-width:\s*100%;/)
	assert.doesNotMatch(component, /<i-custom-upload[^>]+text-7xl/)
})

test('actual upload dialog close clears history only, without blind file reload or canceling active work', async () => {
	const source = await readFile(new URL('../src/components/file/ZUpload.vue', import.meta.url), 'utf8')
	const { descriptor } = parse(source)
	const compiled = compileScript(descriptor, { id: 'upload-close-runtime' }).content
	const emitted = [], tasks = vue.reactive([{ status: 'finished' }, { status: 'uploading' }])
	let clearCalls = 0
	const modules = {
		'~/utils': {},
		'@heroicons/vue/24/outline': {},
		'~/composables/file/useFileSelect': { default: () => ({ currentPath: vue.ref('/another-directory') }) },
		'~/stores/storage-config': { default: () => ({ permission: { upload: true } }) },
		'~/composables/file/useFileUpload': { default: () => ({
			visible: vue.ref(false),
			uploadProgressInfoSorted: vue.computed(() => tasks),
			clearALlFinishedUploadFile() {
				clearCalls++
				for (let index = tasks.length - 1; index >= 0; index--)
					if (tasks[index].status === 'finished') tasks.splice(index, 1)
			},
		}) },
	}
	const executable = compiled.replace(
		/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
		(_match, bindings, name) => bindings.trim().startsWith('{')
			? `const ${bindings.replace(/\s+as\s+/g, ': ')} = modules[${JSON.stringify(name)}];`
			: `const ${bindings.trim()} = modules[${JSON.stringify(name)}].default;`
	).replace('export default', 'return')
	const component = vm.runInNewContext(`(function(){${executable}\n})()`, { ...vue, modules, onMounted() {} })
	const scope = vue.effectScope()
	try {
		const api = scope.run(() => component.setup({}, { expose() {}, emit: (...args) => emitted.push(args) }))
		api.closeDialog()
		api.closeDialog()
		assert.equal(clearCalls, 2)
		assert.equal(tasks.length, 1)
		assert.equal(tasks[0].status, 'uploading')
		assert.deepEqual(emitted, [])
	} finally {
		scope.stop()
	}
})
