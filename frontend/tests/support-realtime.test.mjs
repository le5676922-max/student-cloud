import test from 'node:test'
import assert from 'node:assert/strict'
import {
	createSupportRealtime,
	supportSocketUrl,
	mergeSupportMessages,
} from '../src/utils/support-realtime.mjs'

const flush = async () => {
	for (let index = 0; index < 8; index++) await Promise.resolve()
}
function deferred() {
	let resolve
	const promise = new Promise((done) => {
		resolve = done
	})
	return { promise, resolve }
}
function fixture(overrides = {}) {
	const timers = new Map(),
		sockets = [],
		states = [],
		changes = [],
		requests = []
	let timerId = 0
	class Socket {
		constructor(url) {
			this.url = url
			this.sent = []
			sockets.push(this)
		}
		send(value) {
			this.sent.push(JSON.parse(value))
		}
		close() {
			this.closed = true
			this.onclose?.()
		}
	}
	const manager = createSupportRealtime({
		WebSocketImpl: Socket,
		origin: () => 'https://cloud.invalid',
		baseUrl: () => '',
		getTicket: async (options) => {
			requests.push(options)
			return {
				data: { ticket: `single-use-${requests.length}`, path: '/ws/support' },
			}
		},
		onState: (state) => states.push(state),
		onChange: (id) => changes.push(id),
		setTimer: (callback, milliseconds) => {
			const id = ++timerId
			timers.set(id, { callback, milliseconds })
			return id
		},
		clearTimer: (id) => timers.delete(id),
		...overrides,
	})
	return {
		manager,
		sockets,
		states,
		changes,
		requests,
		timers,
		runTimer(milliseconds) {
			const timer = [...timers].find(
				([, value]) => value.milliseconds === milliseconds
			)
			assert.ok(timer, `missing ${milliseconds} timer`)
			timers.delete(timer[0])
			timer[1].callback()
		},
	}
}

test('support URL uses the configured backend and only the short-lived ticket', () => {
	const url = new URL(
		supportSocketUrl({
			baseUrl: 'https://api.invalid/backend/',
			origin: 'https://ui.invalid',
			path: '/ws/support',
			ticket: 'one+use/token',
		})
	)
	assert.equal(
		url.href,
		'wss://api.invalid/backend/ws/support?ticket=one%2Buse%2Ftoken'
	)
	assert.deepEqual([...url.searchParams.keys()], ['ticket'])
	assert.equal(
		supportSocketUrl({
			baseUrl: '/backend',
			origin: 'http://ui.invalid',
			path: '/ws/support',
			ticket: 'x',
		}),
		'ws://ui.invalid/backend/ws/support?ticket=x'
	)
	for (const values of [
		{ path: '/ws/other' },
		{ baseUrl: 'https://user:secret@api.invalid' },
		{ baseUrl: 'file:///local' },
		{ ticket: '' },
	])
		assert.throws(() =>
			supportSocketUrl({
				origin: 'https://ui.invalid',
				path: '/ws/support',
				ticket: 'x',
				...values,
			})
		)
})

test('reconnect obtains a new ticket and never replays chat writes', async () => {
	const view = fixture()
	view.manager.start()
	await flush()
	assert.equal(view.sockets.length, 1)
	view.sockets[0].onopen()
	view.sockets[0].onmessage({
		data: JSON.stringify({ type: 'support.changed', conversationId: 'mine' }),
	})
	view.sockets[0].close()
	assert.equal(view.states.at(-1), 'reconnecting')
	view.runTimer(1000)
	await flush()
	assert.equal(view.requests.length, 2)
	assert.notEqual(view.sockets[0].url, view.sockets[1].url)
	assert.deepEqual(view.changes, [null, 'mine'])
	assert.deepEqual(
		view.sockets.flatMap((socket) => socket.sent),
		[]
	)
	view.manager.stop()
	assert.equal(view.timers.size, 0)
})

