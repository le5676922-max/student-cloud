// One request and confirmed result per application/session context. Consumers
// share this controller; a layout unmount never cancels another consumer's read.
export function createSiteConfigBootstrap({ state, load, apply }) {
	let context,
		controller,
		pending,
		value,
		sequence = 0
	Object.assign(state, {
		ready: false,
		loading: false,
		error: '',
		needsInstall: false,
	})
	function reset(nextContext) {
		controller?.abort()
		controller = pending = value = undefined
		context = nextContext
		sequence++
		Object.assign(state, {
			ready: false,
			loading: false,
			error: '',
			needsInstall: false,
		})
	}
	function ensure(nextContext) {
		if (!nextContext) {
			reset()
			return Promise.resolve(null)
		}
		if (nextContext !== context) reset(nextContext)
		if (state.ready) return Promise.resolve(value)
		if (pending) return pending
		if (state.error || state.needsInstall) return Promise.resolve(null)
		controller = new AbortController()
		const signal = controller.signal,
			current = ++sequence
		const isCurrent = () => sequence === current && !signal.aborted
		state.loading = true
		pending = (async () => {
			try {
				const response = await Promise.resolve().then(() =>
					load({ signal, timeout: 10000, showDefaultMsg: false })
				)
				if (!isCurrent()) return null
				const data = response?.data
				if (
					!data ||
					typeof data !== 'object' ||
					Array.isArray(data) ||
					typeof data.installed !== 'boolean' ||
					(data.accountModel != null &&
						data.accountModel !== '' &&
						!['independent', 'legacy'].includes(data.accountModel))
				)
					throw new Error('Invalid site configuration')
				if (data.installed === false) {
					state.needsInstall = true
					return data
				}
				apply(data)
				value = data
				state.ready = true
				return data
			} catch {
				if (isCurrent())
					state.error = '站点配置读取失败，暂时无法打开页面。请重新读取。'
				return null
			} finally {
				if (isCurrent()) {
					state.loading = false
					pending = undefined
				}
			}
		})()
		return pending
	}
	return {
		state,
		ensure,
		reset: () => reset(),
		cancel: () => reset(),
		retry() {
			if (pending) return pending
			state.error = ''
			state.needsInstall = false
			return ensure(context)
		},
	}
}
