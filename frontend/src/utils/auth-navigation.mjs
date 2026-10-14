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
	if (path === '/admin' || path.startsWith('/admin/'))
		return canAccessAdministration(loginInfo)
	if (path === '/workspace-admin' || path.startsWith('/workspace-admin/'))
		return canAccessWorkspaceAdministration(loginInfo)
	return true
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
	navigationTimeoutMs = 10000,
}) {
	let navigationId = 0
	let activeController
	return async (to) => {
		const currentId = ++navigationId
		activeController?.abort()
		if (isPublicRoute(to.path)) return true
		const controller = new AbortController()
		activeController = controller
		let timer
		let onAbort
		try {
			// A newer navigation cancels this check, even when its transport ignores abort.
			const deadline = new Promise((resolve, reject) => {
				onAbort = () => reject(new Error('Navigation superseded'))
				controller.signal.addEventListener('abort', onAbort, { once: true })
				timer = setTimeout(() => {
					reject(new Error('Login check timed out'))
					controller.abort()
				}, navigationTimeoutMs)
			})
			const loginInfo = await Promise.race([
				Promise.resolve().then(() =>
					loadLoginInfo({
						signal: controller.signal,
						timeout: navigationTimeoutMs,
					})
				),
				deadline,
			])
			if (currentId !== navigationId) return false
			updateLoginInfo(loginInfo)
			if (!isAuthenticatedUser(loginInfo)) return loginRoute(to.fullPath)
			if (!roleCanAccess(to.path, loginInfo)) return '/'
			// The retired anonymous account has no editable permissions.
			if (to.path === '/admin/user-edit/2') return '/admin/user-list'
			return true
		} catch {
			if (currentId !== navigationId) return false
			clearLoginInfo()
			return loginRoute(to.fullPath)
		} finally {
			clearTimeout(timer)
			controller.signal.removeEventListener('abort', onAbort)
			if (activeController === controller) activeController = undefined
		}
	}
}
