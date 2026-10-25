import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'

const source = await readFile(
	new URL('../src/components/admin/LocalStorageRequests.vue', import.meta.url),
	'utf8'
)
const descriptor = parse(source).descriptor
const compiled = compileScript(descriptor, {
	id: 'local-storage-requests-runtime',
})
const flush = async () => {
	for (let i = 0; i < 8; i++) {
		await vue.nextTick()
		await Promise.resolve()
	}
}
function deferred() {
	let resolve
	const promise = new Promise((done) => {
		resolve = done
	})
	return { promise, resolve }
}
function page({
	role = 'TENANT_ADMIN',
	list = async () => ({ data: [] }),
	submit = async () => ({}),
	review = async () => ({}),
} = {}) {
	const store = vue.reactive({
		loginInfo: { role, username: 'test-manager', tenantId: 9 },
	})
	const calls = { lists: [], submits: [], reviews: [], changed: 0 },
		unmount = []
	const modules = {
		vue: { ...vue, onBeforeUnmount: (callback) => unmount.push(callback) },
		'~/stores/storage-config': { default: () => store },
		'~/api/admin/local-storage-approval': {
			loadLocalStorageRequestsReq: (...args) => {
				calls.lists.push(args)
				return list(...args)
			},
			submitLocalStorageRequestReq: (...args) => {
				calls.submits.push(args)
				return submit(...args)
			},
			reviewLocalStorageRequestReq: (...args) => {
				calls.reviews.push(args)
				return review(...args)
			},
		},
	}
	const executable = compiled.content
		.replace(
			/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
			(_all, bindings, name) =>
				bindings.trim().startsWith('{')
					? `const ${bindings.replace(
							/\s+as\s+/g,
							': '
					  )}=modules[${JSON.stringify(name)}];`
					: `const ${bindings.trim()}=modules[${JSON.stringify(name)}].default;`
		)
		.replace('export default', 'return')
	const component = vm.runInNewContext(`(function(){${executable}\n})()`, {
		modules,
		AbortController,
	})
	const scope = vue.effectScope()
	const api = scope.run(() =>
		component.setup(
			{},
			{
				expose() {},
				emit: (event) => {
					if (event === 'changed') calls.changed++
				},
			}
		)
	)
	return {
		api,
		store,
		calls,
		unmount() {
			unmount.forEach((callback) => callback())
			scope.stop()
		},
	}
}

test('local cloud request panel loads only on expansion and submits a purpose without any server path', async () => {
	const p = page()
	try {
		assert.equal(p.calls.lists.length, 0)
		p.api.onToggle({ target: { open: true } })
		await flush()
		assert.equal(p.calls.lists.length, 1)
		assert.equal(p.calls.lists[0][0], false)
		p.api.reason.value = '  课程共享  '
		await p.api.submit()
		assert.equal(p.calls.submits.length, 1)
		assert.equal(p.calls.submits[0][0], '课程共享')
		assert.equal(p.api.reason.value, '')
		assert.equal(p.calls.changed, 1)
	} finally {
		p.unmount()
	}
})

test('a pending request cannot double submit and a late previous-account response is discarded', async () => {
	const operation = deferred(),
		firstLoad = deferred()
	let count = 0
	const p = page({
		list: () =>
			++count === 1 ? firstLoad.promise : Promise.resolve({ data: [] }),
		submit: () => operation.promise,
	})
	try {
		p.api.onToggle({ target: { open: true } })
		await flush()
		p.store.loginInfo.username = 'different-manager'
		p.store.loginInfo.tenantId = 8
		await flush()
		firstLoad.resolve({ data: [{ id: 'foreign-private-request' }] })
		await flush()
		assert.equal(p.api.rows.value.length, 0)
		p.api.reason.value = 'bounded request'
		const first = p.api.submit()
		await flush()
		p.api.submit()
		assert.equal(p.calls.submits.length, 1)
		p.unmount()
		operation.resolve({})
		await first
		assert.equal(p.calls.changed, 0)
	} finally {
		/* Already unmounted to test late completion. */
	}
})

test('global review sends one exact application and choice, then refreshes real server state', async () => {
	const p = page({ role: 'ADMIN' })
	try {
		p.api.notes.value.application = '审批说明'
		await p.api.review({ id: 'application' }, true)
		assert.deepEqual(p.calls.reviews[0].slice(0, 3), [
			'application',
			true,
			'审批说明',
		])
		assert.equal(p.calls.lists[0][0], true)
		assert.equal(p.calls.changed, 1)
	} finally {
		p.unmount()
	}
})

test('the actual approval form compiles and does not expose a server-path field', () => {
	const result = compileTemplate({
		source: descriptor.template.content,
		filename: 'LocalStorageRequests.vue',
		id: 'local-storage-requests-runtime',
		compilerOptions: { bindingMetadata: compiled.bindings },
	})
	assert.deepEqual(result.errors, [])
	assert.doesNotMatch(
		descriptor.template.content,
		/v-model="[^\"]*(?:filePath|serverPath)/
	)
	assert.match(
		descriptor.template.content,
		/isAdmin && row.status === 'PENDING'/
	)
})
