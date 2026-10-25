import {
	canAccessAdministration,
	canAccessWorkspaceAdministration,
} from './roles.mjs'

const supportedRoles = new Set(['ADMIN', 'TENANT_ADMIN', 'USER'])

export function isAuthenticatedUser(loginInfo) {
	return loginInfo?.isLogin === true && supportedRoles.has(loginInfo?.role)
}

export function isPublicRoute(path) {
	return (
		/^\/login(?:\/[^/]+)?\/?$/.test(path) ||
		path === '/install' ||
		path === '/install/' ||
		path === '/sso' ||
		path === '/sso/'
	)
}

// Only return a same-site business route. Never let query parameters choose a URL.
export function safeInternalRedirect(value) {
	if (typeof value !== 'string' || value !== value.trim()) return null
	try {
		const decoded = decodeURIComponent(value)
		if (
			!decoded.startsWith('/') ||
			decoded.startsWith('//') ||
			decoded.includes('\\') ||
			[...decoded].some((character) => {
				const code = character.charCodeAt(0)
				return code < 32 || code === 127
			})
		)
			return null
		const url = new URL(value, 'https://cloud.invalid')
		const decodedPath = decodeURIComponent(url.pathname)
		if (
			url.origin !== 'https://cloud.invalid' ||
			url.pathname.startsWith('//') ||
			decodedPath.startsWith('//') ||
			isPublicRoute(new URL(decodedPath, 'https://cloud.invalid').pathname)
		)
			return null
		return url.pathname + url.search + url.hash
	} catch {
		return null
	}
}

export function loginRoute(requestedPath) {
	return {
		path: '/login',
		query: { redirect: safeInternalRedirect(requestedPath) || '/' },
	}
}

export function loginUrl(requestedPath) {
	return (
		'/login?redirect=' +
		encodeURIComponent(loginRoute(requestedPath).query.redirect)
	)
}

export function loginHomePath(loginInfo) {
	if (
		loginInfo?.role === 'ADMIN' &&
		(loginInfo.isAdmin === true || loginInfo.admin === true)
	)
		return '/admin'
	if (loginInfo?.role === 'TENANT_ADMIN') return '/workspace-admin'
	return '/'
}

function roleCanAccess(path, loginInfo) {
	if (isWorkspaceStorageRoute(path) && loginInfo?.role === 'TENANT_ADMIN')
		return canAccessWorkspaceAdministration(loginInfo)
	if (path === '/admin' || path.startsWith('/admin/'))
		return canAccessAdministration(loginInfo)
	if (path === '/workspace-admin' || path.startsWith('/workspace-admin/'))
		return canAccessWorkspaceAdministration(loginInfo)
	return true
}

export function isWorkspaceStorageRoute(path) {
	return (
		path === '/admin/storage-list' ||
		/^\/admin\/storage-edit(?:\/[0-9]+)?\/?$/.test(path)
	)
}

export function loginDestination(loginInfo, requestedPath) {
	const redirect = safeInternalRedirect(requestedPath)
	const identity = {
		...loginInfo,
		isLogin: true,
		isAdmin: loginInfo?.isAdmin === true || loginInfo?.admin === true,
	}
	if (
		redirect &&
		roleCanAccess(new URL(redirect, 'https://cloud.invalid').pathname, identity)
	)
		return redirect
	return loginHomePath(loginInfo)
}

export function createAuthenticationGuard({
	loadLoginInfo,
	updateLoginInfo,
	clearLoginInfo,
	getCredentialIdentity,
	navigationTimeoutMs = 10000,
}) {
	let navigationId = 0
	let activeNavigation
	let activeCheck
	const unavailableCredential = Symbol('Unreadable navigation credential')
	const credentialIdentity = () => {
		try {
			return getCredentialIdentity?.()
		} catch {
			return unavailableCredential
		}
	}
	const startCheck = (key) => {
		const controller = new AbortController()
		let timer, onAbort
		const check = { key, controller, promise: null }
		const deadline = new Promise((resolve, reject) => {
			onAbort = () => reject(new Error('Login check canceled'))
			controller.signal.addEventListener('abort', onAbort, { once: true })
			timer = setTimeout(() => {
				reject(new Error('Login check timed out'))
				controller.abort()
			}, navigationTimeoutMs)
		})
		check.promise = Promise.race([
			Promise.resolve().then(() =>
				loadLoginInfo({
					signal: controller.signal,
					timeout: navigationTimeoutMs,
				})
			),
			deadline,
		]).finally(() => {
			clearTimeout(timer)
			controller.signal.removeEventListener('abort', onAbort)
			if (activeCheck === check) activeCheck = undefined
		})
		activeCheck = check
		return check
	}
	return async (to) => {
		const currentId = ++navigationId
		activeNavigation?.abort()
		const controller = new AbortController()
		activeNavigation = controller
		if (isPublicRoute(to.path)) {
			activeCheck?.controller.abort()
			return true
		}
		let onAbort
		let key
		try {
			key = credentialIdentity()
			if (key === unavailableCredential) {
				activeCheck?.controller.abort()
				throw new Error('Cannot read navigation credential')
			}
			let check = activeCheck
			if (
				!check ||
				typeof key !== 'string' ||
				!key ||
				check.key !== key ||
				check.controller.signal.aborted
			) {
				check?.controller.abort()
				check = startCheck(key)
			}
			// Rapid clicks share only the request still in flight for the same credential.
			// Completed permission results are never cached across navigations.
			const superseded = new Promise((resolve, reject) => {
				onAbort = () => reject(new Error('Navigation superseded'))
				controller.signal.addEventListener('abort', onAbort, { once: true })
			})
			const loginInfo = await Promise.race([check.promise, superseded])
			if (currentId !== navigationId || key !== credentialIdentity())
				return false
			updateLoginInfo(loginInfo)
			if (!isAuthenticatedUser(loginInfo)) return loginRoute(to.fullPath)
			if (!roleCanAccess(to.path, loginInfo)) return '/'
			// The retired anonymous account has no editable permissions.
			if (to.path === '/admin/user-edit/2') return '/admin/user-list'
			return true
		} catch {
			if (currentId !== navigationId || key !== credentialIdentity())
				return false
			clearLoginInfo()
			return loginRoute(to.fullPath)
		} finally {
			controller.signal.removeEventListener('abort', onAbort)
			if (activeNavigation === controller) activeNavigation = undefined
		}
	}
}
