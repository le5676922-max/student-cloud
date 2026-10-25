import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
	createStorageListLoader,
	filterStorageItems,
	isStorageLoadCanceled,
	reorderStorageItems,
	storageCommittedFailureMessage,
	storageListErrorMessage,
	storageStatusCounts,
	normalizeStorageStatusFilter,
} from '../src/utils/storage-list-controller.mjs'

const deferred = () => {
	let resolve
	let reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}

test('valid and empty storage lists both finish loading', async () => {
	for (const items of [[], [{ id: 1, name: '示例云盘' }]]) {
		const events = []
		const loader = createStorageListLoader({
			request: async ({ signal }) => {
				assert.equal(signal.aborted, false)
				return { data: items }
			},
			onStart: () => events.push('start'),
			onLoaded: (value) => events.push(value),
			onFailure: () => assert.fail('valid data cannot fail'),
			onSettled: () => events.push('settled'),
		})
		assert.equal(await loader.load(), true)
		assert.deepEqual(events, ['start', items, 'settled'])
		loader.dispose()
	}
})

test('network, synchronous request, and response application failures release loading', async () => {
	for (const stage of ['network', 'sync', 'apply']) {
		const original = new Error(stage)
		const failures = []
		let settled = 0
		const loader = createStorageListLoader({
			request: () => {
				if (stage === 'sync') throw original
				if (stage === 'network') return Promise.reject(original)
				return Promise.resolve({ data: [] })
			},
			onLoaded: () => {
				throw original
			},
			onFailure: (error) => failures.push(error),
			onSettled: () => settled++,
		})
		assert.equal(await loader.load(), false)
		assert.deepEqual(failures, [original])
		assert.equal(settled, 1)
	}
})

test('invalid storage response envelopes become a retryable error, not a stuck spinner', async () => {
	for (const response of [
		null,
		{},
		{ data: {} },
		{ data: [null] },
		{ data: [2] },
	]) {
		let error
		let settled = false
		const loader = createStorageListLoader({
			request: async () => response,
			onLoaded: () => assert.fail('invalid response cannot update the page'),
			onFailure: (value) => (error = value),
			onSettled: () => (settled = true),
		})
		assert.equal(await loader.load(), false)
		assert.equal(error.code, 'INVALID_STORAGE_LIST')
		assert.equal(settled, true)
	}
})

test('retry cancels and ignores an obsolete request even when its transport ignores abort', async () => {
	const first = deferred()
	const second = deferred()
	const signals = []
	const updates = []
	let requests = 0
	let settled = 0
	const loader = createStorageListLoader({
		request: ({ signal }) => {
			signals.push(signal)
			return requests++ === 0 ? first.promise : second.promise
		},
		onLoaded: (items) => updates.push(items),
		onFailure: () => assert.fail('superseded request is not an error'),
		onSettled: () => settled++,
	})
	const obsolete = loader.load()
	await Promise.resolve()
	const current = loader.load()
	await Promise.resolve()
	assert.equal(signals[0].aborted, true)
	second.resolve({ data: [{ id: 2 }] })
	assert.equal(await current, true)
	assert.equal(await obsolete, false)
	first.resolve({ data: [{ id: 1 }] })
	await Promise.resolve()
	assert.deepEqual(updates, [[{ id: 2 }]])
	assert.equal(settled, 1)
})

test('unmount cancels work and prevents any later state updates or requests', async () => {
	const pending = deferred()
	let signal
	let requests = 0
	const loader = createStorageListLoader({
		request: (config) => {
			requests++
			signal = config.signal
			return pending.promise
		},
		onLoaded: () => assert.fail('disposed page cannot update'),
		onFailure: () => assert.fail('unmount cannot produce an error banner'),
		onSettled: () => assert.fail('disposed page cannot update'),
	})
	const loading = loader.load()
	await Promise.resolve()
	loader.dispose()
	assert.equal(signal.aborted, true)
	assert.equal(await loading, false)
	pending.resolve({ data: [{ id: 1 }] })
	assert.equal(await loader.load(), false)
	assert.equal(requests, 1)
})

