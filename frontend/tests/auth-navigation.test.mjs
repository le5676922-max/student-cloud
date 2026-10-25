import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
	createAuthenticationGuard,
	isAuthenticatedUser,
	isPublicRoute,
	loginDestination,
	loginRoute,
	loginUrl,
	safeInternalRedirect,
} from '../src/utils/auth-navigation.mjs'

function harness(loginInfo, failure) {
	const state = { checks: 0, updates: [], clears: 0 }
	const guard = createAuthenticationGuard({
		async loadLoginInfo() {
			state.checks++
			if (failure) throw failure
			return loginInfo
		},
		updateLoginInfo: (info) => state.updates.push(info),
		clearLoginInfo: () => state.clears++,
	})
	return { guard, state }
}

test('only login, initialization and the SSO callback can open without an account', async () => {
	const { guard, state } = harness(null)
	for (const path of ['/login', '/login/private-entry', '/install', '/sso']) {
		assert.equal(isPublicRoute(path), true)
		assert.equal(await guard({ path, fullPath: path }), true)
	}
	assert.equal(state.checks, 0)
	for (const path of [
		'/',
		'/share/abc',
		'/login/a/b',
		'/install/storage',
		'/sso/other',
		'/401',
	])
		assert.equal(isPublicRoute(path), false)
})

test('anonymous file, workflow, share and administration visits require login and retain the destination', async () => {
	const { guard, state } = harness({
		isLogin: false,
		isAdmin: false,
		role: null,
	})
	for (const path of [
		'/',
		'/file',
		'/workspace-12/music/song.mp3',
		'/learning',
		'/agent-tasks',
		'/share/key',
		'/workspace-admin',
		'/admin/overview',
	]) {
		const fullPath = path + '?folder=a%20b&mode=preview#notes'
		assert.deepEqual(await guard({ path, fullPath }), {
			path: '/login',
			query: { redirect: fullPath },
		})
	}
	assert.equal(state.checks, 8)
	assert.equal(state.updates.length, 8)
})

test('missing, unknown and retired guest identities cannot access business pages', async () => {
	for (const loginInfo of [
		null,
		{},
		{ isLogin: false, role: 'USER' },
		{ isLogin: true },
		{ isLogin: true, role: 'GUEST' },
		{ isLogin: true, role: 'SUPER_ADMIN' },
	]) {
		assert.equal(isAuthenticatedUser(loginInfo), false)
		assert.deepEqual(
			await harness(loginInfo).guard({ path: '/', fullPath: '/' }),
			loginRoute('/')
		)
	}
})

test('a course invitation survives the login round trip for members and workspace administrators', async () => {
	const invitation = '/courses?invite=' + 'c'.repeat(32)
	const { guard } = harness({ isLogin: false, role: null })
	const redirect = await guard({ path: '/courses', fullPath: invitation })
	assert.deepEqual(redirect, {
		path: '/login',
		query: { redirect: invitation },
	})
	for (const role of ['USER', 'TENANT_ADMIN']) {
		const identity = { isLogin: true, role, isAdmin: false }
		assert.equal(loginDestination(identity, redirect.query.redirect), invitation)
		assert.equal(
			await harness(identity).guard({ path: '/courses', fullPath: invitation }),
			true
		)
	}
})

test('an unavailable login check clears cached identity and fails closed', async () => {
	const { guard, state } = harness(null, new Error('offline'))
	assert.deepEqual(
		await guard({ path: '/share/key', fullPath: '/share/key?path=%2F' }),
		loginRoute('/share/key?path=%2F')
	)
	assert.equal(state.clears, 1)
	assert.deepEqual(state.updates, [])
})

