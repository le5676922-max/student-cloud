import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import test from 'node:test'
import { createContext, Script } from 'node:vm'
import * as Vue from 'vue'

// Compile the actual SFC setup logic, then replace only its external dependencies.
// No test imports the real request module or contacts an installation endpoint.
const require = createRequire(import.meta.url)
const { parse, compileScript } = require('vue/compiler-sfc')
const page = await readFile(
	new URL('../src/pages/install/index.vue', import.meta.url),
	'utf8'
)
const { descriptor, errors } = parse(page)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'install-page-test' })
const executable = compiled.content
	.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '')
	.replace('export default', 'globalThis.InstallPage =')

const deferred = () => {
	let resolve
	let reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}

const settle = () => new Promise((resolve) => setImmediate(resolve))

function createPage(options = {}) {
	const mounted = []
	const unmounted = []
	const routes = []
	const requests = []
	const notices = []
	const confirmations = []
	const storageWrites = []
	let statusCalls = 0
	let validations = 0
	const message = (value) => notices.push(value)
	for (const type of ['success', 'warning', 'error', 'info']) {
		message[type] = (value) => notices.push({ type, message: value })
	}
	const sandbox = createContext({
		...Vue,
		console,
		setTimeout,
		clearTimeout,
		window: {
			matchMedia: () => ({
				matches: Boolean(options.reducedMotion),
				addEventListener() {},
				removeEventListener() {},
			}),
			localStorage: {
				getItem: () => null,
				setItem: (...args) => storageWrites.push(args),
				removeItem: (...args) => storageWrites.push(args),
			},
		},
		useRouter: () => ({
			push: (path) => routes.push({ method: 'push', path }),
			replace: (path) => routes.push({ method: 'replace', path }),
		}),
		useTitle() {},
		onMounted: (callback) => mounted.push(callback),
		onBeforeMount: (callback) => mounted.push(callback),
		onBeforeUnmount: (callback) => unmounted.push(callback),
		onUnmounted: (callback) => unmounted.push(callback),
		installStatusReq: () => {
			statusCalls++
			return options.statusRequest
				? options.statusRequest(statusCalls)
				: Promise.resolve({ data: false })
		},
		installReq: (payload) => {
			requests.push(JSON.parse(JSON.stringify(payload)))
			return options.installRequest
				? options.installRequest(payload)
				: Promise.resolve({ code: '0', data: null })
		},
		ElMessage: message,
		MessageBox: {
			confirm: (...args) => {
				confirmations.push(args)
				return options.confirmRequest
					? options.confirmRequest(...args)
					: Promise.resolve()
			},
		},
	})
	for (const [name, metadata] of Object.entries(compiled.imports)) {
		if (!(name in sandbox)) {
			sandbox[name] = metadata.source === 'vue' ? Vue[metadata.imported] : {}
		}
	}
	new Script(executable, { filename: 'install-page.setup.js' }).runInContext(
		sandbox
	)
	const state = sandbox.InstallPage.setup({}, { expose() {} })
	state.installFormRef.value = {
		validate: (...args) => {
			validations++
			if (options.validate) return options.validate(...args)
			return Promise.resolve(true)
		},
		clearValidate() {},
	}
	return {
		state,
		routes,
		requests,
		notices,
		confirmations,
		storageWrites,
		get statusCalls() {
			return statusCalls
		},
		get validations() {
			return validations
		},
		async mount() {
			await Promise.all(mounted.map((callback) => callback()))
			await settle()
		},
		unmount() {
			for (const callback of unmounted) callback()
		},
	}
}