test('timeout bounds even a transport that never settles and retry still works', async () => {
	let calls = 0
	let timedOutSignal
	const failures = []
	let settled = 0
	const loader = createStorageListLoader({
		timeoutMs: 10,
		request: ({ signal }) => {
			if (calls++ === 0) {
				timedOutSignal = signal
				return new Promise(() => {})
			}
			return Promise.resolve({ data: [] })
		},
		onFailure: (error) => failures.push(error),
		onSettled: () => settled++,
	})
	assert.equal(await loader.load(), false)
	assert.equal(timedOutSignal.aborted, true)
	assert.equal(failures[0].code, 'ETIMEDOUT')
	assert.equal(settled, 1)
	assert.equal(await loader.load(), true)
	assert.equal(settled, 2)
})

test('immediate unmount does not start a request scheduled for the next microtask', async () => {
	const loader = createStorageListLoader({
		request: () => assert.fail('unmounted page must not start a request'),
		onFailure: () => assert.fail('cancellation is not a user-facing failure'),
		onSettled: () => assert.fail('unmounted page must not update state'),
	})
	const loading = loader.load()
	loader.dispose()
	assert.equal(await loading, false)
})

test('each loader instance is isolated and exposes whether post-render work is still current', async () => {
	const rendering = deferred()
	let context
	const first = createStorageListLoader({
		request: async () => ({ data: [{ id: 1 }] }),
		onLoaded: async (_, view) => {
			context = view
			await rendering.promise
		},
	})
	const updates = []
	const second = createStorageListLoader({
		request: async () => ({ data: [{ id: 2 }] }),
		onLoaded: (items) => updates.push(items),
	})
	const loading = first.load()
	await new Promise((resolve) => setImmediate(resolve))
	assert.equal(context.isCurrent(), true)
	first.dispose()
	assert.equal(context.isCurrent(), false)
	assert.equal(await second.load(), true)
	rendering.resolve()
	assert.equal(await loading, false)
	assert.deepEqual(updates, [[{ id: 2 }]])
})

test('sorting copies data and uses draggable indexes rather than the add-card index', () => {
	const first = { id: 1 }
	const second = { id: 2 }
	const third = { id: 3 }
	const items = [first, second, third]
	assert.deepEqual(reorderStorageItems(items, 0, 2), [second, third, first])
	assert.deepEqual(reorderStorageItems(items, 2, 0), [third, first, second])
	assert.deepEqual(items, [first, second, third])
	for (const indexes of [
		[0, 0],
		[-1, 2],
		[0, 3],
		[undefined, 1],
		[1.5, 2],
	]) {
		assert.equal(reorderStorageItems(items, ...indexes), null)
	}
	assert.equal(reorderStorageItems(null, 0, 1), null)
})

test('storage filtering normalizes whitespace and handles missing type fields safely', () => {
	const items = [
		{
			id: 1,
			name: '示例云盘',
			type: { key: 'LOCAL', description: '本地存储' },
		},
		{ id: 2, remark: 'My Archive' },
		{ id: 3, name: null, type: {} },
	]
	assert.equal(filterStorageItems(items, '   '), items)
	assert.deepEqual(filterStorageItems(items, ' local '), [items[0]])
	assert.deepEqual(filterStorageItems(items, '本地'), [items[0]])
	assert.deepEqual(filterStorageItems(items, 'archive'), [items[1]])
	assert.deepEqual(filterStorageItems(items, 'missing'), [])
})

