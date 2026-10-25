import assert from 'node:assert/strict'
import test from 'node:test'
import {
	completedUploadFileChanges,
	createFileChangeRefresh,
	fileChangeContextFromRoute,
} from '../src/utils/file-change-refresh.mjs'

const context = (path = '/资料') => ({ storageKey: 'workspace-2', path, accountIdentity: '["10001",2]' })
const target = (directory = '/资料') => ({ ...context(), directory })
function setupRefresh(options = {}) {
	const timers = new Map()
	let serial = 0, current = context(), busy = false, refreshes = 0
	const refresh = createFileChangeRefresh({
		getContext: () => current,
		isBusy: () => busy,
		refresh: () => { refreshes++; return options.result?.() },
		schedule: (callback) => { const id = ++serial; timers.set(id, callback); return id },
		unschedule: (id) => timers.delete(id),
	})
	return {
		...refresh, timers,
		get refreshes() { return refreshes },
		set current(value) { current = value },
		set busy(value) { busy = value },
		tick() {
			const callbacks = [...timers.values()]
			timers.clear()
			for (const callback of callbacks) callback()
		},
	}
}

test('route matching preserves legal percent, hash, question mark and space file names', () => {
	assert.deepEqual(fileChangeContextFromRoute({ params: { storageKey: 'workspace-2', fullpath: ['资料', '100% ?#'] } }, context().accountIdentity), context('/资料/100% ?#'))
	assert.deepEqual(fileChangeContextFromRoute({ params: { storageKey: 'workspace-2', fullpath: '' } }, context().accountIdentity), context('/'))
	assert.deepEqual(fileChangeContextFromRoute({ params: { storageKey: 'workspace-2', fullpath: '/资料/' } }, context().accountIdentity), context())
	for (const fullpath of [['..'], ['folder/sub'], '/one//two', '/one/../two', 1])
		assert.equal(fileChangeContextFromRoute({ params: { storageKey: 'workspace-2', fullpath } }, context().accountIdentity), null)
	assert.equal(fileChangeContextFromRoute({ params: {} }, context().accountIdentity), null)
	assert.equal(fileChangeContextFromRoute({ params: { storageKey: 'workspace-2' } }, ''), null)
})

test('normal upload emits only captured directory and folder upload includes created ancestors', () => {
	assert.deepEqual(completedUploadFileChanges(target(), '/资料/'), [context()])
	assert.deepEqual(completedUploadFileChanges(target(), '/资料/课程/第一章'), [context(), context('/资料/课程'), context('/资料/课程/第一章')])
	assert.deepEqual(completedUploadFileChanges(target('/'), '/课程/第一章'), [context('/'), context('/课程'), context('/课程/第一章')])
})

test('upload changes reject missing identity, malformed paths and directories outside original target', () => {
	for (const path of ['/资料2', '/elsewhere', '/资料/../elsewhere', '/资料//课程', null])
		assert.deepEqual(completedUploadFileChanges(target(), path), [])
	assert.deepEqual(completedUploadFileChanges({ ...target(), accountIdentity: '' }, '/资料'), [])
	assert.deepEqual(completedUploadFileChanges(undefined, '/资料'), [])
})

test('matching bursts coalesce once without restarting the first timer', () => {
	const h = setupRefresh()
	for (let i = 0; i < 30; i++) assert.equal(h.notify(context()), true)
	assert.equal(h.timers.size, 1)
	h.tick()
	assert.equal(h.refreshes, 1)
	assert.equal(h.timers.size, 0)
	h.dispose()
})

test('unrelated sources, paths and old accounts never schedule refresh; legacy Agent context is supported', () => {
	const h = setupRefresh()
	for (const change of [null, {}, { ...context(), storageKey: 'other' }, context('/other'), { ...context(), accountIdentity: '' }, { ...context(), accountIdentity: '["other",2]' }])
		assert.equal(h.notify(change), false)
	assert.equal(h.timers.size, 0)
	assert.equal(h.notify({ storageKey: 'workspace-2', path: '/资料/' }), true)
	h.tick()
	assert.equal(h.refreshes, 1)
	h.dispose()
})

test('busy directory or operation defers one merged refresh without dropping completion events', () => {
	const h = setupRefresh()
	h.busy = true
	h.notify(context())
	h.tick()
	assert.equal(h.refreshes, 0)
	assert.equal(h.timers.size, 1)
	h.notify(context())
	h.busy = false
	h.tick()
	assert.equal(h.refreshes, 1)
	assert.equal(h.timers.size, 0)
	h.dispose()
})

test('last-moment navigation or account change rejects queued refresh even without an explicit cancel', () => {
	for (const value of [context('/different'), { ...context(), accountIdentity: '["other",2]' }, null]) {
		const h = setupRefresh()
		h.notify(context())
		h.current = value
		h.tick()
		assert.equal(h.refreshes, 0)
		assert.equal(h.timers.size, 0)
		h.dispose()
	}
})

test('canceled callbacks cannot refresh after leaving then returning, and a later event starts fresh', () => {
	const h = setupRefresh()
	h.notify(context())
	const stale = [...h.timers.values()][0]
	h.cancel()
	assert.equal(h.timers.size, 0)
	h.notify(context())
	stale()
	assert.equal(h.refreshes, 0)
	h.tick()
	assert.equal(h.refreshes, 1)
	h.dispose()
})

test('dispose clears the queue and prevents later events from creating timers', () => {
	const h = setupRefresh()
	h.notify(context())
	const stale = [...h.timers.values()][0]
	h.dispose()
	stale()
	assert.equal(h.notify(context()), false)
	assert.equal(h.timers.size, 0)
	assert.equal(h.refreshes, 0)
})

test('refresh failure is contained and subsequent completion events can refresh again', async () => {
	for (const result of [() => Promise.reject(new Error('offline')), () => { throw new Error('offline') }]) {
		const h = setupRefresh({ result })
		h.notify(context())
		h.tick()
		await Promise.resolve()
		h.notify(context())
		h.tick()
		await Promise.resolve()
		assert.equal(h.refreshes, 2)
		h.dispose()
	}
})
