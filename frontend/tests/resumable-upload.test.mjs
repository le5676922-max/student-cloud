import assert from 'node:assert/strict'
import test from 'node:test'
import { createHash, webcrypto } from 'node:crypto'
import { hashUploadManifest, isResumableCandidate, tryResumableUpload, readResumableStatus, RESUMABLE_CHUNK_SIZE as CHUNK } from '../src/utils/resumable-upload.mjs'

const ID = '12345678-1234-1234-1234-123456789abc'
const CAPS = { enabled: true, chunkSize: CHUNK, maxFileSize: 2 * 1024 ** 3, expiresInSeconds: 86400 }
const file = () => new File([new Uint8Array(CHUNK * 2 + 17).fill(7)], 'notes.bin')
const session = (size, receivedChunks = [], state = 'OPEN') => ({ id: ID, state, chunkSize: CHUNK, size, receivedChunks, expiresAt: Date.now() + 86400000, message: state })
const options = (item, request, extra = {}) => ({ file: item, uploadType: 'PROXY', storageKey: 'local-one', path: '/course', cryptoApi: webcrypto, request, ...extra })

test('candidate gating retains original small and non-proxy upload workflows', async () => {
	assert.equal(isResumableCandidate({ size: CHUNK * 2 }, 'PROXY'), true)
	assert.equal(isResumableCandidate({ size: CHUNK * 2 - 1 }, 'PROXY'), false)
	assert.equal(isResumableCandidate({ size: CHUNK * 2 }, 'S3'), false)
	const deniedRequest = () => { throw new Error('must not request') }
	assert.deepEqual(await tryResumableUpload(options({ size: 1 }, deniedRequest)), { handled: false })
	let requests = 0
	assert.deepEqual(await tryResumableUpload(options(file(), async () => { requests++; return { data: { enabled: false } } })), { handled: false })
	assert.equal(requests, 1)
})

test('manifest hashes bounded exact slices and newline-separated SHA-256 digests', async () => {
	const item = file(), slices = [], progress = []
	const tracked = { size: item.size, slice: (start, end) => { slices.push([start, end]); return item.slice(start, end) } }
	const result = await hashUploadManifest(tracked, { cryptoApi: webcrypto, onHashProgress: (event) => progress.push(event.checked) })
	const expected = []
	for (const [start, end] of slices) expected.push(createHash('sha256').update(new Uint8Array(await item.slice(start, end).arrayBuffer())).digest('hex'))
	assert.deepEqual(result.chunkHashes, expected)
	assert.equal(result.manifestHash, createHash('sha256').update(expected.join('\n')).digest('hex'))
	assert.deepEqual(slices, [[0, CHUNK], [CHUNK, CHUNK * 2], [CHUNK * 2, CHUNK * 2 + 17]])
	assert.deepEqual(progress, [CHUNK, CHUNK * 2, CHUNK * 2 + 17])
})

test('confirmed chunks resume without transfer, exact raw remainder is hashed, completion occurs once', async () => {
	const item = file(), requests = [], received = [1], events = [], states = []
	const result = await tryResumableUpload(options(item, async (request) => {
		requests.push(request)
		if (request.url.endsWith('/capabilities')) return { data: CAPS }
		if (request.url.endsWith('/init')) {
			assert.equal(request.data.storageKey, 'local-one')
			assert.equal(request.data.path, '/course')
			assert.equal(request.data.chunkHashes.length, 3)
			return { data: session(item.size, received) }
		}
		if (request.method === 'PUT') {
			const index = Number(request.url.split('/').at(-1))
			assert.equal(request.headers['Content-Type'], 'application/octet-stream')
			assert.equal(request.headers['X-Upload-Chunk-SHA256'], createHash('sha256').update(new Uint8Array(await request.data.arrayBuffer())).digest('hex'))
			request.onUploadProgress({ loaded: 100000000 })
			received.push(index)
			return { data: session(item.size, received) }
		}
		return { data: session(item.size, received, 'COMPLETED') }
	}, { onProgress: (event) => events.push(event), onState: (state) => states.push(state.state) }))
	assert.equal(result.handled, true)
	assert.deepEqual(requests.filter((value) => value.method === 'PUT').map((value) => value.url.split('/').at(-1)), ['0', '2'])
	assert.equal(requests.filter((value) => value.url.endsWith('/complete')).length, 1)
	assert.equal(events[0].loaded, CHUNK)
	assert.equal(events[0].resumed, true)
	assert.equal(events.at(-1).loaded, item.size)
	assert.ok(events.every((event) => event.loaded <= item.size))
	assert.ok(states.includes('HASHING') && states.includes('COMPLETING'))
})

