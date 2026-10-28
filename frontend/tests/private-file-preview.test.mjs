import assert from 'node:assert/strict'
import test from 'node:test'
import {
	createPrivatePreviewSession,
	privatePreviewTarget,
} from '../src/utils/private-file-preview.mjs'

const row = (extra = {}) => ({
	type: 'FILE',
	name: 'note.md',
	path: '/',
	size: 10,
	authenticatedContent: true,
	previewAvailable: true,
	previewKind: 'text',
	previewUrl: '/api/drives/private-drive/preview?path=%2Fnote.md',
	...extra,
})
const deferred = () => {
	let resolve
	const promise = new Promise((done) => {
		resolve = done
	})
	return { promise, resolve }
}

test('private geometry uses a bounded authenticated buffer and clears it on close without public URLs', async () => {
	const body = 'v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n'
	const app = fixture(async () => ({
		data: new Blob([body], { type: 'application/octet-stream' }),
	}))
	const modelRow = row({
		name: 'mesh.obj',
		previewKind: 'model',
		size: new Blob([body]).size,
		previewUrl: '/api/drives/private-drive/preview?path=%2Fmesh.obj',
	})
	assert.equal(await app.session.open(modelRow, 'private-drive'), true)
	assert.equal(app.state.kind, 'model')
	assert.equal(new TextDecoder().decode(app.state.buffer), body)
	assert.equal(app.created.length, 0)
	assert.throws(() =>
		privatePreviewTarget(
			{ ...modelRow, size: 10 * 1048576 + 1 },
			'private-drive'
		)
	)
	app.session.close()
	assert.equal(app.state.buffer, null)
})
function fixture(request) {
	let state
	const created = [],
		revoked = [],
		calls = []
	const session = createPrivatePreviewSession({
		request: (...args) => {
			calls.push(args)
			return request(...args)
		},
		onChange: (value) => {
			state = value
		},
		createUrl: (blob) => {
			const url = `blob:owned-${created.length}`
			created.push({ url, blob })
			return url
		},
		revokeUrl: (url) => revoked.push(url),
	})
	return {
		session,
		calls,
		created,
		revoked,
		get state() {
			return state
		},
	}
}
test('private preview endpoints are exact protected paths with no foreign origin or credential query', () => {
	assert.equal(
		privatePreviewTarget(row(), 'private-drive').url,
		row().previewUrl
	)
	for (const bad of [
		'https://external.invalid/data',
		'//external.invalid/data',
		'/api/drives/other-drive/preview?path=%2Fnote.md',
		'/api/drives/private-drive/preview?path=%2Fnote.md&token=secret',
		'/api/drives/private-drive/preview?path=%2F..%2Fsecret',
		'/api/drives/private-drive/preview?path=%2Fother.md',
	])
		assert.throws(() =>
			privatePreviewTarget(row({ previewUrl: bad }), 'private-drive')
		)
	assert.throws(() =>
		privatePreviewTarget(
			row({ previewKind: 'external-office' }),
			'private-drive'
		)
	)
	assert.throws(() =>
		privatePreviewTarget(row({ size: 1048577 }), 'private-drive')
	)
})
test('Markdown arrives through authenticated transport and remains inert UTF-8 text without resource URLs', async () => {
	const text =
		'# 资料\n<img src="https://external.invalid/a" onerror="alert(1)">'
	const app = fixture(async () => ({
		data: new Blob([text], { type: 'text/plain;charset=UTF-8' }),
	}))
	assert.equal(
		await app.session.open(
			row({ size: new Blob([text]).size }),
			'private-drive'
		),
		true
	)
	assert.equal(app.state.text, text)
	assert.equal(app.state.kind, 'text')
	assert.equal(app.created.length, 0)
	assert.equal(app.calls[0][0], row().previewUrl)
	assert.ok(app.calls[0][1] instanceof AbortSignal)
})
test('replacing an in-flight private preview aborts it, ignores late bytes, and revokes only owned object URLs', async () => {
	const first = deferred()
	let count = 0
	const app = fixture(() =>
		count++ === 0
			? first.promise
			: Promise.resolve({
					data: new Blob(['%PDF-1.7'], { type: 'application/pdf' }),
			  })
	)
	const waiting = app.session.open(row(), 'private-drive')
	const pdf = row({
		name: 'a.pdf',
		size: 8,
		previewKind: 'pdf',
		previewUrl: '/api/drives/private-drive/preview?path=%2Fa.pdf',
	})
	await app.session.open(pdf, 'private-drive')
	assert.equal(app.calls[0][1].aborted, true)
	assert.equal(app.state.kind, 'pdf')
	assert.equal(app.created.length, 1)
	first.resolve({ data: new Blob(['stale'], { type: 'text/plain' }) })
	await waiting
	assert.equal(app.state.kind, 'pdf')
	assert.equal(app.created.length, 1)
	app.session.close()
	assert.equal(app.state.visible, false)
	assert.deepEqual(app.revoked, ['blob:owned-0'])
	app.session.close()
	assert.equal(app.revoked.length, 1)
})
test('closing or leaving before response completion prevents stale content and object URL allocation', async () => {
	const pending = deferred(),
		app = fixture(() => pending.promise)
	const waiting = app.session.open(row(), 'private-drive')
	app.session.close()
	assert.equal(app.calls[0][1].aborted, true)
	pending.resolve({ data: new Blob(['secret'], { type: 'text/plain' }) })
	await waiting
	assert.equal(app.state.visible, false)
	assert.equal(app.state.text, '')
	assert.equal(app.created.length, 0)
})
test('unsupported content types, malformed UTF-8 and transport internals never become preview markup', async () => {
	for (const response of [
		new Blob(['<svg>'], { type: 'image/svg+xml' }),
		new Blob(['{"msg":"denied"}'], { type: 'application/json' }),
		new Blob([new Uint8Array([0xc3, 0x28])], { type: 'text/plain' }),
	]) {
		const app = fixture(async () => ({ data: response }))
		assert.equal(await app.session.open(row(), 'private-drive'), false)
		assert.equal(app.state.text, '')
		assert.equal(app.created.length, 0)
		assert.ok(app.state.error)
	}
	const app = fixture(async () => {
		throw new Error('private server filesystem /managed/secret')
	})
	await app.session.open(row(), 'private-drive')
	assert.doesNotMatch(app.state.error, /managed|secret/)
})
test('audio and video keep authenticated transport and allocate only owned URLs', async () => {
	for (const [kind, type, name] of [
		['audio', 'audio/mpeg', 'lesson.mp3'],
		['video', 'video/mp4', 'lesson.mp4'],
	]) {
		const app = fixture(async () => ({ data: new Blob(['media'], { type }) }))
		assert.equal(
			await app.session.open(
				row({
					name,
					size: 5,
					previewKind: kind,
					previewUrl: `/api/drives/private-drive/preview?path=%2F${name}`,
				}),
				'private-drive'
			),
			true
		)
		assert.equal(app.state.kind, kind)
		assert.equal(app.state.mediaType, type)
		assert.equal(app.created.length, 1)
		app.session.close()
		assert.equal(app.revoked.length, 1)
	}
})
test('mismatched file length or Blob type is never accepted as a preview', async () => {
	for (const response of [
		{ data: new Blob(['short'], { type: 'text/plain' }) },
		{
			data: new Blob(['json error'], { type: 'application/json' }),
			headers: { 'content-type': 'text/plain' },
		},
	]) {
		const app = fixture(async () => response)
		assert.equal(await app.session.open(row(), 'private-drive'), false)
		assert.equal(app.created.length, 0)
		assert.ok(app.state.error)
	}
})
test('office parsing owns the same cancellation signal and cannot publish a model after closing', async () => {
	let state, signal, resolve
	const session = createPrivatePreviewSession({
		request: async () => ({
			data: new Blob(['office'], {
				type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
			}),
		}),
		parseDocument: (_buffer, _name, value) => {
			signal = value
			return new Promise((done) => {
				resolve = done
			})
		},
		onChange: (value) => {
			state = value
		},
	})
	const pending = session.open(
		row({
			name: 'course.docx',
			size: 6,
			previewKind: 'office',
			previewUrl: '/api/drives/private-drive/preview?path=%2Fcourse.docx',
		}),
		'private-drive'
	)
	for (let index = 0; index < 10 && !resolve; index++)
		await new Promise((done) => setImmediate(done))
	assert.ok(signal instanceof AbortSignal)
	session.close()
	assert.equal(signal.aborted, true)
	resolve({
		kind: 'document',
		blocks: [{ type: 'paragraph', text: 'old private data' }],
	})
	await pending
	assert.equal(state.visible, false)
	assert.equal(state.document, null)
})
