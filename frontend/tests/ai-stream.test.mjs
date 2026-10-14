import assert from 'node:assert/strict'
import test from 'node:test'
import { consumeAiStream } from '../src/utils/ai-stream.mjs'

const encode = (event, data) => `event: ${event}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`
const response = (text) => new Response(new ReadableStream({
	start(controller) {
		// One byte per chunk exercises split UTF-8 characters and CRLF delimiters.
		for (const byte of new TextEncoder().encode(text)) controller.enqueue(new Uint8Array([byte]))
		controller.close()
	},
}), { headers: { 'Content-Type': 'text/event-stream' } })

test('delivers incremental tokens and preserves authoritative final citations and plans', async () => {
	const final = { answer: '尚未修改文件', knowledge: [{ id: 'user-test' }], citations: [{ reference: 'M1-S1' }], plans: [{ id: 'task-1', status: 'PENDING' }] }
	const events = []
	const result = await consumeAiStream(response(
		encode('progress', { stage: 'model' }) + encode('token', { text: '中文片段' }) + encode('done', final)
	), { onEvent: (event, data) => events.push([event, data]) })
	assert.deepEqual(result, final)
	assert.equal(events[1][1].text, '中文片段')
	assert.deepEqual(events.map(([name]) => name), ['progress', 'token', 'done'])
})

test('partial transport termination is an error, not a completed answer', async () => {
	await assert.rejects(consumeAiStream(response(encode('token', { text: '半句' }))), /未完成/)
})

test('structured server error is surfaced after tokens and no fake done is produced', async () => {
	await assert.rejects(consumeAiStream(response(encode('error', { message: '本轮对话已超时' }))), /超时/)
	await assert.rejects(consumeAiStream(new Response(JSON.stringify({ msg: '请登录' }), { status: 401 })), /请登录/)
})

test('abort cancels a stalled response reader promptly', async () => {
	const controller = new AbortController()
	let cancelled = false
	const stream = new ReadableStream({ cancel() { cancelled = true } })
	const pending = consumeAiStream(new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } }), { signal: controller.signal })
	controller.abort()
	await assert.rejects(pending, { name: 'AbortError' })
	assert.equal(cancelled, true)
})

test('oversized unfinished events are rejected and stream is closed', async () => {
	const body = new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('data: ' + 'x'.repeat(256_001))) } })
	await assert.rejects(consumeAiStream(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })), /片段过长/)
})
