import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as controller from '../src/utils/storage-list-controller.mjs'

const pageSource = await readFile(
	new URL('../src/pages/admin/storage-list.vue', import.meta.url),
	'utf8'
)
const listSource = await readFile(
	new URL('../src/composables/admin/storage/storage-list.js', import.meta.url),
	'utf8'
)
const { descriptor } = parse(pageSource)
const compiledPage = compileScript(descriptor, {
	id: 'storage-list-runtime',
}).content
const plain = (value) => JSON.parse(JSON.stringify(value))
const flush = async () => {
	for (let step = 0; step < 6; step++) {
		await vue.nextTick()
		await Promise.resolve()
	}
}
const item = (id, extra = {}) => ({
	id,
	key: `drive-${id}`,
	name: `云盘${id}`,
	enable: true,
	type: { key: 'LOCAL', description: '本地存储' },
	...extra,
})

function execute(source, modules, globals) {
	const executable = source
		.replace(
			/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
			(_whole, bindings, name) =>
				bindings.trim().startsWith('{')
					? `const ${bindings.replace(
							/\s+as\s+/g,
							': '
					  )} = modules[${JSON.stringify(name)}];`
					: `const ${bindings.trim()} = modules[${JSON.stringify(
							name
					  )}].default;`
		)
		.replace('export default', 'return')
	return vm.runInNewContext(`(function(){${executable}\n})()`, {
		...vue,
		modules,
		AbortController,
		...globals,
	})
}

/** Run the actual page setup and composable with transport/DOM drag boundaries stubbed. */
function setupPage(initialItems = []) {
	const calls = { loads: 0, sorts: [], pushes: [], opens: [], mutations: [] }
	const cleanups = [],
		mounted = [],
		sorts = []
	let response = async () => ({ data: initialItems })
	let sortResponse = async () => true
	const router = { push: (path) => calls.pushes.push(path) }
	const globals = {
		onBeforeUnmount: (callback) => cleanups.push(callback),
		onMounted: (callback) => mounted.push(callback),
		useRouter: () => router,
		window: { open: (...args) => calls.opens.push(args) },
		ElMessage: { success() {} },
		ElMessageBox: {
			confirm: (_message, _title, options) => options.callback('confirm'),
		},
	}
	const modules = {
		vue,
		'~/stores/storage-config': {
			default: () => ({ loginInfo: { role: 'ADMIN' } }),
		},
		'~/api/admin/admin-storage': {
			loadStorageListReq: (...args) => {
				calls.loads++
				return response(...args)
			},
			storageSortReq: async (order) => {
				calls.sorts.push(plain(order))
				return sortResponse()
			},
			switchEnableReq: async (...args) => calls.mutations.push(args),
		},
		sortablejs: {
			default: {
				create(_element, options) {
					const state = {
						options,
						destroyed: false,
						disabled: options.disabled,
					}
					sorts.push(state)
					return {
						destroy: () => {
							state.destroyed = true
						},
						option(_key, disabled) {
							state.disabled = disabled
						},
					}
				},
			},
		},
		'~/utils': { isMobile: vue.ref(false) },
		'~/utils/storage-list-controller.mjs': controller,
		'~/composables/admin/storage/storage-copy': {
			default: () => ({ openDialog() {} }),
		},
		'@heroicons/vue/24/outline': {},
		'~/pages/admin/storage-copy/index.vue': { default: {} },
		'~/utils/storage-icons': { getStorageIcon: () => '' },
	}
	modules['~/composables/admin/storage/storage-list'] = {
		default: execute(listSource, modules, globals),
	}
	const Page = execute(compiledPage, modules, globals)
	const scope = vue.effectScope()
	const bindings = scope.run(() => Page.setup({}, { expose() {} }))
	bindings.storageContainerRef.value = { children: [] }
	return {
		bindings,
		calls,
		sorts,
		mounted,
		setResponse(next) {
			response = next
		},
		setSortResponse(next) {
			sortResponse = next
		},
		dispose() {
			cleanups.forEach((cleanup) => cleanup())
			scope.stop()
		},
	}
}

test('actual storage page combines local status and existing name/type search without another request', async () => {
	const page = setupPage([
		item(1),
		item(2, { enable: false, refreshTokenInfo: { success: false } }),
		item(3, {
			type: { key: 'S3', description: '对象存储' },
			refreshTokenInfo: { success: false },
		}),
	])
	try {
		const state = page.bindings
		assert.deepEqual(
			plain(state.statusOptions.value.map(({ count }) => count)),
			[null, null, null, null]
		)
		assert.equal(state.emptyState.value, '')
		await state.init()
		assert.deepEqual(
			plain(state.statusOptions.value.map(({ count }) => count)),
			[3, 2, 1, 2]
		)
		state.statusFilter.value = 'token-error'
		state.searchKey.value = ' s3 '
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[3]
		)
		assert.equal(page.calls.loads, 1)
		state.clearFilters()
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[1, 2, 3]
		)
		assert.equal(state.hasStorageFilters.value, false)
		assert.equal(page.calls.loads, 1)
	} finally {
		page.dispose()
	}
})