test('a slow authentication check is bounded and fails closed', async () => {
	const state = { clears: 0, signal: null }
	const guard = createAuthenticationGuard({
		navigationTimeoutMs: 20,
		loadLoginInfo(config) {
			state.signal = config.signal
			return new Promise(() => {})
		},
		updateLoginInfo() {
			assert.fail('timed-out requests must not update identity')
		},
		clearLoginInfo() {
			state.clears++
		},
	})
	assert.deepEqual(
		await guard({
			path: '/admin/storage-list',
			fullPath: '/admin/storage-list',
		}),
		loginRoute('/admin/storage-list')
	)
	assert.equal(state.clears, 1)
	assert.equal(state.signal.aborted, true)
})

test('a public navigation cancels an old check without waiting or clearing the new session', async () => {
	let config
	let resolveOld
	const updates = []
	let clears = 0
	const guard = createAuthenticationGuard({
		loadLoginInfo(options) {
			config = options
			return new Promise((resolve) => {
				resolveOld = resolve
			})
		},
		updateLoginInfo(info) {
			updates.push(info)
		},
		clearLoginInfo() {
			clears++
		},
	})
	const old = guard({
		path: '/admin/storage-list',
		fullPath: '/admin/storage-list',
	})
	await Promise.resolve()
	assert.equal(await guard({ path: '/login', fullPath: '/login' }), true)
	assert.equal(config.signal.aborted, true)
	assert.equal(await old, false)
	resolveOld({ isLogin: true, role: 'ADMIN', isAdmin: true })
	await Promise.resolve()
	assert.equal(clears, 0)
	assert.deepEqual(updates, [])
})

test('rapid private navigation discards stale replies while the latest route still checks permissions', async () => {
	const pending = []
	const updates = []
	let clears = 0
	const guard = createAuthenticationGuard({
		loadLoginInfo(config) {
			return new Promise((resolve) => {
				pending.push({ resolve, config })
			})
		},
		updateLoginInfo(info) {
			updates.push(info)
		},
		clearLoginInfo() {
			clears++
		},
	})
	const first = guard({
		path: '/admin/storage-list',
		fullPath: '/admin/storage-list',
	})
	await Promise.resolve()
	const latest = guard({
		path: '/admin/user-list',
		fullPath: '/admin/user-list',
	})
	await Promise.resolve()
	assert.equal(pending[0].config.signal.aborted, true)
	assert.equal(await first, false)
	const user = { isLogin: true, role: 'USER', isAdmin: false }
	pending[1].resolve(user)
	assert.equal(await latest, '/')
	pending[0].resolve({ isLogin: true, role: 'ADMIN', isAdmin: true })
	await Promise.resolve()
	assert.deepEqual(updates, [user])
	assert.equal(clears, 0)
})

test('each account role can open business pages but administration remains separated', async () => {
	for (const role of ['ADMIN', 'TENANT_ADMIN', 'USER']) {
		const identity = {
			isLogin: true,
			role,
			isAdmin: role === 'ADMIN',
			tenantId: 12,
		}
		const { guard } = harness(identity)
		for (const path of [
			'/',
			'/learning',
			'/agent-tasks',
			'/share/key',
			'/workspace-12/folder',
		])
			assert.equal(await guard({ path, fullPath: path }), true)
		assert.equal(
			await guard({ path: '/admin/overview', fullPath: '/admin/overview' }),
			role === 'ADMIN' ? true : '/'
		)
		assert.equal(
			await guard({ path: '/workspace-admin', fullPath: '/workspace-admin' }),
			role === 'TENANT_ADMIN' ? true : '/'
		)
	}
	assert.equal(
		await harness({ isLogin: true, isAdmin: true, role: 'ADMIN' }).guard({
			path: '/admin/user-edit/2',
			fullPath: '/admin/user-edit/2',
		}),
		'/admin/user-list'
	)
})

