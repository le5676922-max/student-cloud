function directoryPath(value) {
	if (
		typeof value !== 'string' ||
		!value.startsWith('/') ||
		value.includes('//') ||
		value.split('/').some((part) => part === '.' || part === '..')
	)
		return null
	return value.replace(/\/+$/, '') || '/'
}

function contextSnapshot(value) {
	const path = directoryPath(value?.path)
	if (
		!path ||
		typeof value?.storageKey !== 'string' ||
		!value.storageKey ||
		typeof value?.accountIdentity !== 'string' ||
		!value.accountIdentity
	)
		return null
	return {
		storageKey: value.storageKey,
		path,
		accountIdentity: value.accountIdentity,
	}
}

function sameContext(first, second) {
	return !!(
		first &&
		second &&
		first.storageKey === second.storageKey &&
		first.path === second.path &&
		first.accountIdentity === second.accountIdentity
	)
}

// Matching UI updates is not an authorization decision. Keep legal file names
// (including %, ?, # and spaces) intact rather than using Agent input rules.
export function fileChangeContextFromRoute(route, accountIdentity) {
	const parts = route?.params?.fullpath
	const path = Array.isArray(parts)
		? parts.every((part) => typeof part === 'string' && !part.includes('/'))
			? `/${parts.join('/')}`
			: null
		: typeof parts === 'string'
		? parts.startsWith('/')
			? parts
			: `/${parts}`
		: parts == null
		? '/'
		: null
	return contextSnapshot({
		storageKey: route?.params?.storageKey,
		path,
		accountIdentity,
	})
}

// A folder upload can create intermediate directories. Refresh only directories
// inside its captured base, never an unrelated route selected during the upload.
export function completedUploadFileChanges(target, uploadPath) {
	const context = contextSnapshot({ ...target, path: target?.directory })
	const path = directoryPath(uploadPath)
	if (!context || !path) return []
	const prefix = context.path === '/' ? '/' : `${context.path}/`
	if (path !== context.path && !path.startsWith(prefix)) return []
	const paths = [context.path]
	if (path !== context.path) {
		let parent = context.path === '/' ? '' : context.path
		for (const name of path.slice(prefix.length).split('/')) {
			parent += `/${name}`
			paths.push(parent)
		}
	}
	return paths.map((changedPath) => ({ ...context, path: changedPath }))
}

/** Coalesce completion events and wait for an existing load/dialog to finish. */
export function createFileChangeRefresh({
	getContext,
	refresh,
	isBusy = () => false,
	schedule = setTimeout,
	unschedule = clearTimeout,
	delay = 180,
}) {
	let timer,
		pending,
		revision = 0,
		disposed = false
	const cancel = () => {
		revision++
		if (timer !== undefined) unschedule(timer)
		timer = undefined
		pending = undefined
	}
	const scheduleRefresh = () => {
		const ticket = revision
		timer = schedule(() => {
			if (disposed || ticket !== revision) return
			timer = undefined
			if (!sameContext(pending, contextSnapshot(getContext()))) {
				pending = undefined
				return
			}
			if (isBusy()) {
				scheduleRefresh()
				return
			}
			pending = undefined
			// Directory loading owns its visible error/retry state; event callbacks
			// must not create unhandled promise rejections.
			try {
				Promise.resolve(refresh()).catch(() => {})
			} catch {
				/* no event rejection */
			}
		}, delay)
	}
	return {
		notify(change) {
			if (disposed) return false
			const context = contextSnapshot(getContext())
			if (
				!context ||
				context.storageKey !== change?.storageKey ||
				context.path !== directoryPath(change?.path) ||
				// Older Agent task events carry source/path only. Uploads always
				// include the captured account and may never cross account changes.
				('accountIdentity' in change &&
					change.accountIdentity !== context.accountIdentity)
			)
				return false
			if (pending && !sameContext(pending, context)) cancel()
			pending = context
			if (timer === undefined) scheduleRefresh()
			return true
		},
		cancel,
		dispose() {
			disposed = true
			cancel()
		},
	}
}
