import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import test from 'node:test'
import { createContext, Script } from 'node:vm'
import * as Vue from 'vue'
import * as navigation from '../src/utils/auth-navigation.mjs'
import * as preference from '../src/utils/login-preference.mjs'

// Execute the actual SFC setup; request mocks expose delayed and reordered replies.
const require = createRequire(import.meta.url)
const { parse, compileScript } = require('vue/compiler-sfc')
const page = await readFile(
	new URL('../src/pages/login.vue', import.meta.url),
	'utf8'
)
const { descriptor, errors } = parse(page)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'login-ux-flow-test' })
const executable = compiled.content
	.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '')
	.replace('export default', 'globalThis.LoginPage =')
const tokenKey = 'tongligong-student-cloud-token'

function deferred() {
	let resolve
	let reject
	const promise = new Promise((accept, decline) => {
		resolve = accept
		reject = decline
	})
	return { promise, resolve, reject }
}

async function flush() {
	for (let index = 0; index < 8; index++) {
		await Promise.resolve()
		await Vue.nextTick()
	}
}

function createLogin(options = {}) {
	const mounted = []
	const unmounted = []
	const replacements = []
	const pushes = []
	const messages = []
	const focus = []
	const writes = []
	const storage = new Map()
	const pendingDebounces = new Map()
	const calls = {}
	const route = Vue.reactive({
		params: { entry: options.entry || '' },
		query: { redirect: options.redirect || '/' },
	})
	const defaults = {
		installStatusReq: async () => ({ data: true }),
		validateLoginEntryReq: async () => ({ data: null }),
		checkLoginReq: async () => ({ data: { isLogin: false } }),
		loginVerifyModeReq: async () => ({ data: '' }),
		loginVerifyImgReq: async () => ({
			data: {
				uuid: 'default-captcha',
				imgBase64: 'data:image/png;base64,test',
			},
		}),
		loginReq: async () => ({ data: { token: 'test-token', role: 'USER' } }),
	}
	const requests = Object.fromEntries(
		Object.entries(defaults).map(([name, fallback]) => {
			calls[name] = []
			return [
				name,
				(...args) => {
					calls[name].push(args)
					return (options.api?.[name] || fallback)(...args)
				},
			]
		})
	)
	const localStorage = {
		getItem: (key) => storage.get(key) ?? null,
		removeItem: (key) => storage.delete(key),
		setItem(key, value) {
			if (key === tokenKey && options.storageFailure)
				throw new Error('Storage blocked')
			writes.push([key, String(value)])
			storage.set(key, String(value))
		},
	}
	const message = (value) => messages.push(value)
	message.success = (value) =>
		messages.push({ type: 'success', message: value })
	message.error = (value) => messages.push({ type: 'error', message: value })
	message.warning = (value) =>
		messages.push({ type: 'warning', message: value })
	const document = {
		querySelector(selector) {
			return {
				focus: () => focus.push(selector),
				scrollIntoView() {},
				querySelector: () => ({ focus: () => focus.push(selector) }),
			}
		},
		getElementById: (id) => ({ focus: () => focus.push(`#${id}`) }),
	}
	const sandbox = createContext({
		...Vue,
		...navigation,
		...preference,
		...requests,
		AbortController,
		console,
		structuredClone,
		setTimeout,
		clearTimeout,
		document,
		window: {
			localStorage,
			matchMedia: () => ({ matches: false }),
			setTimeout,
			clearTimeout,
		},
		ElMessage: message,
		useRoute: () => route,
		useRouter: () => ({
			replace: async (destination) => replacements.push(destination),
			push: async (destination) => pushes.push(destination),
		}),
		useTitle() {},
		useDebounceFn(callback) {
			return (...args) => pendingDebounces.set(callback, args)
		},
		onMounted: (callback) => mounted.push(callback),
		onBeforeUnmount: (callback) => unmounted.push(callback),
	})
	for (const [name, metadata] of Object.entries(compiled.imports)) {
		if (!(name in sandbox))
			sandbox[name] = metadata.source === 'vue' ? Vue[metadata.imported] : {}
	}
	new Script(executable, { filename: 'login-page.setup.js' }).runInContext(
		sandbox
	)
	const scope = Vue.effectScope()
	const state = scope.run(() => sandbox.LoginPage.setup({}, { expose() {} }))
	state.loginFormRef.value = {
		validate: options.validate || (async () => true),
		clearValidate() {},
		scrollToField() {},
	}
	let didUnmount = false
	return {
		state,
		route,
		calls,
		storage,
		writes,
		replacements,
		pushes,
		messages,
		focus,
		async mount() {
			for (const callback of mounted) await callback()
			await flush()
		},
		async flushDebounces() {
			await flush()
			const tasks = [...pendingDebounces.entries()]
			pendingDebounces.clear()
			await Promise.all(tasks.map(([callback, args]) => callback(...args)))
			await flush()
		},
		unmount() {
			if (didUnmount) return
			didUnmount = true
			for (const callback of unmounted) callback()
			scope.stop()
		},
	}
}