test('login destinations reject external URLs, malformed values and authentication loops', () => {
	for (const value of [
		undefined,
		null,
		['/share/key'],
		'https://evil.example',
		'//evil.example',
		'/\\evil.example',
		'/%2Fevil.example',
		'/%5Cevil.example',
		'/%0Aevil.example',
		'/bad%zz',
		' /share/key',
		'/login',
		'/login/private',
		'/install',
		'/sso?token=secret',
		'/foo/../login',
		'/folder/..//evil.example',
		'/%2e%2e//evil.example',
		'/%6cogin',
	])
		assert.equal(safeInternalRedirect(value), null, String(value))
	assert.equal(
		safeInternalRedirect(
			'/share/key?path=%2Fdocuments%2Fa%20b&preview=true#file'
		),
		'/share/key?path=%2Fdocuments%2Fa%20b&preview=true#file'
	)
	const url = new URL(
		loginUrl('/share/key?folder=one&mode=preview#notes'),
		'https://cloud.invalid'
	)
	assert.equal(
		url.searchParams.get('redirect'),
		'/share/key?folder=one&mode=preview#notes'
	)
})

test('all account roles resume a requested share or file route after login', () => {
	for (const role of ['ADMIN', 'TENANT_ADMIN', 'USER']) {
		const identity = { role, admin: role === 'ADMIN', tenantId: 12 }
		assert.equal(
			loginDestination(identity, '/share/key?path=%2Freadme.md#preview'),
			'/share/key?path=%2Freadme.md#preview'
		)
		assert.equal(
			loginDestination(identity, '/workspace-12/study'),
			'/workspace-12/study'
		)
	}
	assert.equal(loginDestination({ role: 'USER' }, '/admin/overview'), '/')
	assert.equal(
		loginDestination({ role: 'TENANT_ADMIN', tenantId: 12 }, '/admin/overview'),
		'/workspace-admin'
	)
	assert.equal(
		loginDestination({ role: 'ADMIN', admin: true }, '//evil.example'),
		'/admin'
	)
	assert.equal(
		loginDestination(
			{ role: 'TENANT_ADMIN', tenantId: 12 },
			'/workspace-admin'
		),
		'/workspace-admin'
	)
})

test('the actual router installs the authentication guard and login uses the shared safe destination', async () => {
	const [router, login, navigation, settings, layout, userEditor] =
		await Promise.all(
			[
				'../src/modules/router.ts',
				'../src/pages/login.vue',
				'../src/utils/admin-navigation.mjs',
				'../src/pages/admin/security-setting/index.vue',
				'../src/layouts/file.vue',
				'../src/pages/admin/user-edit/index.vue',
			].map((path) => readFile(new URL(path, import.meta.url), 'utf8'))
		)
	assert.match(router, /router\.beforeEach\(\s*createAuthenticationGuard/)
	assert.match(
		login,
		/authenticatedDestination\.value = loginDestination\(\s*response\.data,\s*route\.query\.redirect\s*\)/
	)
	assert.match(login, /router\.replace\(authenticatedDestination\.value\)/)
	assert.doesNotMatch(login, /router\.replace\('\/'\)/)
	assert.doesNotMatch(navigation, /访客|href: '\/admin\/user-edit\/2'/)
	assert.doesNotMatch(settings, /guestIndexHtml|访客首页/)
	assert.doesNotMatch(layout, /res\.data\.guest|\/guest/)
	assert.doesNotMatch(userEditor, /访客|GUEST/)
})

test('tenant managers can open only source list/editor within the global administration route prefix', async () => {
	const identity = { isLogin: true, role: 'TENANT_ADMIN', tenantId: 9 }
	const { guard } = harness(identity)
	for (const path of [
		'/admin/storage-list',
		'/admin/storage-edit',
		'/admin/storage-edit/41',
	])
		assert.equal(await guard({ path, fullPath: path }), true)
	for (const path of [
		'/admin/overview',
		'/admin/user-list',
		'/admin/storage-local-requests',
		'/admin/storage-edit/abc',
		'/admin/storage-edit/41/extra',
	])
		assert.equal(await guard({ path, fullPath: path }), '/')
})
