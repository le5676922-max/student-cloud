/** Read POST SSE incrementally; unfinished output is never returned as a successful answer. */
export async function consumeAiStream(
	response,
	{ signal, onEvent = () => {} } = {}
) {
	if (
		!response.ok ||
		!response.headers.get('content-type')?.includes('text/event-stream')
	) {
		const error = await response.json().catch(() => ({}))
		throw new Error(error.msg || 'AI 服务未返回可用的流式响应，请稍后重试')
	}
	if (!response.body) throw new Error('当前浏览器不支持流式读取')
	const reader = response.body.getReader()
	const decoder = new TextDecoder()
	let buffer = ''
	let received = 0
	const abort = () => reader.cancel().catch(() => {})
	signal?.addEventListener('abort', abort, { once: true })
	try {
		for (;;) {
			if (signal?.aborted) throw new DOMException('已停止生成', 'AbortError')
			const { value, done } = await reader.read()
			if (signal?.aborted) throw new DOMException('已停止生成', 'AbortError')
			buffer += decoder.decode(value, { stream: !done })
			received += value?.length || 0
			if (received > 2 * 1024 * 1024)
				throw new Error('AI 响应过长，请缩小问题范围')
			let boundary
			while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
				const block = buffer.slice(0, boundary.index)
				buffer = buffer.slice(boundary.index + boundary[0].length)
				if (block.length > 256_000) throw new Error('AI 响应片段过长')
				let name = 'message'
				const data = []
				for (const line of block.split(/\r?\n/)) {
					if (line.startsWith('event:')) name = line.slice(6).trim()
					if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
				}
				if (!data.length) continue
				const payload = JSON.parse(data.join('\n'))
				if (name === 'error')
					throw new Error(payload.message || 'AI 对话未完成')
				onEvent(name, payload)
				if (name === 'done') {
					if (typeof payload.answer !== 'string' || !payload.answer.trim()) {
						throw new Error('AI 服务未返回最终答案')
					}
					return payload
				}
			}
			if (buffer.length > 256_000) throw new Error('AI 响应片段过长')
			if (done) throw new Error('连接已中断，未完成的内容仅供参考，请重试')
		}
	} finally {
		signal?.removeEventListener('abort', abort)
		await reader.cancel().catch(() => {})
		reader.releaseLock()
	}
}
