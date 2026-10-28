import assert from 'node:assert/strict'
import test from 'node:test'
import * as downloads from '../src/utils/private-file-download.mjs'

const fixture = (chunks = [new Uint8Array([1, 2]), new Uint8Array([3])]) => {
	const events = []
	let index = 0
	const reader = {
		async read() {
			events.push('read')
			return index < chunks.length
				? { value: chunks[index++], done: false }
				: { done: true }
		},
		async cancel() {
			events.push('cancel')
		},
		releaseLock() {
			events.push('release')
		},
	}
	const writable = {
		async write(chunk) {
			events.push(['write', ...chunk])
		},
		async close() {
			events.push('close')
		},
		async abort() {
			events.push('abort')
		},
	}
	const handle = {
		async createWritable() {
			events.push('open')
			return writable
		},
	}
	const response = {
		data: { getReader: () => reader },
		headers: {
			'content-type': 'application/octet-stream',
			'content-length': '3',
		},
	}
	return { events, reader, writable, handle, response }
}
const run = (f, overrides = {}) =>
	downloads.writePrivateDownloadStream({
		response: f.response,
		handle: f.handle,
		expectedSize: 3,
		isCurrent: () => true,
		...overrides,
	})

test('private download size is a bounded exact integer, including empty files', () => {
	for (const size of [0, 1, 50 * 1024 * 1024])
		assert.equal(downloads.privateDownloadSize(size), size)
	for (const size of [
		undefined,
		null,
		'',
		'3',
		NaN,
		Infinity,
		-1,
		0.5,
		50 * 1024 * 1024 + 1,
	])
		assert.throws(() => downloads.privateDownloadSize(size))
})

test('save picker runs before network with the frozen suggested filename', async () => {
	let options
	const handle = {}
	const promise = downloads.pickPrivateDownloadFile(
		{
			isSecureContext: true,
			navigator: { userActivation: { isActive: true } },
			showSaveFilePicker(value) {
				options = value
				return Promise.resolve(handle)
			},
		},
		'original.txt'
	)
	assert.equal(options.suggestedName, 'original.txt')
	assert.equal(await promise, handle)
})

test('unsupported browser or missing transient activation uses Blob fallback without opening picker', async () => {
	for (const browser of [
		{},
		{
			isSecureContext: false,
			navigator: { userActivation: { isActive: true } },
		},
		{
			isSecureContext: true,
			navigator: { userActivation: { isActive: false } },
		},
	]) {
		assert.equal(
			await downloads.pickPrivateDownloadFile(
				{
					...browser,
					showSaveFilePicker() {
						assert.fail('picker must not open')
					},
				},
				'file.txt'
			),
			null
		)
	}
})

test('picker cancellation and permission refusal propagate, unsupported API alone permits fallback', async () => {
	for (const name of [
		'AbortError',
		'NotAllowedError',
		'SecurityError',
		'NotSupportedError',
	]) {
		const error = Object.assign(new Error(name), { name })
		const pending = downloads.pickPrivateDownloadFile(
			{
				isSecureContext: true,
				navigator: { userActivation: { isActive: true } },
				showSaveFilePicker() {
					return Promise.reject(error)
				},
			},
			'file.txt'
		)
		if (name === 'SecurityError' || name === 'NotSupportedError')
			assert.equal(await pending, null)
		else await assert.rejects(pending, (value) => value === error)
	}
})

test('stream writes each chunk before reading the next, and commits only at exact EOF', async () => {
	const f = fixture()
	assert.equal(await run(f), 3)
	assert.deepEqual(f.events, [
		'open',
		'read',
		['write', 1, 2],
		'read',
		['write', 3],
		'read',
		'close',
		'release',
	])
})

test('empty stream commits a valid zero byte file', async () => {
	const f = fixture([])
	f.response.headers['content-length'] = '0'
	assert.equal(await run(f, { expectedSize: 0 }), 0)
	assert.ok(f.events.includes('close'))
})