test('completed initialization is idempotent and COMPLETING never replays publication', async () => {
	for (const state of ['COMPLETED', 'COMPLETING']) {
		const item = file(), requests = []
		const pending = tryResumableUpload(options(item, async (request) => {
			requests.push(request)
			return { data: request.url.endsWith('/capabilities') ? CAPS : session(item.size, [0, 1, 2], state) }
		}))
		if (state === 'COMPLETED') assert.equal((await pending).session.state, state)
		else await assert.rejects(pending, (error) => error.name === 'ResumableCompletionPendingError' && error.session.id === ID)
		assert.equal(requests.length, 2)
	}
})

test('capability failure never silently falls back; invalid receipts cannot trigger writes', async () => {
	const item = file()
	await assert.rejects(tryResumableUpload(options(item, async () => { throw new Error('unavailable') })), /unavailable/)
	let requests = 0
	await assert.rejects(tryResumableUpload(options(item, async () => {
		requests++
		return { data: requests === 1 ? CAPS : { ...session(item.size), receivedChunks: [0, 0] } }
	})), /无效/)
	assert.equal(requests, 2)
	await assert.rejects(hashUploadManifest(item, { cryptoApi: null }), /HTTPS/)
})

test('abort during hash or chunk prevents further requests and preserves saved chunks', async () => {
	const item = file(), controller = new AbortController()
	let requests = 0
	await assert.rejects(tryResumableUpload(options(item, async () => { requests++; return { data: CAPS } }, {
		signal: controller.signal, onHashProgress: () => controller.abort(),
	})), { name: 'AbortError' })
	assert.equal(requests, 1)
	const nextController = new AbortController(), methods = []
	await assert.rejects(tryResumableUpload(options(item, async (request) => {
		methods.push(request.method)
		if (request.url.endsWith('/capabilities')) return { data: CAPS }
		if (request.url.endsWith('/init')) return { data: session(item.size) }
		nextController.abort()
		return { data: session(item.size, [0]) }
	}, { signal: nextController.signal })), { name: 'AbortError' })
	assert.deepEqual(methods, ['POST', 'POST', 'PUT'])
})

test('lost completion response is uncertain, explicit follow-up performs only GET status', async () => {
	const item = file(), requests = []
	await assert.rejects(tryResumableUpload(options(item, async (request) => {
		requests.push(request)
		if (request.url.endsWith('/capabilities')) return { data: CAPS }
		if (request.url.endsWith('/init')) return { data: session(item.size, [0, 1, 2]) }
		throw new Error('socket closed after commit')
	})), (error) => error.name === 'ResumableCompletionPendingError' && /不要重复提交/.test(error.message))
	assert.equal(requests.filter((request) => request.url.endsWith('/complete')).length, 1)
	const checked = await readResumableStatus({ id: ID, size: item.size, request: async (request) => {
		assert.equal(request.method, 'GET')
		assert.equal(request.url, `/api/uploads/resumable/${ID}`)
		return { data: session(item.size, [0, 1, 2], 'COMPLETED') }
	} })
	assert.equal(checked.state, 'COMPLETED')
	await assert.rejects(readResumableStatus({ id: '../other-user', size: item.size, request: () => { throw new Error('must not request') } }), /无效/)
})

test('stale active context and dropped chunk receipt stop before completion', async () => {
	const item = file(), requests = [], active = { value: true }
	await assert.rejects(tryResumableUpload(options(item, async (request) => {
		requests.push(request)
		if (request.url.endsWith('/capabilities')) return { data: CAPS }
		if (request.url.endsWith('/init')) return { data: session(item.size, [1]) }
		return { data: session(item.size, [0]) }
	})), /状态不一致/)
	assert.ok(!requests.some((request) => request.url.endsWith('/complete')))
	await assert.rejects(tryResumableUpload(options(item, async () => { active.value = false; return { data: CAPS } }, { isActive: () => active.value })), { name: 'AbortError' })
})
