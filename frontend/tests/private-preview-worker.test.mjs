import assert from 'node:assert/strict'
import test from 'node:test'
import { parsePrivatePreviewDocument } from '../src/utils/private-preview-worker.mjs'

function fixture() {
	const worker = {
		terminations: 0,
		postMessage(value, transfer) {
			this.value = value
			this.transfer = transfer
		},
		terminate() {
			this.terminations++
		},
	}
	let timer,
		cleared = 0,
		created = 0
	return {
		worker,
		get timer() {
			return timer
		},
		get cleared() {
			return cleared
		},
		get created() {
			return created
		},
		options: {
			createWorker: () => {
				created++
				return worker
			},
			setTimer: (fn) => {
				timer = fn
				return 1
			},
			clearTimer: () => {
				cleared++
			},
		},
	}
}
test('successful document worker transfers bytes once, returns inert model and releases itself', async () => {
	const h = fixture(),
		buffer = new ArrayBuffer(8),
		pending = parsePrivatePreviewDocument(
			buffer,
			'x.docx',
			undefined,
			h.options
		)
	assert.deepEqual(h.worker.transfer, [buffer])
	h.worker.onmessage({
		data: { ok: true, value: { kind: 'document', blocks: [] } },
	})
	assert.deepEqual(await pending, { kind: 'document', blocks: [] })
	assert.equal(h.worker.terminations, 1)
	assert.equal(h.cleared, 1)
})
test('close, account change, and replacement abort the worker and ignore late model data', async () => {
	const h = fixture(),
		controller = new AbortController(),
		pending = parsePrivatePreviewDocument(
			new ArrayBuffer(8),
			'x.docx',
			controller.signal,
			h.options
		)
	controller.abort()
	await assert.rejects(pending, { name: 'AbortError' })
	h.worker.onmessage({
		data: {
			ok: true,
			value: { kind: 'document', blocks: [{ text: 'late private' }] },
		},
	})
	assert.equal(h.worker.terminations, 1)
	const before = fixture()
	controller.abort()
	await assert.rejects(
		parsePrivatePreviewDocument(
			new ArrayBuffer(0),
			'x.docx',
			controller.signal,
			before.options
		),
		{ name: 'AbortError' }
	)
	assert.equal(before.created, 0)
})
test('timeout and worker failure terminate parsing and never expose internal parser details', async () => {
	for (const failure of ['timeout', 'error', 'message']) {
		const h = fixture(),
			pending = parsePrivatePreviewDocument(
				new ArrayBuffer(8),
				'x.xlsx',
				undefined,
				h.options
			)
		if (failure === 'timeout') h.timer()
		else if (failure === 'error')
			h.worker.onerror({ message: 'private filesystem secret' })
		else
			h.worker.onmessage({
				data: { ok: false, error: 'private filesystem secret' },
			})
		await assert.rejects(
			pending,
			(error) =>
				error.privatePreviewMessage === true &&
				!/private filesystem/.test(error.message)
		)
		assert.equal(h.worker.terminations, 1)
	}
})
