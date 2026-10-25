const emptyState = () => ({
	enabled: false,
	maxEntries: 50,
	maxChars: 500,
	items: [],
	loaded: false,
	busy: false,
	confirmClear: false,
	error: '',
	formError: '',
	identityDenied: false,
	notice: '',
})

const safeContentErrors = new Set([
	'记忆内容不能为空且不能超过 500 个字符',
	'请勿在长期记忆中保存密码、验证码、密钥或令牌',
	'长期记忆仅保存偏好和背景，请勿保存文件链接或服务器路径',
	'长期记忆不能改变权限、系统规则或任务确认要求',
	'已有相同内容的记忆，请修改内容后再保存',
])

/** Only explicit pre-write content rejection is retryable; transport/CAS failures are uncertain. */
function memoryFailure(error, formWrite) {
	const response = error?.response || error
	const body = response?.data
	const status = Number(response?.status)
	const code = String(body?.code || '')
	const denied =
		status === 401 ||
		status === 403 ||
		code === '42000' ||
		code === '30000' ||
		/请先登录|重新登录|未登录|当前账号不存在或已被停用|加入申请尚未通过工作空间管理员审批/.test(
			body?.msg || ''
		)
	const fieldError =
		status === 400 &&
		code === '41000' &&
		typeof body?.data?.content === 'string'
			? body.data.content
			: ''
	const message =
		typeof body?.msg === 'string' && body.msg.trim()
			? body.msg.slice(0, 240)
			: '操作未完成，请刷新确认后重试。'
	return {
		message: fieldError ? fieldError.slice(0, 240) : message,
		recoverable:
			formWrite &&
			!denied &&
			status === 400 &&
			(Boolean(fieldError) || safeContentErrors.has(message)),
		denied,
	}
}

export function filterMemoryItems(items, query) {
	const words = String(query || '')
		.trim()
		.toLocaleLowerCase()
		.split(/\s+/)
		.filter(Boolean)
	return words.length
		? items.filter((item) =>
				words.every((word) => item.content.toLocaleLowerCase().includes(word))
		  )
		: [...items]
}

export function normalizeMemoryState(value) {
	if (
		!value ||
		typeof value.enabled !== 'boolean' ||
		!Array.isArray(value.items) ||
		value.items.length > 50 ||
		value.items.some(
			(item) =>
				!item ||
				typeof item.id !== 'string' ||
				!item.id ||
				item.id.length > 64 ||
				typeof item.content !== 'string' ||
				!item.content.trim() ||
				item.content.length > 500
		)
	) {
		throw new Error('记忆数据不完整，请刷新后重试')
	}
	return {
		enabled: value.enabled,
		maxEntries: Math.min(50, Math.max(1, Number(value.maxEntries) || 50)),
		maxChars: Math.min(500, Math.max(1, Number(value.maxChars) || 500)),
		items: value.items.map(({ id, content, createdAt }) => ({
			id,
			content,
			createdAt: typeof createdAt === 'string' ? createdAt : '',
		})),
	}
}

/** Bounded panel operations with account-generation isolation, including transports that ignore abort. */
export function createAiMemoryManager({ request, onChange = () => {} }) {
	let state = emptyState()
	let identity = ''
	let generation = 0
	let active
	let disposed = false
	const snapshot = () => ({ ...state, items: [...state.items] })
	const change = (patch) => {
		state = { ...state, ...patch }
		onChange(snapshot())
	}
	const available = () => !disposed && Boolean(identity) && !state.busy
	const mutable = () =>
		available() && state.loaded && !state.error && !state.confirmClear

	async function run(method, path, data, notice, formWrite = false) {
		if (!available()) return false
		const epoch = generation
		const controller = new AbortController()
		active = controller
		change({
			busy: true,
			error: '',
			formError: '',
			identityDenied: false,
			notice: '',
			confirmClear: false,
		})
		try {
			const response = await request(method, path, data, {
				signal: controller.signal,
			})
			if (disposed || generation !== epoch || controller.signal.aborted)
				return false
			const result = normalizeMemoryState(response?.data)
			change({ ...result, loaded: true, identityDenied: false, notice })
			return true
		} catch (error) {
			if (disposed || generation !== epoch || controller.signal.aborted)
				return false
			const failure = memoryFailure(error, formWrite)
			change({
				...(failure.denied ? { items: [], enabled: false } : {}),
				loaded: method === 'get' || failure.denied ? false : state.loaded,
				error: failure.recoverable ? '' : failure.message,
				formError: failure.recoverable ? failure.message : '',
				identityDenied: failure.denied,
			})
			return false
		} finally {
			if (active === controller) {
				active = undefined
				change({ busy: false })
			}
		}
	}
	const manager = {
		state: snapshot,
		setIdentity(value) {
			if (disposed) return Promise.resolve(false)
			const next = String(value || '')
			if (next === identity) return Promise.resolve(false)
			generation++
			active?.abort()
			active = undefined
			identity = next
			state = emptyState()
			onChange(snapshot())
			return identity ? manager.load() : Promise.resolve(false)
		},
		load: () => run('get', '', undefined, ''),
		clearFormError() {
			if (!disposed && !state.busy) change({ formError: '' })
		},
		remember(content) {
			if (!mutable() || !state.enabled) return Promise.resolve(false)
			const text = String(content || '').trim()
			if (!text || text.length > state.maxChars) return Promise.resolve(false)
			if (state.items.length >= state.maxEntries) return Promise.resolve(false)
			return run(
				'post',
				'',
				{ content: text },
				'已保存。下次对话可继续使用这条记忆。',
				true
			)
		},
		update(id, content, expectedContent) {
			const original = state.items.find((item) => item.id === id)
			if (!mutable() || !state.enabled || !original)
				return Promise.resolve(false)
			const text = String(content || '').trim()
			if (!text || text.length > state.maxChars) return Promise.resolve(false)
			const expected =
				expectedContent === undefined ? original.content : expectedContent
			if (
				typeof expected !== 'string' ||
				!expected ||
				expected.length > state.maxChars
			)
				return Promise.resolve(false)
			return run(
				'put',
				`/${encodeURIComponent(id)}`,
				{ content: text, expectedContent: expected },
				'已更新这条记忆。',
				true
			)
		},
		setEnabled(enabled) {
			if (!mutable()) return Promise.resolve(false)
			return run(
				'put',
				'/settings',
				{ enabled: Boolean(enabled) },
				enabled
					? '已启用长期记忆。'
					: '已暂停使用和保存记忆；已有条目仍保留，可随时删除。'
			)
		},
		forget(id) {
			if (!mutable() || !state.items.some((item) => item.id === id))
				return Promise.resolve(false)
			return run(
				'delete',
				`/${encodeURIComponent(id)}`,
				undefined,
				'已删除这条记忆。'
			)
		},
		askClear() {
			if (mutable() && state.items.length)
				change({ confirmClear: true, notice: '' })
		},
		cancelClear() {
			if (available()) change({ confirmClear: false })
		},
		clear() {
			if (!available() || !state.loaded || state.error || !state.confirmClear)
				return Promise.resolve(false)
			return run('delete', '', undefined, '已清空长期记忆。')
		},
		dispose() {
			disposed = true
			generation++
			active?.abort()
			active = undefined
		},
	}
	return manager
}