test('short, overlong, non-byte and broken response streams never commit partial files', async () => {
	for (const kind of ['short', 'overlong', 'invalid', 'broken']) {
		const f = fixture(
			kind === 'short'
				? [new Uint8Array([1])]
				: kind === 'overlong'
				? [new Uint8Array(4)]
				: ['bad']
		)
		if (kind === 'broken')
			f.reader.read = async () => {
				throw new Error('connection lost')
			}
		let aborted = 0
		await assert.rejects(run(f, { abortNetwork: () => aborted++ }))
		assert.equal(f.events.includes('close'), false)
		assert.equal(f.events.filter((event) => event === 'abort').length, 1)
		assert.ok(f.events.includes('cancel'))
		assert.ok(f.events.includes('release'))
		assert.equal(aborted, 1)
	}
})

test('invalid MIME or content length is rejected and response cancelled before opening disk', async () => {
	for (const headers of [
		{ 'content-type': 'application/json', 'content-length': '3' },
		{ 'content-type': 'application/octet-stream', 'content-length': '2' },
		{ 'content-type': 'application/octet-stream', 'content-length': '3.0' },
		{
			'content-type': 'application/octet-stream',
			'content-length': String(51 * 1024 * 1024),
		},
	]) {
		const f = fixture()
		f.response.headers = headers
		await assert.rejects(run(f))
		assert.equal(f.events.includes('open'), false)
		assert.ok(f.events.includes('cancel'))
		assert.ok(f.events.includes('release'))
	}
})

test('write failure aborts disk and transport without closing or retrying', async () => {
	const f = fixture()
	const error = new Error('disk full')
	f.writable.write = async () => {
		throw error
	}
	let aborted = 0
	await assert.rejects(
		run(f, { abortNetwork: () => aborted++ }),
		(value) => value === error
	)
	assert.ok(f.events.includes('abort'))
	assert.ok(f.events.includes('cancel'))
	assert.equal(f.events.includes('close'), false)
	assert.equal(aborted, 1)
})

test('account change during a pending read cancels stream and aborts disk promptly', async () => {
	const f = fixture()
	const controller = new AbortController()
	f.reader.read = () => new Promise(() => {})
	const pending = run(f, { signal: controller.signal })
	await Promise.resolve()
	await Promise.resolve()
	controller.abort()
	await assert.rejects(pending, { name: 'AbortError' })
	assert.ok(f.events.includes('cancel'))
	assert.ok(f.events.includes('abort'))
	assert.equal(f.events.includes('close'), false)
})

test('context is rechecked after pending disk write and immediately before commit', async () => {
	const f = fixture()
	let current = true
	f.writable.write = async () => {
		current = false
	}
	await assert.rejects(run(f, { isCurrent: () => current }), {
		name: 'AbortError',
	})
	assert.ok(f.events.includes('abort'))
	assert.equal(f.events.includes('close'), false)
})

test('cancellation while a chunk is being written aborts the temporary file and network', async () => {
	const f = fixture()
	const controller = new AbortController()
	f.writable.write = () => {
		queueMicrotask(() => controller.abort())
		return new Promise(() => {})
	}
	let aborted = 0
	await assert.rejects(
		run(f, { signal: controller.signal, abortNetwork: () => aborted++ }),
		{ name: 'AbortError' }
	)
	assert.ok(f.events.includes('abort'))
	assert.ok(f.events.includes('cancel'))
	assert.ok(f.events.includes('release'))
	assert.equal(f.events.includes('close'), false)
	assert.equal(aborted, 1)
})

test('disk-open and close failures release the response and never publish an incomplete file', async () => {
	for (const phase of ['open', 'close']) {
		const f = fixture()
		const error = new Error('disk unavailable')
		if (phase === 'open')
			f.handle.createWritable = async () => {
				throw error
			}
		else
			f.writable.close = async () => {
				throw error
			}
		await assert.rejects(run(f), (value) => value === error)
		assert.ok(f.events.includes('cancel'))
		assert.ok(f.events.includes('release'))
		if (phase === 'close') assert.ok(f.events.includes('abort'))
	}
})

test('chunked response without content length still requires exact snapshot size', async () => {
	const f = fixture()
	delete f.response.headers['content-length']
	assert.equal(await run(f), 3)
	assert.ok(f.events.includes('close'))
})
