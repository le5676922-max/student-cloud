import test from 'node:test'
import assert from 'node:assert/strict'
import { taskStatus, taskStatusLabel, taskProgress, createTaskPoller } from '../src/utils/agent-task.mjs'

test('only pending tasks expire; successful or cancelled tasks cannot become pending again', () => {
	assert.equal(taskStatus({ status: 'PENDING', expiresAt: 100 }, 101), 'EXPIRED')
	assert.equal(taskStatus({ status: 'PENDING', expiresAt: 100 }, 99), 'PENDING')
	for (const status of ['SUCCEEDED', 'FAILED', 'PARTIAL', 'CANCELLED', 'RUNNING', 'INTERRUPTED']) assert.equal(taskStatus({ status, expiresAt: 100 }, 101), status)
})

function timers() {
	const callbacks = new Map()
	let id = 0
	return {
		callbacks,
		schedule: (callback) => { callbacks.set(++id, callback); return id },
		unschedule: (key) => callbacks.delete(key),
		async tick() { const [key, callback] = callbacks.entries().next().value; callbacks.delete(key); await callback() },
	}
}

test('background task polling stops at terminal state and never polls pending tasks', async () => {
	const clock = timers()
	const updates = []
	let calls = 0
	const poller = createTaskPoller({ ...clock, load: async () => ({ status: ++calls === 1 ? 'RUNNING' : 'SUCCEEDED' }), onUpdate: (task) => updates.push(task.status), onError: assert.fail })
	poller.start({ id: 'id', status: 'PENDING' })
	assert.equal(clock.callbacks.size, 0)
	poller.start({ id: 'id', status: 'RUNNING' })
	await clock.tick()
	await clock.tick()
	assert.deepEqual(updates, ['RUNNING', 'SUCCEEDED'])
	assert.equal(clock.callbacks.size, 0)
})

test('unmount aborts an in-flight task poll and ignores its late response', async () => {
	const clock = timers()
	let finish, signal
	const poller = createTaskPoller({ ...clock, load: (_id, requestSignal) => { signal = requestSignal; return new Promise((resolve) => { finish = resolve }) }, onUpdate: assert.fail, onError: assert.fail })
	poller.start({ id: 'id', status: 'RUNNING' })
	const running = clock.tick()
	poller.stop()
	assert.equal(signal.aborted, true)
	finish({ status: 'SUCCEEDED' })
	await running
	assert.equal(clock.callbacks.size, 0)
})

test('polling backs off and stops after three consecutive errors', async () => {
	const clock = timers()
	let failures = 0
	const poller = createTaskPoller({ ...clock, load: async () => { throw new Error('offline') }, onUpdate: assert.fail, onError: () => failures++ })
	poller.start({ id: 'id', status: 'RUNNING' })
	await clock.tick()
	await clock.tick()
	await clock.tick()
	assert.equal(failures, 1)
	assert.equal(clock.callbacks.size, 0)
})
test('partial failure does not present unexecuted steps as completed', () => {
	assert.deepEqual(taskProgress({ steps: [1, 2, 3], results: [{ index: 0, success: true }, { index: 1, success: false }] }), { succeeded: 1, failed: 1, remaining: 1 })
	assert.equal(taskStatusLabel('PARTIAL'), '部分完成，已停止')
	assert.equal(taskStatusLabel('UNKNOWN'), '未知状态')
})