test('install shares the direct-entry login layout, stylesheet and interactive characters', () => {
	assert.match(page, /class="career-login-page career-install-page"/)
	for (const className of [
		'career-login-card',
		'career-login-art',
		'career-login-content',
		'career-login-form',
	]) {
		assert.match(
			descriptor.template.content,
			new RegExp(`class="${className}"`)
		)
	}
	assert.match(page, /import ['"]~\/styles\/login\.css['"]/)
	assert.match(page, /<LoginCharacters\b/)
	assert.doesNotMatch(page, /<LoginCharacters\b[^>]*\bvariant=/)
	assert.match(page, /:is-typing="isTyping"/)
	assert.match(page, /:show-password="passwordVisible"/)
	assert.match(page, /:password-length="formData\.password\.length"/)
	assert.match(page, /:paused="motionPaused"/)
	assert.doesNotMatch(
		page,
		/cloud-auth-page|LoginIntro|重播开场|data-login-intro/
	)
})

test('password, field labels, focus interaction and retry controls remain wired accessibly', () => {
	assert.match(page, /v-model="formData\.password"/)
	assert.doesNotMatch(page, /v-model\.trim="formData\.password"/)
	for (const id of ['site', 'account', 'password']) {
		assert.match(page, new RegExp(`for="career-install-${id}"`))
		assert.match(page, new RegExp(`id="career-install-${id}"`))
	}
	assert.match(page, /:type="passwordVisible \? 'text' : 'password'"/)
	assert.match(page, /:aria-pressed="passwordVisible"/)
	assert.match(page, /@focus="isTyping = true"/)
	assert.match(page, /@blur="isTyping = false"/)
	assert.match(page, /@submit\.prevent="submitForm"/)
	assert.match(page, /:disabled="formDisabled"/)
	assert.match(page, /role="alert"/)
	assert.match(page, /@click="checkInstallStatus"/)
})

test('unknown or pending initialization status blocks validation and installation', async () => {
	const pending = deferred()
	const view = createPage({ statusRequest: () => pending.promise })
	assert.equal(view.state.formDisabled.value, true)
	await view.state.submitForm()
	assert.equal(view.validations, 0)
	assert.equal(view.requests.length, 0)
	const mounting = view.mount()
	assert.equal(view.statusCalls, 1)
	assert.equal(view.state.checkingInstall.value, true)
	await view.state.submitForm()
	assert.equal(view.validations, 0)
	pending.resolve({ data: false })
	await mounting
	assert.equal(view.state.installStatusChecked.value, true)
	assert.equal(view.state.formDisabled.value, false)
	assert.deepEqual(view.routes, [])
})

test('an already installed site redirects to login and cannot submit installation again', async () => {
	const view = createPage({ statusRequest: async () => ({ data: true }) })
	await view.mount()
	assert.equal(view.state.installed.value, true)
	assert.equal(view.state.formDisabled.value, true)
	assert.deepEqual(view.routes, [{ method: 'replace', path: '/login' }])
	await view.state.submitForm()
	assert.equal(view.validations, 0)
	assert.equal(view.requests.length, 0)
	assert.equal(view.state.submitLabel.value, '初始化完成')
})

test('malformed status envelopes fail closed rather than treating truthy values as installed', async () => {
	for (const response of [
		undefined,
		null,
		{},
		{ data: null },
		{ data: 'false' },
		{ data: 'true' },
		{ data: 0 },
		{ data: 1 },
		{ data: {} },
	]) {
		const view = createPage({ statusRequest: async () => response })
		await view.mount()
		assert.equal(view.state.installStatusChecked.value, false)
		assert.equal(view.state.checkingInstall.value, false)
		assert.equal(view.state.formDisabled.value, true)
		assert.ok(view.state.statusError.value)
		assert.equal(view.state.feedback.value, view.state.statusError.value)
		await view.state.submitForm()
		assert.equal(view.requests.length, 0)
		assert.deepEqual(view.routes, [])
	}
})

test('status network failures show retry feedback and a successful retry unlocks the form', async () => {
	const view = createPage({
		statusRequest: async (call) => {
			if (call === 1) throw new Error('offline')
			return { data: false }
		},
	})
	await view.mount()
	assert.equal(view.state.formDisabled.value, true)
	assert.match(view.state.statusError.value, /检查后端连接后重试/)
	await view.state.submitForm()
	assert.equal(view.requests.length, 0)
	await view.state.checkInstallStatus()
	assert.equal(view.statusCalls, 2)
	assert.equal(view.state.statusError.value, '')
	assert.equal(view.state.installStatusChecked.value, true)
	assert.equal(view.state.formDisabled.value, false)
})

test('a stale status response cannot override a newer check or redirect an uninstalled site', async () => {
	const first = deferred()
	const second = deferred()
	const view = createPage({
		statusRequest: (call) => (call === 1 ? first.promise : second.promise),
	})
	const mounting = view.mount()
	const rechecking = view.state.checkInstallStatus()
	second.resolve({ data: false })
	await rechecking
	first.resolve({ data: true })
	await mounting
	assert.equal(view.state.installed.value, false)
	assert.equal(view.state.installStatusChecked.value, true)
	assert.equal(view.state.formDisabled.value, false)
	assert.deepEqual(view.routes, [])
})

test('form validation rejection prevents installation and releases the loading lock', async () => {
	const view = createPage({
		validate: () => Promise.reject({ password: 'invalid' }),
	})
	await view.mount()
	await view.state.submitForm()
	assert.equal(view.validations, 1)
	assert.equal(view.requests.length, 0)
	assert.equal(view.state.loading.value, false)
	assert.equal(view.state.formDisabled.value, false)
	assert.match(view.state.formError.value, /请检查并填写初始化信息/)
})

test('repeated clicks and Enter during validation or installation produce only one request', async () => {
	const validation = deferred()
	const request = deferred()
	const view = createPage({
		validate: () => validation.promise,
		installRequest: () => request.promise,
	})
	await view.mount()
	const submitting = view.state.submitForm()
	assert.equal(view.state.loading.value, true)
	assert.equal(view.state.formDisabled.value, true)
	assert.equal(view.state.submitLabel.value, '正在初始化…')
	await view.state.submitForm()
	assert.equal(view.validations, 1)
	assert.equal(view.requests.length, 0)
	validation.resolve(true)
	await settle()
	await view.state.submitForm()
	assert.equal(view.validations, 1)
	assert.equal(view.requests.length, 1)
	request.resolve({ code: '0' })
	await submitting
	assert.equal(view.requests.length, 1)
})

test('installation sends only existing API fields and preserves password spaces', async () => {
	const view = createPage()
	await view.mount()
	Object.assign(view.state.formData, {
		siteName: '  我的云盘  ',
		username: '  administrator  ',
		password: '  pass word  ',
	})
	await view.state.submitForm()
	assert.deepEqual(view.requests, [
		{
			siteName: '我的云盘',
			username: 'administrator',
			password: '  pass word  ',
		},
	])
	assert.deepEqual(view.storageWrites, [])
	assert.doesNotMatch(page, /localStorage|sessionStorage/)
})

test('successful installation clears password, waits for confirmation and stays locked', async () => {
	const confirmation = deferred()
	const view = createPage({ confirmRequest: () => confirmation.promise })
	await view.mount()
	view.state.formData.password = 'test-password'
	view.state.passwordVisible.value = true
	view.state.capsLock.value = true
	const submitting = view.state.submitForm()
	await settle()
	assert.equal(view.state.installSucceeded.value, true)
	assert.equal(view.state.installed.value, true)
	assert.equal(view.state.formData.password, '')
	assert.equal(view.state.passwordVisible.value, false)
	assert.equal(view.state.capsLock.value, false)
	assert.equal(view.state.formDisabled.value, true)
	assert.equal(view.confirmations.length, 1)
	assert.deepEqual(view.routes, [])
	await view.state.submitForm()
	await view.state.checkInstallStatus()
	assert.equal(view.requests.length, 1)
	assert.equal(view.statusCalls, 1)
	confirmation.resolve()
	await submitting
	assert.deepEqual(view.routes, [{ method: 'replace', path: '/login' }])
	assert.equal(view.state.loading.value, false)
	assert.equal(view.state.formDisabled.value, true)
	assert.equal(view.state.submitLabel.value, '初始化完成')
})

test('installation errors release the form, keep the password and allow retry', async () => {
	let calls = 0
	const view = createPage({
		installRequest: async () => {
			if (++calls === 1) throw { response: { data: { msg: '站点配置未保存' } } }
			return { code: '0' }
		},
	})
	await view.mount()
	view.state.formData.password = 'retry-password'
	await view.state.submitForm()
	assert.equal(view.state.loading.value, false)
	assert.equal(view.state.formDisabled.value, false)
	assert.equal(view.state.formError.value, '站点配置未保存')
	assert.equal(view.state.feedback.value, '站点配置未保存')
	assert.equal(view.state.formData.password, 'retry-password')
	assert.equal(view.state.installSucceeded.value, false)
	assert.deepEqual(view.routes, [])
	await view.state.submitForm()
	assert.equal(view.requests.length, 2)
	assert.equal(view.state.formError.value, '')
	assert.equal(view.state.installSucceeded.value, true)
})

test('a generic request failure shows concise fallback text instead of an exception stack', async () => {
	const view = createPage({
		installRequest: () =>
			Promise.reject(new Error('ECONNREFUSED internal details')),
	})
	await view.mount()
	await view.state.submitForm()
	assert.equal(view.state.formError.value, '初始化失败，请检查后端连接后重试。')
	assert.doesNotMatch(view.state.formError.value, /ECONNREFUSED|stack|Error:/)
	assert.equal(view.state.loading.value, false)
})

test('reduced-motion preference pauses characters and Caps Lock handling is defensive', () => {
	for (const reducedMotion of [false, true]) {
		const view = createPage({ reducedMotion })
		assert.equal(view.state.motionPaused.value, reducedMotion)
		view.state.updateCapsLock({ getModifierState: () => true })
		assert.equal(view.state.capsLock.value, true)
		view.state.updateCapsLock({})
		assert.equal(view.state.capsLock.value, false)
	}
})

test('unmounting ignores late status responses and prevents navigation or future requests', async () => {
	const response = deferred()
	const view = createPage({ statusRequest: () => response.promise })
	const mounting = view.mount()
	view.unmount()
	response.resolve({ data: true })
	await mounting
	assert.equal(view.state.installed.value, false)
	assert.equal(view.state.installStatusChecked.value, false)
	assert.deepEqual(view.routes, [])
	await view.state.checkInstallStatus()
	await view.state.submitForm()
	assert.equal(view.statusCalls, 1)
	assert.equal(view.requests.length, 0)
})

test('unmounting during async validation prevents the installation request entirely', async () => {
	const validation = deferred()
	const view = createPage({ validate: () => validation.promise })
	await view.mount()
	const submitting = view.state.submitForm()
	view.unmount()
	validation.resolve(true)
	await submitting
	assert.equal(view.requests.length, 0)
	assert.equal(view.confirmations.length, 0)
	assert.deepEqual(view.routes, [])
})

test('unmounting during installation ignores its late success without a dialog or redirect', async () => {
	const response = deferred()
	const view = createPage({ installRequest: () => response.promise })
	await view.mount()
	const submitting = view.state.submitForm()
	await settle()
	assert.equal(view.requests.length, 1)
	view.unmount()
	response.resolve({ code: '0' })
	await submitting
	assert.equal(view.state.installSucceeded.value, false)
	assert.equal(view.confirmations.length, 0)
	assert.deepEqual(view.routes, [])
})
