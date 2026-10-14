import test from 'node:test'
import assert from 'node:assert/strict'
import {
	createLatestRequest,
	loadDirectoryInParallel,
} from '../src/utils/latest-request.mjs'

test('a new directory or sort request aborts and invalidates the previous request', () => {
	const requests = createLatestRequest()
	const first = requests.begin()
	const second = requests.begin()
	assert.equal(first.signal.aborted, true)
	assert.equal(first.isCurrent(), false)
	assert.equal(second.signal.aborted, false)
	assert.equal(second.isCurrent(), true)
})

test('late file, permission or root-path responses cannot overwrite the current directory', async () => {
	const requests = createLatestRequest()
	let releaseOld
	let displayed
	const old = requests.begin()
	const slow = new Promise((resolve) => {
		releaseOld = resolve
	}).then((value) => {
		if (old.isCurrent()) displayed = value
	})
	const current = requests.begin()
	if (current.isCurrent()) displayed = 'new directory'
	releaseOld('old directory')
	await slow
	assert.equal(displayed, 'new directory')
})

test('leaving the file page cancels pending work and allows a fresh request later', () => {
	const requests = createLatestRequest()
	const first = requests.begin()
	requests.cancel()
	assert.equal(first.signal.aborted, true)
	assert.equal(first.isCurrent(), false)
	requests.cancel()
	assert.equal(requests.begin().isCurrent(), true)
})

function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}

test('files and configuration start together and a slow config does not delay list rendering', async () => {
	const config = deferred()
	const events = []
	const work = loadDirectoryInParallel({
		loadFiles: async () => {
			events.push('files started')
			return 'files'
		},
		loadConfig: () => {
			events.push('config started')
			return config.promise
		},
		isCurrent: () => true,
		onFiles: () => events.push('files rendered'),
		onConfig: () => events.push('config rendered'),
	})
	await Promise.resolve()
	assert.deepEqual(events, [
		'config started',
		'files started',
		'files rendered',
	])
	config.resolve('config')
	await work
	assert.equal(events.at(-1), 'config rendered')
})

test('a late old configuration cannot restore old folder permissions', async () => {
	const requests = createLatestRequest()
	const first = requests.begin()
	const config = deferred()
	let permission = 'cleared'
	const work = loadDirectoryInParallel({
		loadFiles: async () => [],
		loadConfig: () => config.promise,
		isCurrent: first.isCurrent,
		onFiles: () => {},
		onConfig: (value) => {
			permission = value
		},
	})
	await Promise.resolve()
	requests.begin()
	config.resolve('old upload permission')
	await work
	assert.equal(permission, 'cleared')
})

test('failed configuration is reported without losing an already loaded file list', async () => {
	let filesRendered = false
	let configApplied = false
	await assert.rejects(
		loadDirectoryInParallel({
			loadFiles: async () => [],
			loadConfig: async () => {
				throw new Error('timeout')
			},
			isCurrent: () => true,
			onFiles: () => {
				filesRendered = true
			},
			onConfig: () => {
				configApplied = true
			},
		}),
		/timeout/
	)
	assert.equal(filesRendered, true)
	assert.equal(configApplied, false)
})

test('cancelled list failure is ignored and current network failure is propagated', async () => {
	const request = createLatestRequest()
	const first = request.begin()
	const files = deferred()
	const work = loadDirectoryInParallel({
		loadFiles: () => files.promise,
		loadConfig: async () => ({}),
		isCurrent: first.isCurrent,
		onFiles: () => assert.fail('stale list'),
		onConfig: () => assert.fail('stale config'),
	})
	request.cancel()
	files.reject(new Error('network'))
	await work
	await assert.rejects(
		loadDirectoryInParallel({
			loadFiles: async () => {
				throw new Error('network')
			},
			loadConfig: async () => ({}),
			isCurrent: () => true,
			onFiles: () => assert.fail('failed list'),
			onConfig: () => assert.fail('config without list'),
		}),
		/network/
	)
})