async function fillCredentials(view) {
	view.state.formData.value.username = 'student@example.test'
	view.state.formData.value.password = 'my study password'
	await view.flushDebounces()
}

test('entry checks block submission until initialization finishes', async (t) => {
	const entry = deferred()
	const view = createLogin({
		api: { validateLoginEntryReq: () => entry.promise },
	})
	t.after(() => view.unmount())
	const mounting = view.mount()
	await flush()
	assert.equal(view.state.bootstrapChecking.value, true)
	assert.equal(view.state.canSubmit.value, false)
	await view.state.submitForm()
	assert.equal(view.calls.loginReq.length, 0)
	assert.equal(view.state.loginError.value, '')
	entry.resolve({ data: null })
	await mounting
	assert.equal(view.state.loginEntryValid.value, true)
	assert.equal(view.state.bootstrapChecking.value, false)
	assert.equal(view.state.canSubmit.value, true)
})

test('a temporary entry network failure can be retried without becoming an invalid address', async (t) => {
	let attempts = 0
	const view = createLogin({
		api: {
			validateLoginEntryReq: async () => {
				if (++attempts === 1) throw new Error('Network Error')
				return { data: null }
			},
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	assert.equal(view.state.loginEntryValid.value, false)
	assert.equal(view.state.canSubmit.value, false)
	assert.ok(view.state.bootstrapError.value)
	assert.doesNotMatch(
		view.state.bootstrapError.value,
		/入口不可用|登录地址.*(?:错误|不正确)/
	)
	await view.state.initializeLogin()
	await flush()
	assert.equal(attempts, 2)
	assert.equal(view.state.bootstrapError.value, '')
	assert.equal(view.state.loginEntryValid.value, true)
	assert.equal(view.state.canSubmit.value, true)
})

test('entry navigation ignores an earlier check that finishes after the current one', async (t) => {
	const first = deferred()
	const second = deferred()
	const view = createLogin({
		entry: 'first-entry',
		api: {
			validateLoginEntryReq: (entry) =>
				entry === 'first-entry' ? first.promise : second.promise,
		},
	})
	t.after(() => view.unmount())
	const mounting = view.mount()
	await flush()
	view.route.params.entry = 'second-entry'
	await flush()
	assert.equal(view.calls.validateLoginEntryReq.length, 2)
	second.resolve({ data: null })
	await flush()
	assert.equal(view.state.loginEntry.value, 'second-entry')
	assert.equal(view.state.loginEntryValid.value, true)
	first.reject(new Error('late network failure'))
	await mounting
	assert.equal(view.state.bootstrapError.value, '')
	assert.equal(view.state.loginEntryValid.value, true)
	assert.equal(view.calls.checkLoginReq.length, 1)
})

test('login submits a credential snapshot once and navigates without dismissing a toast', async (t) => {
	const login = deferred()
	const view = createLogin({
		redirect: '/learning?course=1',
		api: { loginReq: () => login.promise },
	})
	t.after(() => view.unmount())
	await view.mount()
	await fillCredentials(view)
	const submitting = view.state.submitForm()
	await flush()
	assert.equal(view.calls.loginReq.length, 1)
	const payload = view.calls.loginReq[0][0]
	view.state.formData.value.password = 'changed while request pending'
	await view.state.submitForm()
	assert.equal(view.calls.loginReq.length, 1)
	assert.equal(payload.password, 'my study password')
	login.resolve({ data: { token: 'signed-in-token', role: 'USER' } })
	await submitting
	assert.equal(view.storage.get(tokenKey), 'signed-in-token')
	assert.deepEqual(view.replacements, ['/learning?course=1'])
	assert.ok(view.messages.some((item) => item.type === 'success'))
	assert.equal(view.state.loginSucceeded.value, true)
})

test('leaving the page cancels login and ignores a late successful reply', async (t) => {
	const login = deferred()
	const view = createLogin({ api: { loginReq: () => login.promise } })
	t.after(() => view.unmount())
	await view.mount()
	await fillCredentials(view)
	const submitting = view.state.submitForm()
	await flush()
	view.unmount()
	login.resolve({ data: { token: 'late-token', role: 'USER' } })
	await submitting
	assert.equal(view.storage.has(tokenKey), false)
	assert.deepEqual(view.replacements, [])
	assert.equal(view.state.loginSucceeded.value, false)
	const requestOptions = view.calls.loginReq[0].find((arg) => arg?.signal)
	assert.ok(
		requestOptions?.signal.aborted,
		'the submitted request is aborted on unmount'
	)
})

test('a login check resolving after unmount cannot navigate', async (t) => {
	const check = deferred()
	const view = createLogin({ api: { checkLoginReq: () => check.promise } })
	t.after(() => view.unmount())
	const mounting = view.mount()
	await flush()
	assert.equal(view.calls.checkLoginReq.length, 1)
	view.unmount()
	check.resolve({ data: { isLogin: true, role: 'USER' } })
	await mounting
	assert.deepEqual(view.replacements, [])
})

test('captcha refresh clears the old code and accepts only the newest image', async (t) => {
	const first = deferred()
	const second = deferred()
	let images = 0
	const view = createLogin({
		api: {
			loginVerifyImgReq: () =>
				++images === 1 ? first.promise : second.promise,
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	view.state.loginVerifyMode.value = 'image'
	view.state.formData.value.verifyCode = 'old-code'
	view.state.formData.value.verifyCodeUUID = 'old-uuid'
	const refreshing = view.state.loadLoginVerifyCodeImgData()
	assert.equal(view.state.formData.value.verifyCode, '')
	assert.equal(view.state.formData.value.verifyCodeUUID, '')
	assert.equal(view.state.captchaLoading.value, true)
	const newer = view.state.loadLoginVerifyCodeImgData()
	second.resolve({ data: { uuid: 'new-uuid', imgBase64: 'new-image' } })
	await newer
	first.resolve({ data: { uuid: 'stale-uuid', imgBase64: 'stale-image' } })
	await refreshing
	assert.equal(view.state.formData.value.verifyCodeUUID, 'new-uuid')
	assert.equal(view.state.loginVerifyCodeImgData.value.imgBase64, 'new-image')
	assert.equal(view.state.captchaLoading.value, false)
})

test('captcha network failure keeps stale codes unusable and allows a retry', async (t) => {
	let attempts = 0
	const view = createLogin({
		api: {
			loginVerifyImgReq: async () => {
				if (++attempts === 1) throw new Error('Network Error')
				return { data: { uuid: 'retried-uuid', imgBase64: 'retried-image' } }
			},
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	view.state.loginVerifyMode.value = 'image'
	view.state.formData.value.verifyCode = 'old-code'
	view.state.formData.value.verifyCodeUUID = 'old-uuid'
	await view.state.loadLoginVerifyCodeImgData()
	assert.equal(view.state.formData.value.verifyCode, '')
	assert.equal(view.state.formData.value.verifyCodeUUID, '')
	assert.equal(view.state.captchaLoading.value, false)
	assert.equal(view.state.canSubmit.value, false)
	await view.state.loadLoginVerifyCodeImgData()
	assert.equal(view.state.formData.value.verifyCodeUUID, 'retried-uuid')
	assert.equal(
		view.state.loginVerifyCodeImgData.value.imgBase64,
		'retried-image'
	)
})

test('verification mode belongs to the current account even when responses arrive out of order', async (t) => {
	const first = deferred()
	const second = deferred()
	const view = createLogin({
		api: {
			loginVerifyModeReq: (username) => {
				if (username === 'first@example.test') return first.promise
				if (username === 'second@example.test') return second.promise
				return Promise.resolve({ data: '' })
			},
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	view.state.formData.value.username = 'first@example.test'
	await flush()
	const older = view.state.loadLoginVerifyMode()
	view.state.formData.value.username = 'second@example.test'
	await flush()
	const newer = view.state.loadLoginVerifyMode()
	assert.equal(view.state.verificationChecking.value, true)
	assert.equal(view.state.canSubmit.value, false)
	second.resolve({ data: '2fa' })
	await newer
	first.resolve({ data: 'image' })
	await older
	assert.equal(view.state.loginVerifyMode.value, '2fa')
	assert.equal(view.state.verificationChecking.value, false)
	assert.equal(view.calls.loginVerifyImgReq.length, 0)
})

test('an unknown verification mode blocks submission and can recover with a retry', async (t) => {
	let attempts = 0
	const view = createLogin({
		api: {
			loginVerifyModeReq: async (username) => {
				if (!username) return { data: '' }
				if (++attempts === 1) throw new Error('Network Error')
				return { data: '2fa' }
			},
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	await fillCredentials(view)
	assert.ok(view.state.verificationError.value)
	assert.equal(view.state.canSubmit.value, false)
	await view.state.submitForm()
	assert.equal(view.calls.loginReq.length, 0)
	await view.state.loadLoginVerifyMode()
	assert.equal(view.state.verificationError.value, '')
	assert.equal(view.state.loginVerifyMode.value, '2fa')
	assert.equal(view.state.canSubmit.value, true)
})

test('blocked browser storage cannot show a successful login or navigate', async (t) => {
	const view = createLogin({ storageFailure: true })
	t.after(() => view.unmount())
	await view.mount()
	await fillCredentials(view)
	await view.state.submitForm()
	assert.equal(view.storage.has(tokenKey), false)
	assert.equal(view.state.loginSucceeded.value, false)
	assert.equal(view.state.loading.value, false)
	assert.match(view.state.loginError.value, /(?:存储|保存登录状态)/)
	assert.deepEqual(view.replacements, [])
	assert.equal(
		view.messages.some((item) => item.type === 'success'),
		false
	)
})

test('an unsupported verification response blocks login until retry returns a supported mode', async (t) => {
	let attempts = 0
	const view = createLogin({
		api: {
			loginVerifyModeReq: async () => ({
				data: ++attempts === 1 ? 'future-security-mode' : 'off',
			}),
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	assert.ok(view.state.verificationError.value)
	assert.equal(view.state.verificationChecking.value, false)
	assert.equal(view.state.canSubmit.value, false)
	await view.state.submitForm()
	assert.equal(view.calls.loginReq.length, 0)
	await view.state.loadLoginVerifyMode()
	assert.equal(view.state.loginVerifyMode.value, 'off')
	assert.equal(view.state.verificationError.value, '')
	assert.equal(view.state.canSubmit.value, true)
})

test('a rejected HTTP 200 entry business response is classified as an invalid login address', async (t) => {
	const view = createLogin({
		api: {
			validateLoginEntryReq: () =>
				Promise.reject({
					status: 200,
					data: { code: '500', msg: '安全登录入口不正确' },
				}),
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	assert.equal(view.state.entryAddressInvalid.value, true)
	assert.match(view.state.bootstrapError.value, /登录地址不正确/)
	assert.equal(view.state.loginEntryValid.value, false)
	assert.equal(view.state.canSubmit.value, false)
	assert.equal(view.calls.checkLoginReq.length, 0)
})

test('an installation status 404 remains a service error rather than an invalid login address', async (t) => {
	const view = createLogin({
		api: {
			installStatusReq: () =>
				Promise.reject({ response: { status: 404, data: {} } }),
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	assert.equal(view.state.entryAddressInvalid.value, false)
	assert.ok(view.state.bootstrapError.value)
	assert.doesNotMatch(view.state.bootstrapError.value, /登录地址不正确/)
	assert.equal(view.state.canSubmit.value, false)
	assert.equal(view.calls.validateLoginEntryReq.length, 0)
	assert.deepEqual(view.replacements, [])
})