test('actual storage drag handler rejects every filtered view and preserves hidden IDs after clearing', async () => {
	const page = setupPage([item(1), item(2, { enable: false }), item(3)])
	try {
		const state = page.bindings
		await state.init()
		await flush()
		const sortable = page.sorts.at(-1)
		assert.equal(sortable.disabled, false)
		for (const [status, search] of [
			['enabled', ''],
			['all', '云盘1'],
			['disabled', '云盘2'],
		]) {
			state.statusFilter.value = status
			state.searchKey.value = search
			await flush()
			assert.equal(sortable.disabled, true)
			await sortable.options.onEnd({
				oldDraggableIndex: 0,
				newDraggableIndex: 1,
			})
			assert.equal(page.calls.sorts.length, 0)
		}
		state.clearFilters()
		await flush()
		assert.equal(sortable.disabled, false)
		await sortable.options.onEnd({ oldDraggableIndex: 0, newDraggableIndex: 2 })
		assert.deepEqual(
			page.calls.sorts[0].map(({ id }) => id),
			[2, 3, 1]
		)
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[2, 3, 1]
		)
		assert.equal(page.calls.loads, 1)
	} finally {
		page.dispose()
	}
})

test('actual storage page distinguishes unconfigured, unmatched and failed loads without invented zero counts', async () => {
	const page = setupPage([])
	try {
		const state = page.bindings
		await state.init()
		assert.equal(state.emptyState.value, 'unconfigured')
		state.statusFilter.value = 'enabled'
		assert.equal(state.emptyState.value, 'filtered')
		state.clearFilters()
		assert.equal(state.emptyState.value, 'unconfigured')
		page.setResponse(async () => {
			throw new Error('private diagnostic')
		})
		await state.init()
		assert.equal(state.emptyState.value, '')
		assert.ok(state.loadError.value)
		assert.doesNotMatch(state.loadError.value, /private diagnostic/)
	} finally {
		page.dispose()
	}
	const failed = setupPage()
	try {
		failed.setResponse(async () => {
			throw new Error('offline')
		})
		await failed.bindings.init()
		assert.equal(failed.bindings.hasLoaded.value, false)
		assert.deepEqual(
			plain(failed.bindings.statusOptions.value.map(({ count }) => count)),
			[null, null, null, null]
		)
	} finally {
		failed.dispose()
	}
})

test('pending sort blocks another drop and refresh, then applies every source exactly once', async () => {
	const page = setupPage([item(1), item(2, { enable: false }), item(3)])
	let finishSort
	page.setSortResponse(
		() =>
			new Promise((resolve) => {
				finishSort = resolve
			})
	)
	try {
		const state = page.bindings
		await state.init()
		await flush()
		const sortable = page.sorts.at(-1)
		const saving = sortable.options.onEnd({
			oldDraggableIndex: 0,
			newDraggableIndex: 2,
		})
		await flush()
		assert.equal(state.sorting.value, true)
		assert.equal(sortable.disabled, true)
		assert.equal(await state.init(), false)
		await sortable.options.onEnd({ oldDraggableIndex: 1, newDraggableIndex: 0 })
		assert.equal(page.calls.sorts.length, 1)
		assert.equal(page.calls.loads, 1)
		// A programmatic filter change cannot turn a full-list save into a partial save.
		state.statusFilter.value = 'disabled'
		finishSort()
		await saving
		assert.equal(state.sorting.value, false)
		state.clearFilters()
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[2, 3, 1]
		)
	} finally {
		finishSort?.()
		page.dispose()
	}
})

test('disposing the actual page destroys its scoped drag instance and never starts another list read', async () => {
	const page = setupPage([item(1)])
	await page.bindings.init()
	await flush()
	const sortable = page.sorts.at(-1)
	page.dispose()
	assert.equal(sortable.destroyed, true)
	assert.equal(await page.bindings.init(), false)
	assert.equal(page.calls.loads, 1)
})

test('failed refresh retains the previous sources explicitly and retry recovers the current filter', async () => {
	const page = setupPage([item(1), item(2, { enable: false })])
	try {
		const state = page.bindings
		await state.init()
		state.statusFilter.value = 'disabled'
		page.setResponse(async () => {
			throw new Error('offline')
		})
		await state.init()
		assert.equal(state.showingPreviousData.value, true)
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[2]
		)
		page.setResponse(async () => ({ data: [item(3, { enable: false })] }))
		await state.init()
		assert.equal(state.showingPreviousData.value, false)
		assert.equal(state.loadError.value, '')
		assert.equal(state.statusFilter.value, 'disabled')
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[3]
		)
	} finally {
		page.dispose()
	}
})

test('status counts react to a confirmed enable change without extra list reads', async () => {
	const page = setupPage([item(1, { enable: false })])
	try {
		const state = page.bindings
		await state.init()
		state.statusFilter.value = 'disabled'
		state.switchEnableStatus(state.storageListSearchResult.value[0])
		await flush()
		assert.deepEqual(
			plain(state.statusOptions.value.map(({ count }) => count)),
			[1, 1, 0, 0]
		)
		assert.equal(state.emptyState.value, 'filtered')
		assert.equal(page.calls.loads, 1)
		assert.deepEqual(plain(page.calls.mutations), [[1, 'enable']])
	} finally {
		page.dispose()
	}
})

test('page action handlers keep the existing edit/create routes and open files without window.opener', () => {
	const page = setupPage()
	try {
		page.bindings.addStorage()
		page.bindings.editStorage(item(7))
		page.bindings.openStorage('drive-7')
		assert.deepEqual(plain(page.calls.pushes), [
			'/admin/storage-edit',
			'/admin/storage-edit/7',
		])
		assert.deepEqual(plain(page.calls.opens), [
			['/drive-7', '_blank', 'noopener,noreferrer'],
		])
		const result = compileTemplate({
			source: descriptor.template.content,
			filename: 'storage-list.vue',
			id: 'storage-runtime',
		})
		assert.deepEqual(result.errors, [])
	} finally {
		page.dispose()
	}
})
