import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'

const descriptor = parse(
	await readFile(
		new URL('../src/pages/recycle-bin.vue', import.meta.url),
		'utf8'
	)
).descriptor
const compiled = compileScript(descriptor, { id: 'recycle-runtime' })
assert.deepEqual(
	compileTemplate({
		source: descriptor.template.content,
		filename: 'recycle-bin.vue',
		id: 'recycle-runtime',
		compilerOptions: { bindingMetadata: compiled.bindings },
	}).errors,
	[]
)
const transform = (code) =>
	code
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_m, names, module) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}}=modules[${JSON.stringify(
					module
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_m, name, module) => `const ${name}=modules[${JSON.stringify(module)}]`
		)
const flush = async () => {
	for (let n = 0; n < 12; n++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
const deferred = () => {
	let resolve, reject
	const promise = new Promise((done, fail) => {
		resolve = done
		reject = fail
	})
	return { promise, resolve, reject }
}
function setup({ request, confirm = async () => true } = {}) {
	const mounted = [],
		cleanups = [],
		calls = [],
		confirmations = [],
		success = []
	const store = vue.reactive({
			loginInfo: {
				id: 3,
				accountId: 'a3',
				username: 'member',
				isLogin: true,
				role: 'USER',
				tenantId: 1,
			},
		}),
		credential = vue.ref('token')
	const modules = {
		vue: {
			...vue,
			onMounted: (fn) => mounted.push(fn),
			onBeforeUnmount: (fn) => cleanups.push(fn),
		},
		'@vueuse/core': { useStorage: () => credential },
		'element-plus': {
			ElMessage: { success: (message) => success.push(message) },
			ElMessageBox: {
				confirm: (...args) => {
					confirmations.push(args)
					return confirm(...args)
				},
			},
		},
		'~/stores/storage-config': () => store,
		'~/http/request': async (input) => {
			calls.push(input)
			return request
				? request(input)
				: input.url === '/api/storage/list'
				? { data: [{ key: 'own-drive', name: 'own' }] }
				: { data: [{ id: 'a'.repeat(32), name: 'note', status: 'TRASHED' }] }
		},
	}
	const component = new Function(
			'modules',
			transform(compiled.content).replace('export default', 'return')
		)(modules),
		scope = vue.effectScope()
	const state = vue.proxyRefs(
		scope.run(() => component.setup({}, { expose() {} }))
	)
	return {
		state,
		calls,
		store,
		credential,
		confirmations,
		success,
		mount: () => Promise.all(mounted.map((fn) => fn())),
		dispose() {
			cleanups.forEach((fn) => fn())
			scope.stop()
		},
	}
}
test('real recycle page cancels an old load and never lets late rows replace the selected drive', async () => {
	const old = deferred()
	let first = true
	const page = setup({
		request: (input) =>
			input.url === '/api/storage/list'
				? Promise.resolve({ data: [{ key: 'own-drive', name: 'own' }] })
				: first
				? ((first = false), old.promise)
				: Promise.resolve({ data: [{ name: 'new' }] }),
	})
	try {
		const mounting = page.mount()
		await flush()
		const oldRequest = page.calls[1]
		page.state.selected = 'new-drive'
		await page.state.load()
		old.resolve({ data: [{ name: 'old' }] })
		await mounting
		assert.equal(oldRequest.config.signal.aborted, true)
		assert.equal(page.state.rows[0].name, 'new')
	} finally {
		page.dispose()
	}
})
test('logout and credential changes clear private rows even when username and workspace remain the same', async () => {
	const page = setup()
	try {
		await page.mount()
		assert.equal(page.state.rows.length, 1)
		page.store.loginInfo.isLogin = false
		await flush()
		assert.equal(page.state.rows.length, 0)
		assert.equal(page.state.drives.length, 0)
	} finally {
		page.dispose()
	}
	const next = setup()
	try {
		await next.mount()
		next.credential.value = 'other-token'
		await flush()
		assert.equal(next.state.rows.length, 0)
	} finally {
		next.dispose()
	}
})
test('restore conflicts remain visible and do not escape as rejected event handlers', async () => {
	const page = setup({
		request: async (input) => {
			if (input.url.endsWith('/restore'))
				throw { response: { data: { msg: '目标已存在同名文件，不会覆盖' } } }
			return input.url === '/api/storage/list'
				? { data: [{ key: 'own-drive' }] }
				: { data: [{ id: 'a'.repeat(32), status: 'TRASHED' }] }
		},
	})
	try {
		await page.mount()
		await assert.doesNotReject(() => page.state.act(page.state.rows[0], false))
		assert.match(page.state.error, /同名文件/)
		assert.equal(page.success.length, 0)
		assert.equal(page.state.busy, false)
	} finally {
		page.dispose()
	}
})
test('purge confirmation is singular, cancellation never deletes and account changes invalidate pending confirmation', async () => {
	const confirmation = deferred(),
		page = setup({ confirm: () => confirmation.promise })
	try {
		await page.mount()
		const row = page.state.rows[0],
			one = page.state.act(row, true),
			two = page.state.act(row, true)
		assert.equal(page.confirmations.length, 1)
		page.store.loginInfo.id = 4
		await flush()
		confirmation.resolve(true)
		await Promise.all([one, two])
		assert.equal(
			page.calls.filter((call) => call.method === 'delete').length,
			0
		)
	} finally {
		page.dispose()
	}
	const cancelled = setup({
		confirm: async () => {
			throw new Error('cancel')
		},
	})
	try {
		await cancelled.mount()
		await cancelled.state.act(cancelled.state.rows[0], true)
		assert.equal(
			cancelled.calls.some((call) => call.method === 'delete'),
			false
		)
	} finally {
		cancelled.dispose()
	}
})
test('an unmounted storage request is aborted and its late error stays discarded', async () => {
	const request = deferred(),
		page = setup({ request: () => request.promise }),
		mounting = page.mount()
	await flush()
	const pending = page.calls[0]
	page.dispose()
	request.reject(new Error('old storage error'))
	await mounting
	assert.equal(pending.config.signal.aborted, true)
	assert.equal(page.state.error, '')
})

test('a confirmed purge sends exactly one explicit permanent-delete request and then refreshes', async () => {
	const page = setup()
	try {
		await page.mount()
		await page.state.act(page.state.rows[0], true)
		const deletes = page.calls.filter((call) => call.method === 'delete')
		assert.equal(deletes.length, 1)
		assert.equal(
			deletes[0].url,
			`/api/drives/own-drive/recycle-bin/${'a'.repeat(32)}?confirmed=true`
		)
		assert.equal(page.confirmations.length, 1)
		assert.equal(page.success.at(-1), '已彻底删除')
		assert.equal(page.state.busy, false)
	} finally {
		page.dispose()
	}
})