test('error descriptions are actionable and do not expose raw backend diagnostics', () => {
	assert.match(storageListErrorMessage({ code: 'ETIMEDOUT' }), /超时/)
	assert.match(storageListErrorMessage({ code: 'ECONNABORTED' }), /超时/)
	assert.match(
		storageListErrorMessage({ response: { status: 403 } }),
		/重新登录/
	)
	assert.match(
		storageListErrorMessage({ code: 'INVALID_STORAGE_LIST' }),
		/数据/
	)
	assert.equal(isStorageLoadCanceled({ code: 'ERR_CANCELED' }), true)
	assert.equal(isStorageLoadCanceled({ name: 'AbortError' }), true)
	assert.equal(isStorageLoadCanceled({ code: 'ETIMEDOUT' }), false)
	assert.doesNotMatch(
		storageListErrorMessage(new Error('secret debug info')),
		/secret/
	)
})

test('status filters require explicit booleans and combine with the original text match without reordering', () => {
	const items = [
		{ id: 1, name: 'Course Local', enable: true },
		{
			id: 2,
			name: 'Archive',
			enable: false,
			refreshTokenInfo: { success: false },
		},
		{
			id: 3,
			name: 'Archive Two',
			enable: true,
			refreshTokenInfo: { success: true },
		},
		{
			id: 4,
			name: 'Unknown',
			enable: null,
			refreshTokenInfo: { success: null },
		},
	]
	assert.deepEqual(storageStatusCounts(items), {
		all: 4,
		enabled: 2,
		disabled: 1,
		'token-error': 1,
	})
	assert.deepEqual(filterStorageItems(items, ' archive ', 'enabled'), [
		items[2],
	])
	assert.deepEqual(filterStorageItems(items, '', 'token-error'), [items[1]])
	assert.deepEqual(filterStorageItems(items, '', 'disabled'), [items[1]])
	assert.equal(normalizeStorageStatusFilter('arbitrary'), 'all')
	assert.equal(filterStorageItems(items, '', 'arbitrary'), items)
	assert.deepEqual(
		items.map(({ id }) => id),
		[1, 2, 3, 4]
	)
})

test('post-commit cache failure safely preserves the explicit saved-data warning', () => {
	const message =
		'数据已保存，但存储列表缓存刷新失败。请恢复 Redis 后刷新页面，不要重复提交。'
	assert.equal(
		storageCommittedFailureMessage({ data: { msg: message } }),
		message
	)
	assert.equal(
		storageCommittedFailureMessage({ response: { data: { msg: message } } }),
		message
	)
	for (const error of [
		null,
		new Error(message),
		{ data: { msg: 'SQL password secret exception' } },
		{ data: { msg: message + ' raw debug information' } },
		{ data: { msg: '<script>' + message + '</script>' } },
		{ response: { data: { msg: 'cache server unavailable' } } },
	]) {
		assert.equal(storageCommittedFailureMessage(error), '')
	}
})

test('page wiring uses supported asset imports, scoped drag lifecycle and an explicit retry UI', () => {
	const page = readFileSync(
		new URL('../src/pages/admin/storage-list.vue', import.meta.url),
		'utf8'
	)
	const composable = readFileSync(
		new URL(
			'../src/composables/admin/storage/storage-list.js',
			import.meta.url
		),
		'utf8'
	)
	assert.doesNotMatch(page, /import\.meta\.globEager\(/)
	const icons = readFileSync(
		new URL('../src/utils/storage-icons.js', import.meta.url),
		'utf8'
	)
	assert.match(icons, /const storageIcons = import\.meta\.glob\(/)
	assert.match(page, /import \{ getStorageIcon \}/)
	assert.match(page, /ref="storageContainerRef"/)
	assert.match(page, /v-if="loadError"/)
	assert.match(page, /重新加载/)
	assert.match(composable, /onBeforeUnmount\(/)
	assert.match(composable, /loader\.dispose\(\)/)
	assert.match(composable, /sortable\?\.destroy\(\)/)
	assert.match(composable, /event\.oldDraggableIndex/)
	assert.match(composable, /Boolean\(searchKey\.value\.trim\(\)\)/)
	assert.doesNotMatch(composable, /document\.querySelector/)
	assert.match(
		composable,
		/const committedMessage = storageCommittedFailureMessage\(error\)/
	)
	assert.match(composable, /if \(committedMessage\) await loader\.load\(\)/)
})
