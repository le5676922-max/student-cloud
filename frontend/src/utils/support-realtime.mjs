export function supportSocketUrl({ baseUrl = '', origin, path, ticket }) {
	if (path !== '/ws/support' || typeof ticket !== 'string' || !ticket)
		throw new Error('客服连接凭证无效')
	const base = new URL(baseUrl || origin, origin)
	if (
		!['http:', 'https:'].includes(base.protocol) ||
		base.username ||
		base.password
	)
		throw new Error('客服服务地址无效')
	base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
	base.pathname = base.pathname.replace(/\/$/, '') + path
	base.search = ''
	base.hash = ''
	base.searchParams.set('ticket', ticket)
	return base.href
}

/** Short-lived single-use tickets and bounded reconnects; this channel never replays writes. */
export function createSupportRealtime({
	getTicket,
	baseUrl = () => '',
	origin = () => window.location.origin,
	WebSocketImpl = globalThis.WebSocket,
	onState = () => {},
	onChange = () => {},
	setTimer = setTimeout,
	clearTimer = clearTimeout,
	maxRetries = 6,
}) {
	let epoch = 0,
		active = false,
		retries = 0,
		socket = null,
		request = null,
		retryTimer = null,
		heartbeatTimer = null,
		pongTimer = null
	const clearTimers = () => {
		for (const timer of [retryTimer, heartbeatTimer, pongTimer])
			if (timer !== null) clearTimer(timer)
		retryTimer = heartbeatTimer = pongTimer = null
	}
	const current = (version, candidate) =>
		active && version === epoch && (!candidate || socket === candidate)
	function disconnect() {
		active = false
		epoch++
		request?.abort()
		request = null
		clearTimers()
		const previous = socket
		socket = null
		if (previous) {
			previous.onopen =
				previous.onmessage =
				previous.onclose =
				previous.onerror =
					null
			try {
				previous.close()
			} catch {
				/* A connecting socket may already have been closed. */
			}
		}
	}
	function retryLater(version) {
		if (!current(version)) return
		clearTimers()
		if (retries >= maxRetries) {
			onState('offline')
			return
		}
		onState('reconnecting')
		const delay = Math.min(1000 * 2 ** retries++, 30000)
		retryTimer = setTimer(() => connect(version), delay)
	}
	function heartbeat(version, candidate) {
		heartbeatTimer = setTimer(() => {
			if (!current(version, candidate)) return
			try {
				candidate.send(JSON.stringify({ type: 'ping' }))
				pongTimer = setTimer(() => endSocket(version, candidate), 10000)
			} catch {
				endSocket(version, candidate)
			}
		}, 25000)
	}
	function endSocket(version, candidate) {
		try {
			candidate.close()
		} catch {
			if (current(version, candidate)) {
				socket = null
				retryLater(version)
			}
		}
	}
	async function connect(version) {
		if (!current(version)) return
		request = new AbortController()
		try {
			const response = await getTicket({ signal: request.signal })
			if (!current(version)) return
			const ticket = response?.data || response
			const candidate = new WebSocketImpl(
				supportSocketUrl({ ...ticket, baseUrl: baseUrl(), origin: origin() })
			)
			let readySeen = false
			socket = candidate
			// Abandoned handshakes must not remain CONNECTING forever.
			pongTimer = setTimer(() => endSocket(version, candidate), 12000)
			candidate.onopen = () => {
				if (!current(version, candidate)) return
				clearTimers()
				onState('connected')
				onChange(null)
				heartbeat(version, candidate)
			}
			candidate.onmessage = (event) => {
				if (!current(version, candidate) || typeof event.data !== 'string')
					return
				try {
					const message = JSON.parse(event.data)
					if (message.type === 'pong') {
						// A successful heartbeat, not merely HTTP 101, proves stability.
						retries = 0
						if (pongTimer !== null) clearTimer(pongTimer)
						pongTimer = null
						heartbeat(version, candidate)
					} else if (message.type === 'support.ready' && !readySeen) {
						// The server has now registered this subscriber. Reload after that
						// barrier to cover changes committed between HTTP 101 and registration.
						readySeen = true
						onChange(null)
					} else if (
						message.type === 'support.changed' &&
						typeof message.conversationId === 'string'
					)
						onChange(message.conversationId)
				} catch {
					/* A malformed notification never becomes chat content. */
				}
			}
			candidate.onerror = () => {
				if (current(version, candidate)) endSocket(version, candidate)
			}
			candidate.onclose = (event) => {
				if (!current(version, candidate)) return
				socket = null
				if (event?.code === 1008) {
					clearTimers()
					onState('offline')
					return
				}
				retryLater(version)
			}
		} catch {
			if (current(version)) retryLater(version)
		}
	}
	function start() {
		disconnect()
		if (!WebSocketImpl) {
			onState('unsupported')
			return
		}
		active = true
		retries = 0
		onState('connecting')
		connect(epoch)
	}
	return { start, retry: start, stop: disconnect }
}

export const supportStatusLabel = (status) =>
	({ WAITING: '等待接待', ACTIVE: '沟通中', CLOSED: '已结束' }[status] ||
	'状态未知')

export function mergeSupportMessages(current, incoming, limit = 500) {
	const items = new Map(current.map((message) => [message.id, message]))
	for (const message of incoming) {
		if (Number.isSafeInteger(message?.id) && message.id > 0)
			items.set(message.id, message)
	}
	return [...items.values()]
		.sort((left, right) => left.id - right.id)
		.slice(-limit)
}