test('ticket failures back off a bounded number of times and manual retry can recover', async () => {
	let calls = 0
	const view = fixture({
		maxRetries: 2,
		getTicket: async () => {
			calls++
			throw new Error('offline')
		},
	})
	view.manager.start()
	await flush()
	view.runTimer(1000)
	await flush()
	view.runTimer(2000)
	await flush()
	assert.equal(calls, 3)
	assert.equal(view.states.at(-1), 'offline')
	assert.equal(view.timers.size, 0)
	view.manager.retry()
	await flush()
	assert.equal(calls, 4)
	view.manager.stop()
})

test('stopping aborts the ticket and a late response cannot create a socket', async () => {
	const ticket = deferred()
	let signal
	const view = fixture({
		getTicket: (options) => {
			signal = options.signal
			return ticket.promise
		},
	})
	view.manager.start()
	view.manager.stop()
	assert.equal(signal.aborted, true)
	ticket.resolve({ data: { ticket: 'late', path: '/ws/support' } })
	await flush()
	assert.equal(view.sockets.length, 0)
	assert.equal(view.timers.size, 0)
})

test('policy close after an open socket does not request another ticket automatically', async () => {
	const view = fixture()
	view.manager.start()
	await flush()
	view.sockets[0].onopen()
	view.sockets[0].onclose({ code: 1008 })
	await flush()
	assert.equal(view.requests.length, 1)
	assert.equal(view.states.at(-1), 'offline')
	assert.equal(view.timers.size, 0)
	view.manager.retry()
	await flush()
	assert.equal(view.requests.length, 2)
	view.manager.stop()
})

test('repeated HTTP upgrades followed by immediate drops remain bounded before a stable pong', async () => {
	const view = fixture({ maxRetries: 2 })
	view.manager.start()
	await flush()
	view.sockets[0].onopen()
	view.sockets[0].onclose({ code: 1006 })
	view.runTimer(1000)
	await flush()
	view.sockets[1].onopen()
	view.sockets[1].onclose({ code: 1006 })
	view.runTimer(2000)
	await flush()
	view.sockets[2].onopen()
	view.sockets[2].onclose({ code: 1006 })
	assert.equal(view.states.at(-1), 'offline')
	assert.equal(view.requests.length, 3)
	assert.equal(view.timers.size, 0)
	view.manager.stop()
})

test('heartbeat sends only ping, pong reschedules it, and stale socket messages are ignored', async () => {
	const view = fixture()
	view.manager.start()
	await flush()
	const socket = view.sockets[0]
	socket.onopen()
	view.runTimer(25000)
	assert.deepEqual(socket.sent, [{ type: 'ping' }])
	socket.onmessage({ data: '{bad-json' })
	socket.onmessage({ data: JSON.stringify({ type: 'pong' }) })
	assert.equal(
		[...view.timers.values()].some((timer) => timer.milliseconds === 10000),
		false
	)
	const oldMessage = socket.onmessage
	view.manager.retry()
	await flush()
	oldMessage({
		data: JSON.stringify({ type: 'support.changed', conversationId: 'stale' }),
	})
	assert.deepEqual(view.changes, [null])
	view.manager.stop()
})

test('missing WebSocket leaves HTTP usable without an automatic timer loop', () => {
	const view = fixture({ WebSocketImpl: null })
	view.manager.start()
	assert.deepEqual(view.states, ['unsupported'])
	assert.equal(view.requests.length, 0)
	assert.equal(view.timers.size, 0)
})

test('message merging is ordered, idempotent and bounded', () => {
	const old = [{ id: 2, content: 'old' }, { id: 1 }]
	assert.deepEqual(
		mergeSupportMessages(
			old,
			[{ id: 2, content: 'new' }, { id: 3 }, { id: NaN }],
			2
		),
		[{ id: 2, content: 'new' }, { id: 3 }]
	)
	assert.equal(old[0].content, 'old')
})
