import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { createAuthenticationGuard } from '../src/utils/auth-navigation.mjs'
import { navigationCredentialIdentity } from '../src/utils/navigation-credential.mjs'

const route = (path) => ({ path, fullPath: path })
const tick = async () => {
	for (let step = 0; step < 4; step++) await Promise.resolve()
}
const deferred = () => {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { resolve, reject, promise }
}
const member = {
	isLogin: true,
	role: 'USER',
	isAdmin: false,
	username: 'member',
}
function fixture() {
	let key = 'synthetic-session-A'
	const requests = [],
		updates = []
	let clears = 0
	const guard = createAuthenticationGuard({
		getCredentialIdentity: () => key,
		loadLoginInfo(config) {
			const request = { ...deferred(), config }
			requests.push(request)
			return request.promise
		},
		updateLoginInfo: (info) => updates.push(info),
		clearLoginInfo: () => clears++,
	})
	return {
		guard,
		requests,
		updates,
		get clears() {
			return clears
		},
		setKey(value) {
			key = value
		},
	}
}

test('rapid private route clicks share one unfinished check without reusing completed authorization', async () => {
	const app = fixture()
	const first = app.guard(route('/learning'))
	await tick()
	const second = app.guard(route('/agent-tasks'))
	const latest = app.guard(route('/share/example'))
	await tick()
	assert.equal(await first, false)
	assert.equal(await second, false)
	assert.equal(
		app.requests.length,
		1,
		'switching targets must not restart a slow login request'
	)
	assert.equal(app.requests[0].config.signal.aborted, false)
	app.requests[0].resolve(member)
	assert.equal(await latest, true)
	assert.deepEqual(app.updates, [member])
	const next = app.guard(route('/admin/overview'))
	await tick()
	assert.equal(
		app.requests.length,
		2,
		'a later completed navigation must check the server again'
	)
	app.requests[1].resolve(member)
	assert.equal(await next, '/')
})

test('rotating credentials cancels the old shared request and prevents old administrator state from applying', async () => {
	const app = fixture()
	const first = app.guard(route('/admin/overview'))
	await tick()
	app.setKey('synthetic-session-B')
	const latest = app.guard(route('/admin/storage-list'))
	await tick()
	assert.equal(await first, false)
	assert.equal(app.requests.length, 2)
	assert.equal(app.requests[0].config.signal.aborted, true)
	app.requests[1].resolve(member)
	assert.equal(await latest, '/')
	app.requests[0].resolve({ isLogin: true, role: 'ADMIN', isAdmin: true })
	await tick()
	assert.deepEqual(app.updates, [member])
	assert.equal(app.clears, 0)
})

test('a credential change without a second navigation also prevents an outdated identity commit', async () => {
	const app = fixture()
	const pending = app.guard(route('/admin/overview'))
	await tick()
	app.setKey('replacement-session')
	app.requests[0].resolve({ isLogin: true, role: 'ADMIN', isAdmin: true })
	assert.equal(await pending, false)
	assert.deepEqual(app.updates, [])
	assert.equal(app.clears, 0, 'do not erase a replacement login session')
})

test('public routes cancel an unfinished shared login request and do not wait for it', async () => {
	const app = fixture()
	const pending = app.guard(route('/learning'))
	await tick()
	assert.equal(await app.guard(route('/login')), true)
	assert.equal(await pending, false)
	assert.equal(app.requests[0].config.signal.aborted, true)
	app.requests[0].resolve(member)
	await tick()
	assert.deepEqual(app.updates, [])
	assert.equal(app.clears, 0)
})

test('one failed shared request fails closed only for the final target', async () => {
	const app = fixture()
	const first = app.guard(route('/learning'))
	await tick()
	const latest = app.guard(route('/agent-tasks'))
	await tick()
	app.requests[0].reject(new Error('offline'))
	assert.equal(await first, false)
	assert.deepEqual(await latest, {
		path: '/login',
		query: { redirect: '/agent-tasks' },
	})
	assert.equal(app.requests.length, 1)
	assert.equal(app.clears, 1)
})

test('an unreadable credential aborts the prior check and fails closed without waiting for its deadline', async () => {
	let unreadable = false
	const requests = [],
		updates = []
	let clears = 0
	const guard = createAuthenticationGuard({
		getCredentialIdentity() {
			if (unreadable) throw new Error('browser access blocked')
			return 'synthetic-session'
		},
		loadLoginInfo(config) {
			const request = { ...deferred(), config }
			requests.push(request)
			return request.promise
		},
		updateLoginInfo: (info) => updates.push(info),
		clearLoginInfo: () => clears++,
	})
	const first = guard(route('/learning'))
	await tick()
	unreadable = true
	assert.deepEqual(await guard(route('/agent-tasks')), {
		path: '/login',
		query: { redirect: '/agent-tasks' },
	})
	assert.equal(await first, false)
	assert.equal(requests.length, 1)
	assert.equal(requests[0].config.signal.aborted, true)
	assert.equal(clears, 1)
	requests[0].resolve({ isLogin: true, role: 'ADMIN', isAdmin: true })
	await tick()
	assert.deepEqual(updates, [])
})

test('credential identity distinguishes API targets and supports cookie-only sessions without persistence', () => {
	const storage = { getItem: () => 'synthetic-local-session' }
	const local = navigationCredentialIdentity({
		storage,
		cookie: 'tongligong-student-cloud-token=cookie-value',
		baseUrl: '/one',
	})
	assert.notEqual(
		local,
		navigationCredentialIdentity({ storage, baseUrl: '/two' })
	)
	const cookieOnly = navigationCredentialIdentity({
		storage: {
			getItem() {
				throw new Error('storage blocked')
			},
		},
		cookie: 'first=a; tongligong-student-cloud-token=synthetic-cookie==',
	})
	assert.deepEqual(JSON.parse(cookieOnly), ['', 'synthetic-cookie=='])
	assert.equal(navigationCredentialIdentity({ cookie: 'other=value' }), '')
})

test('storage list API forwards timeout and abort to HTTP config rather than URL parameters', async () => {
	const source = await readFile(
		new URL('../src/api/home/home.js', import.meta.url),
		'utf8'
	)
	const calls = []
	const { getSourceListReq } = new Function(
		'axios',
		source.replace(/^import .*$/gm, '').replaceAll('export const ', 'const ') +
			'\nreturn { getSourceListReq }'
	)((request) => {
		calls.push(request)
		return Promise.resolve(request)
	})
	const controller = new AbortController()
	await getSourceListReq({
		signal: controller.signal,
		timeout: 200,
		showDefaultMsg: false,
	})
	assert.equal(calls[0].url, '/api/storage/list')
	assert.equal(calls[0].data, undefined)
	assert.equal(calls[0].config.signal, controller.signal)
	assert.equal(calls[0].config.timeout, 200)
	assert.equal(calls[0].config.showDefaultMsg, false)
	controller.abort()
	assert.equal(calls[0].config.signal.aborted, true)
	await getSourceListReq()
	assert.equal(calls[1].config.timeout, 10000)
})

test('file header consumes router-verified identity instead of issuing a duplicate stale login check', async () => {
	const [header, router] = await Promise.all([
		readFile(
			new URL('../src/components/layout/Header.vue', import.meta.url),
			'utf8'
		),
		readFile(new URL('../src/modules/router.ts', import.meta.url), 'utf8'),
	])
	assert.doesNotMatch(header, /checkLoginReq\s*\(/)
	assert.match(router, /createAuthenticationGuard\(/)
	assert.match(router, /getCredentialIdentity/)
	assert.match(router, /checkLoginReq\(/)
})
