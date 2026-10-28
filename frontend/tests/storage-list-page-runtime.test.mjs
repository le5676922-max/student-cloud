import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as controller from '../src/utils/storage-list-controller.mjs'
import * as navigationCredential from '../src/utils/navigation-credential.mjs'

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
	await new Promise((resolve) => setImmediate(resolve))
}
const item = (id, extra = {}) => ({
	id,
	key: `drive-${id}`,
	name: `云盘${id}`,
	enable: true,
	type: { key: 'LOCAL', description: '本地存储' },
	...extra,
})

test('independent local storage administration fences workspace actions and requires disabling before delete', async () => {
	const local = item(1, { type: { key: 'local' }, cacheAvailable: false })
	const tenant = setupPage([local], {
		accountModel: 'independent',
		role: 'TENANT_ADMIN',
	})
	try {
		await tenant.bindings.init()
		await flush()
		assert.equal(tenant.sorts.at(-1).disabled, true)
		tenant.bindings.editStorage(local)
		tenant.bindings.switchEnableStatus(local)
		tenant.bindings.handleOperator({ operator: 'delete', storage: local })
		await flush()
		assert.deepEqual(tenant.calls.pushes, [])
		assert.deepEqual(tenant.calls.mutations, [])
		assert.match(tenant.bindings.operationError.value, /全局管理员维护/)
	} finally {
		tenant.dispose()
	}
	const global = setupPage([local], { accountModel: 'independent' })
	try {
		global.bindings.handleOperator({ operator: 'delete', storage: local })
		await flush()
		assert.deepEqual(global.calls.mutations, [])
		assert.match(global.bindings.operationError.value, /先停用/)
		global.bindings.handleOperator({
			operator: 'delete',
			storage: { ...local, enable: false },
		})
		await flush()
		assert.deepEqual(plain(global.calls.mutations), [['delete', 1]])
		global.bindings.handleOperator({
			operator: 'permissionManager',
			storage: local,
		})
		assert.match(global.bindings.operationError.value, /空间管理员/)
		assert.deepEqual(global.calls.pushes, [])
	} finally {
		global.dispose()
	}
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
function setupPage(
	initialItems = [],
	{ accountModel = '', role = 'ADMIN', tenantId = 9, query = {} } = {}
) {
	const calls = {
		loads: 0,
		sorts: [],
		pushes: [],
		replaces: [],
		opens: [],
		mutations: [],
		confirmations: [],
		successes: [],
	}
	const cleanups = [],
		mounted = [],
		sorts = []
	let response = async () => ({ data: initialItems })
	let sortResponse = async () => true
	let mutationResponse = async () => true
	let confirmationResponse = (options) => options.callback('confirm')
	let token = 'session-one'
	const store = vue.reactive({
		loginInfo: { role, tenantId, username: 'admin' },
		globalConfig: { accountModel },
	})
	const router = {
		currentRoute: vue.ref({ path: '/admin/storage-list', query }),
		push: (path) => calls.pushes.push(path),
		replace: (target) => {
			calls.replaces.push(plain(target))
			router.currentRoute.value = target
			return Promise.resolve()
		},
	}
	const globals = {
		onBeforeUnmount: (callback) => cleanups.push(callback),
		onMounted: (callback) => mounted.push(callback),
		useRouter: () => router,
		window: {
			open: (...args) => calls.opens.push(args),
			localStorage: { getItem: () => token },
		},
		document: { cookie: '' },
		ElMessage: { success: (message) => calls.successes.push(message) },
		ElMessageBox: {
			confirm: (message, title, options) => {
				calls.confirmations.push({ message, title, options })
				return confirmationResponse(options)
			},
		},
	}
	const modules = {
		vue,
		'~/stores/storage-config': {
			default: () => store,
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
			switchEnableReq: async (...args) => {
				calls.mutations.push(args)
				return mutationResponse()
			},
			deleteStorageReq: async (...args) => {
				calls.mutations.push(['delete', ...args])
				return mutationResponse()
			},
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
		'~/utils/navigation-credential.mjs': navigationCredential,
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
		store,
		router,
		setToken(next) {
			token = next
		},
		setMutationResponse(next) {
			mutationResponse = next
		},
		setConfirmationResponse(next) {
			confirmationResponse = next
		},
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
		page.bindings.openStorage(item(7))
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

test('independent workspace administrators can edit and enable or disable their own remote connections', async () => {
	for (const type of ['s3', 'aliyun', 'minio']) {
		const remote = item(41, { type: { key: type }, tenantId: 9 })
		const page = setupPage([remote], {
			accountModel: 'independent',
			role: 'TENANT_ADMIN',
		})
		try {
			await page.bindings.init()
			page.bindings.editStorage(remote)
			assert.deepEqual(page.calls.pushes, ['/admin/storage-edit/41'])
			page.bindings.switchEnableStatus(remote)
			await flush()
			assert.equal(remote.enable, false)
			page.bindings.handleOperator({ operator: 'enable', storage: remote })
			await flush()
			assert.equal(remote.enable, true)
			assert.deepEqual(plain(page.calls.mutations), [
				[41, 'disable'],
				[41, 'enable'],
			])
			assert.equal(page.bindings.operationError.value, '')
		} finally {
			page.dispose()
		}
	}
})

test('local and out-of-workspace connections remain unavailable to independent workspace administrators', async () => {
	for (const row of [
		item(1, { type: { key: 'local' }, tenantId: 9 }),
		item(2, { type: { key: 'LOCAL' }, tenantId: 9 }),
		item(3, { type: { key: 's3' }, tenantId: 10 }),
	]) {
		const page = setupPage([row], {
			accountModel: 'independent',
			role: 'TENANT_ADMIN',
		})
		try {
			page.bindings.editStorage(row)
			page.bindings.switchEnableStatus(row)
			await flush()
			assert.deepEqual(page.calls.pushes, [])
			assert.deepEqual(page.calls.mutations, [])
			assert.ok(page.bindings.operationError.value)
		} finally {
			page.dispose()
		}
	}
})

test('independent remote retirement preserves connection history for both global and workspace administrators', async () => {
	for (const role of ['ADMIN', 'TENANT_ADMIN']) {
		const remote = item(41, { type: { key: 's3' }, tenantId: 9, enable: false })
		const page = setupPage([remote], { accountModel: 'independent', role })
		try {
			page.bindings.handleOperator({ operator: 'delete', storage: remote })
			await flush()
			assert.deepEqual(page.calls.mutations, [])
			assert.match(page.bindings.operationError.value, /停用.*保留/)
		} finally {
			page.dispose()
		}
	}
})

test('remote connection cards expose workspace edit and enable controls while hiding history deletion', () => {
	const collect = (node, predicate, found = []) => {
		if (predicate(node)) found.push(node)
		for (const child of node.children || []) collect(child, predicate, found)
		return found
	}
	const commands = (operator) =>
		collect(
			descriptor.template.ast,
			(node) =>
				node.tag === 'el-dropdown-item' &&
				node.props?.some(
					(prop) =>
						prop.name === 'bind' &&
						prop.arg?.content === 'command' &&
						prop.exp?.content.includes(`operator: '${operator}'`)
				)
		)
	const edit = collect(
		descriptor.template.ast,
		(node) =>
			node.tag === 'button' &&
			node.props?.some(
				(prop) =>
					prop.name === 'on' &&
					prop.arg?.content === 'click' &&
					prop.exp?.content === 'editStorage(storage)'
			)
	)[0]
	const page = setupPage([], {
		accountModel: 'independent',
		role: 'TENANT_ADMIN',
	})
	try {
		const context = Object.fromEntries(
			Object.entries(page.bindings).map(([key, value]) => [
				key,
				vue.unref(value),
			])
		)
		context.storage = item(41, { type: { key: 's3' }, tenantId: 9 })
		const evaluate = (expression) => vm.runInNewContext(expression, context)
		assert.equal(
			evaluate(
				edit.props.find(
					(prop) => prop.name === 'bind' && prop.arg?.content === 'disabled'
				).exp.content
			),
			false
		)
		const enable = collect(
			descriptor.template.ast,
			(node) =>
				node.tag === 'button' &&
				node.props?.some(
					(prop) =>
						prop.name === 'on' &&
						prop.arg?.content === 'click' &&
						prop.exp?.content === 'switchEnableStatus(storage)'
				)
		)[0]
		assert.ok(enable, 'enable is an explicit native button')
		assert.equal(
			evaluate(
				enable.props.find(
					(prop) => prop.name === 'bind' && prop.arg?.content === 'disabled'
				).exp.content
			),
			false
		)
		const badges = collect(
			descriptor.template.ast,
			(node) => node.tag === 'el-tag'
		)
		assert.ok(badges.length > 0)
		assert.equal(
			badges.some((node) =>
				node.props?.some(
					(prop) => prop.name === 'on' && prop.arg?.content === 'click'
				)
			),
			false
		)
		const deletion = commands('delete')[0]
		const visibility = deletion.props.find((prop) => prop.name === 'if')
		assert.equal(visibility ? evaluate(visibility.exp.content) : true, false)
	} finally {
		page.dispose()
	}
})

const deferred = () => {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}

test('row pending locks confirmation and transport duplicates and failure keeps the saved enable state', async () => {
	const page = setupPage([item(1, { enable: false }), item(2)])
	const response = deferred()
	page.setMutationResponse(() => response.promise)
	page.setConfirmationResponse(() => {})
	try {
		const state = page.bindings
		await state.init()
		const row = state.storageListSearchResult.value[0]
		state.switchEnableStatus(row)
		state.switchEnableStatus({ ...row })
		assert.equal(page.calls.confirmations.length, 1)
		assert.equal(state.isRowPending(row), true)
		assert.equal(state.operationPending.value, true)
		await flush()
		assert.equal(page.sorts.at(-1).disabled, true)
		assert.equal(await state.init(), false)
		assert.equal(page.calls.mutations.length, 0)
		page.calls.confirmations[0].options.callback('confirm')
		page.calls.confirmations[0].options.callback('confirm')
		await flush()
		state.switchEnableStatus(row)
		state.handleOperator({ operator: 'enable', storage: row })
		state.editStorage(row)
		assert.deepEqual(plain(page.calls.mutations), [[1, 'enable']])
		assert.deepEqual(page.calls.pushes, [])
		assert.equal(row.enable, false)
		response.reject(new Error('secret backend credential'))
		await flush()
		assert.equal(row.enable, false)
		assert.equal(state.isRowPending(row), false)
		assert.equal(state.operationPending.value, false)
		assert.match(state.operationError.value, /刷新确认/)
		assert.doesNotMatch(state.operationError.value, /secret/)
		assert.equal(page.calls.loads, 1)
		assert.equal(page.calls.successes.length, 0)
	} finally {
		response.resolve()
		page.dispose()
	}
})

test('canceling a named enable confirmation releases the row without a write', async () => {
	const row = item(1, { name: '课程资料' })
	const page = setupPage([row])
	page.setConfirmationResponse(() => {})
	try {
		await page.bindings.init()
		page.bindings.switchEnableStatus(row)
		assert.match(page.calls.confirmations[0].message, /课程资料/)
		assert.equal(page.calls.confirmations[0].options.confirmButtonText, '停用')
		page.calls.confirmations[0].options.callback('cancel')
		assert.equal(page.bindings.isRowPending(row), false)
		assert.equal(page.calls.mutations.length, 0)
		page.bindings.switchEnableStatus(row)
		assert.equal(page.calls.confirmations.length, 2)
	} finally {
		page.dispose()
	}
})

test('keyboard order commands save the whole mount list and sort pending blocks all other mutations', async () => {
	const page = setupPage([item(30), item(10, { enable: false }), item(20)])
	const response = deferred()
	page.setSortResponse(() => response.promise)
	try {
		const state = page.bindings
		await state.init()
		const rows = state.storageListSearchResult.value
		assert.equal(state.canMoveStorage(rows[0], -1), false)
		assert.equal(state.canMoveStorage(rows[2], 1), false)
		state.handleOperator({ operator: 'moveLater', storage: rows[0] })
		await flush()
		assert.deepEqual(page.calls.sorts, [
			[
				{ id: 10, orderNum: 0 },
				{ id: 30, orderNum: 1 },
				{ id: 20, orderNum: 2 },
			],
		])
		state.handleOperator({ operator: 'moveEarlier', storage: rows[2] })
		state.switchEnableStatus(rows[0])
		state.handleOperator({ operator: 'delete', storage: rows[1] })
		state.editStorage(rows[0])
		state.openStorage(rows[0])
		assert.equal(page.calls.sorts.length, 1)
		assert.equal(page.calls.confirmations.length, 0)
		assert.equal(page.calls.mutations.length, 0)
		assert.equal(page.calls.opens.length, 0)
		assert.equal(page.calls.pushes.length, 0)
		assert.equal(state.canMoveStorage(rows[0], 1), false)
		response.resolve()
		await flush()
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[10, 30, 20]
		)
		assert.equal(state.sorting.value, false)
		state.searchKey.value = '云盘'
		await flush()
		state.handleOperator({ operator: 'moveLater', storage: rows[0] })
		assert.equal(page.calls.sorts.length, 1)
	} finally {
		response.resolve()
		page.dispose()
	}
})

test('failed keyboard ordering preserves confirmed order and never retries a write', async () => {
	const page = setupPage([item(1), item(2), item(3)])
	page.setSortResponse(async () => {
		throw new Error('private error')
	})
	try {
		await page.bindings.init()
		page.bindings.handleOperator({ operator: 'moveEarlier', storage: item(3) })
		await flush()
		assert.deepEqual(
			plain(page.bindings.storageListSearchResult.value.map(({ id }) => id)),
			[1, 2, 3]
		)
		assert.equal(page.calls.sorts.length, 1)
		assert.equal(page.calls.loads, 1)
		assert.equal(page.bindings.sorting.value, false)
		assert.match(page.bindings.operationError.value, /刷新确认/)
		assert.doesNotMatch(page.bindings.operationError.value, /private/)
	} finally {
		page.dispose()
	}
})

test('account change clears private rows and cancels a stale read before a fresh account can load', async () => {
	const page = setupPage([item(1)])
	const response = deferred()
	try {
		const state = page.bindings
		await state.init()
		let signal
		page.setResponse((_data, config) => {
			signal = config.signal
			return response.promise
		})
		const loading = state.init()
		await flush()
		page.store.loginInfo = {
			role: 'TENANT_ADMIN',
			tenantId: 22,
			username: 'other',
		}
		assert.equal(signal.aborted, true)
		assert.equal(state.hasLoaded.value, false)
		assert.deepEqual(plain(state.storageListSearchResult.value), [])
		assert.equal(await loading, false)
		response.resolve({ data: [item(99)] })
		await flush()
		assert.deepEqual(plain(state.storageListSearchResult.value), [])
		page.setResponse(async () => ({ data: [item(22)] }))
		await state.init()
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[22]
		)
	} finally {
		response.resolve({ data: [] })
		page.dispose()
	}
})

test('late confirmation and mutation completion cannot write into another account or route', async () => {
	for (const boundary of ['account', 'route', 'dispose']) {
		const row = item(1)
		const page = setupPage([row])
		const response = deferred()
		page.setMutationResponse(() => response.promise)
		try {
			await page.bindings.init()
			page.bindings.switchEnableStatus(row)
			await flush()
			assert.equal(page.calls.mutations.length, 1)
			if (boundary === 'account') page.store.loginInfo.username = 'other'
			if (boundary === 'route')
				page.router.currentRoute.value = { path: '/admin/overview', query: {} }
			if (boundary === 'dispose') page.dispose()
			response.resolve()
			await flush()
			assert.equal(row.enable, true)
			assert.equal(page.calls.successes.length, 0)
			if (boundary === 'account')
				assert.deepEqual(plain(page.bindings.storageListSearchResult.value), [])
		} finally {
			response.resolve()
			page.dispose()
		}
	}
	const page = setupPage([item(1)])
	page.setConfirmationResponse(() => {})
	try {
		await page.bindings.init()
		page.bindings.switchEnableStatus(item(1))
		page.store.loginInfo.username = 'other'
		page.calls.confirmations[0].options.callback('confirm')
		await flush()
		assert.equal(page.calls.mutations.length, 0)
	} finally {
		page.dispose()
	}
})

test('late sort completion after an account switch cannot restore old mounts', async () => {
	const page = setupPage([item(1), item(2)])
	const response = deferred()
	page.setSortResponse(() => response.promise)
	try {
		await page.bindings.init()
		page.bindings.handleOperator({ operator: 'moveLater', storage: item(1) })
		await flush()
		page.store.loginInfo.username = 'another-admin'
		response.resolve()
		await flush()
		assert.deepEqual(plain(page.bindings.storageListSearchResult.value), [])
		assert.equal(page.bindings.sorting.value, false)
		assert.equal(page.calls.successes.length, 0)
	} finally {
		response.resolve()
		page.dispose()
	}
})

test('token replacement during a read rejects old data even before account metadata changes', async () => {
	const page = setupPage()
	const response = deferred()
	page.setResponse(() => response.promise)
	try {
		const loading = page.bindings.init()
		await flush()
		page.setToken('session-two')
		response.resolve({ data: [item(99)] })
		assert.equal(await loading, false)
		assert.deepEqual(plain(page.bindings.storageListSearchResult.value), [])
		assert.equal(page.bindings.hasLoaded.value, false)
	} finally {
		response.resolve({ data: [] })
		page.dispose()
	}
})

test('URL filter restoration survives edit/create navigation, refresh and browser Back without storing extra fields', async () => {
	const page = setupPage([item(1), item(2, { enable: false })], {
		query: { q: ' 云盘2 ', status: 'disabled', secret: 'must-not-copy' },
	})
	try {
		const state = page.bindings
		await state.init()
		assert.equal(state.searchKey.value, '云盘2')
		assert.equal(state.statusFilter.value, 'disabled')
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[2]
		)
		state.editStorage(state.storageListSearchResult.value[0])
		state.addStorage()
		assert.deepEqual(plain(page.calls.pushes), [
			{
				path: '/admin/storage-edit/2',
				query: { q: '云盘2', status: 'disabled' },
			},
			{
				path: '/admin/storage-edit',
				query: { q: '云盘2', status: 'disabled' },
			},
		])
		await state.init()
		assert.equal(state.searchKey.value, '云盘2')
		page.router.currentRoute.value = {
			path: '/admin/storage-list',
			query: { q: '云盘1', status: 'enabled' },
		}
		await flush()
		assert.equal(state.searchKey.value, '云盘1')
		assert.equal(state.statusFilter.value, 'enabled')
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[1]
		)
		state.statusFilter.value = 'all'
		state.searchKey.value = '云盘'
		await flush()
		assert.deepEqual(plain(page.router.currentRoute.value.query), { q: '云盘' })
		assert.doesNotMatch(
			JSON.stringify(page.calls.replaces),
			/secret|must-not-copy/
		)
	} finally {
		page.dispose()
	}
})

test('local search commits after IME composition and clear is immediate with input focus restored', async () => {
	const page = setupPage([item(1), item(2, { name: '课程资料' })])
	try {
		const state = page.bindings
		await state.init()
		let focusCount = 0
		state.searchInputRef.value = {
			focus() {
				focusCount++
			},
		}
		state.beginSearchComposition()
		state.searchKey.value = '课程'
		await flush()
		assert.equal(state.storageListSearchResult.value.length, 2)
		assert.equal(page.calls.replaces.length, 0)
		state.finishSearchComposition({ target: { value: '课程' } })
		assert.deepEqual(
			plain(state.storageListSearchResult.value.map(({ id }) => id)),
			[2]
		)
		await flush()
		assert.deepEqual(plain(page.router.currentRoute.value.query), { q: '课程' })
		state.clearSearch()
		assert.equal(state.searchKey.value, '')
		assert.equal(state.storageListSearchResult.value.length, 2)
		await flush()
		assert.equal(focusCount, 1)
		assert.deepEqual(plain(page.router.currentRoute.value.query), {})
		assert.equal(page.calls.loads, 1)
	} finally {
		page.dispose()
	}
})

test('disabled sources cannot open and mixed-case local types remain global-admin-only', async () => {
	const page = setupPage([item(1, { enable: false })])
	try {
		page.bindings.openStorage(item(1, { enable: false }))
		page.bindings.openStorage(item(1, { enable: undefined }))
		assert.equal(page.calls.opens.length, 0)
		page.bindings.openStorage(item(2))
		assert.deepEqual(plain(page.calls.opens), [
			['/drive-2', '_blank', 'noopener,noreferrer'],
		])
	} finally {
		page.dispose()
	}
	for (const type of ['local', 'LOCAL']) {
		const tenant = setupPage([], { role: 'TENANT_ADMIN' })
		try {
			const local = item(1, { type: { key: type } })
			tenant.bindings.editStorage(local)
			tenant.bindings.switchEnableStatus(local)
			assert.equal(tenant.bindings.canChangeStorage(local), false)
			assert.equal(tenant.calls.pushes.length, 0)
			assert.equal(tenant.calls.confirmations.length, 0)
		} finally {
			tenant.dispose()
		}
	}
})

test('confirmed enable completion updates the current row after another row refreshes the list', async () => {
	const page = setupPage([
		item(1, { enable: false }),
		item(2, { enable: false }),
	])
	const response = deferred()
	let mutationNumber = 0
	page.setMutationResponse(() =>
		++mutationNumber === 1 ? response.promise : Promise.resolve()
	)
	try {
		await page.bindings.init()
		const row = page.bindings.storageListSearchResult.value[0]
		page.bindings.switchEnableStatus(row)
		await flush()
		page.setResponse(async () => ({ data: [item(1, { enable: false })] }))
		page.bindings.handleOperator({
			operator: 'delete',
			storage: item(2, { enable: false }),
		})
		await flush()
		assert.equal(page.calls.loads, 2)
		assert.equal(
			page.bindings.isRowPending(
				page.bindings.storageListSearchResult.value[0]
			),
			true
		)
		response.resolve()
		await flush()
		assert.equal(page.bindings.storageListSearchResult.value[0].enable, true)
		assert.equal(page.bindings.operationPending.value, false)
	} finally {
		response.resolve()
		page.dispose()
	}
})

test('a read taken before a confirmed mutation cannot overwrite it when the read returns last', async () => {
	const page = setupPage([
		item(1, { enable: false }),
		item(2, { enable: false }),
	])
	const mutation = deferred()
	const staleRead = deferred()
	const freshRead = deferred()
	let mutationNumber = 0
	let readNumber = 0
	page.setMutationResponse(() =>
		++mutationNumber === 1 ? mutation.promise : Promise.resolve()
	)
	try {
		await page.bindings.init()
		const row = page.bindings.storageListSearchResult.value[0]
		page.bindings.switchEnableStatus(row)
		await flush()
		page.setResponse(() =>
			++readNumber === 1 ? staleRead.promise : freshRead.promise
		)
		page.bindings.handleOperator({
			operator: 'delete',
			storage: item(2, { enable: false }),
		})
		await flush()
		assert.equal(page.calls.loads, 2)
		mutation.resolve()
		await flush()
		assert.equal(page.bindings.storageListSearchResult.value[0].enable, true)
		staleRead.resolve({ data: [item(1, { enable: false })] })
		await flush()
		assert.equal(page.bindings.storageListSearchResult.value[0].enable, true)
		assert.equal(
			page.calls.loads,
			3,
			'superseded metadata is read again after the confirmed write'
		)
		assert.equal(page.bindings.loading.value, true)
		assert.deepEqual(plain(page.calls.mutations), [
			[1, 'enable'],
			['delete', 2],
		])
		freshRead.resolve({ data: [item(1, { enable: true })] })
		await flush()
		assert.equal(page.bindings.storageListSearchResult.value[0].enable, true)
		assert.equal(page.bindings.loading.value, false)
		assert.equal(page.bindings.operationPending.value, false)
		assert.equal(page.calls.loads, 3)
		assert.equal(
			page.calls.mutations.length,
			2,
			'reads never replay either write'
		)
	} finally {
		mutation.resolve()
		staleRead.resolve({ data: [] })
		freshRead.resolve({ data: [] })
		page.dispose()
	}
})
