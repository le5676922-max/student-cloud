import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import test from 'node:test'
import { createContext, Script } from 'node:vm'
import * as Vue from 'vue'
import * as registration from '../src/utils/registration.mjs'
import * as reset from '../src/utils/password-reset.mjs'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

const require = createRequire(import.meta.url)
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
const source = await readFile(
	new URL('../src/components/auth/PasswordResetDialog.vue', import.meta.url),
	'utf8'
)
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'password-reset-test' })
const executable = compiled.content
	.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '')
	.replace('export default', 'globalThis.PasswordResetDialog =')
const settle = async () => {
	await Vue.nextTick()
	await new Promise(setImmediate)
}
function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
function createDialog(options = {}) {
	const mounted = [],
		unmounted = [],
		emissions = [],
		codes = [],
		resets = [],
		focuses = []
	const intervals = new Map()
	let timer = 0,
		now = 1000
	const sandbox = createContext({
		...Vue,
		...registration,
		...reset,
		createLatestRequest,
		AbortController,
		console,
		onMounted: (fn) => mounted.push(fn),
		onBeforeUnmount: (fn) => unmounted.push(fn),
		createRegistrationCodeCooldown: () =>
			registration.createRegistrationCodeCooldown(() => now),
		setInterval: (fn) => {
			intervals.set(++timer, fn)
			return timer
		},
		clearInterval: (id) => intervals.delete(id),
		passwordResetConfigReq: (config) =>
			options.configRequest?.(config) ||
			Promise.resolve({ data: { enabled: true, mailAvailable: true } }),
		passwordResetEmailCodeReq: (email, config) => {
			codes.push({ email, config })
			return (
				options.codeRequest?.(email, config) ||
				Promise.resolve({ msg: 'generic sent' })
			)
		},
		passwordResetReq: (payload, config) => {
			resets.push({ payload: JSON.parse(JSON.stringify(payload)), config })
			return (
				options.resetRequest?.(payload, config) ||
				Promise.resolve({ msg: 'generic reset completed' })
			)
		},
	})
	new Script(executable).runInContext(sandbox)
	const props = Vue.reactive({
		modelValue: true,
		initialIdentifier: '',
		...options.props,
	})
	const scope = Vue.effectScope()
	const state = scope.run(() =>
		sandbox.PasswordResetDialog.setup(props, {
			expose() {},
			emit: (...args) => emissions.push(args),
		})
	)
	state.formRef.value = {
		clearValidate() {},
		$el: {
			querySelector: (selector) => ({ focus: () => focuses.push(selector) }),
		},
		validateField(fields) {
			if (options.validationRequest) return options.validationRequest(fields)
			const errors = reset.passwordResetErrors(state.draft)
			return (Array.isArray(fields) ? fields : [fields]).some(
				(field) => errors[field]
			)
				? Promise.reject(errors)
				: Promise.resolve()
		},
	}
	return {
		state,
		props,
		emissions,
		codes,
		resets,
		focuses,
		async mount() {
			for (const fn of mounted) await fn()
			await settle()
		},
		unmount() {
			for (const fn of unmounted) fn()
			scope.stop()
		},
		advance(ms) {
			now += ms
			for (const fn of intervals.values()) fn()
		},
	}
}
async function validDraft(view) {
	view.state.draft.email = ' Member@Example.com '
	await settle()
	Object.assign(view.state.draft, {
		emailCode: '123456',
		password: '  pass word  ',
		confirmPassword: '  pass word  ',
	})
}

test('password recovery template compiles and login uses the real recovery dialog', async () => {
	assert.deepEqual(
		compileTemplate({
			source: descriptor.template.content,
			filename: 'PasswordResetDialog.vue',
			id: 'password-reset-test',
		}).errors,
		[]
	)
	const login = await readFile(
		new URL('../src/pages/login.vue', import.meta.url),
		'utf8'
	)
	assert.match(login, /<PasswordResetDialog\b/)
	assert.doesNotMatch(login, /暂不支持自助找回密码/)
	assert.match(source, /autocomplete="one-time-code"/)
	assert.match(source, /autocomplete="new-password"/)
})

test('invalid address and passwords stop requests without silently trimming a real password', async () => {
	const view = createDialog()
	await view.mount()
	await view.state.sendCode()
	assert.equal(view.codes.length, 0)
	await validDraft(view)
	view.state.draft.confirmPassword = 'different'
	await view.state.submit()
	assert.equal(view.resets.length, 0)
	assert.match(view.state.feedback.value, /不一致/)
	view.state.draft.password = ' '.repeat(8)
	view.state.draft.confirmPassword = ' '.repeat(8)
	await view.state.submit()
	assert.equal(view.resets.length, 0)
	view.unmount()
})

test('mail sending normalizes address and retains per-mailbox cooldown across editing', async () => {
	const view = createDialog()
	await view.mount()
	await validDraft(view)
	await view.state.sendCode()
	assert.equal(view.codes[0].email, 'member@example.com')
	assert.equal(view.state.cooldown.value, 60)
	await view.state.sendCode()
	assert.equal(view.codes.length, 1)
	view.state.draft.email = 'other@example.com'
	await settle()
	assert.equal(view.state.cooldown.value, 0)
	assert.equal(view.state.draft.emailCode, '')
	view.state.draft.email = 'MEMBER@example.com'
	await settle()
	assert.equal(view.state.cooldown.value, 60)
	view.advance(60_001)
	assert.equal(view.state.cooldown.value, 0)
	view.unmount()
})

test('successful reset clears secrets and returns the email without storing or logging in', async () => {
	const view = createDialog()
	await view.mount()
	await validDraft(view)
	await view.state.submit()
	assert.equal(view.resets.length, 1)
	assert.equal(view.resets[0].payload.password, '  pass word  ')
	assert.equal(view.resets[0].payload.email, 'member@example.com')
	assert.equal(view.state.completed.value, 'generic reset completed')
	assert.equal(view.state.draft.password, '')
	assert.equal(view.state.draft.emailCode, '')
	view.state.returnToLogin()
	assert.deepEqual(view.emissions, [
		['return-to-login', 'member@example.com'],
		['update:modelValue', false],
	])
	view.unmount()
})

test('in-flight duplicate reset is blocked and unmount aborts and ignores a late result', async () => {
	const pending = deferred()
	const view = createDialog({ resetRequest: () => pending.promise })
	await view.mount()
	await validDraft(view)
	const submission = view.state.submit()
	await settle()
	await view.state.submit()
	assert.equal(view.resets.length, 1)
	view.unmount()
	assert.equal(view.resets[0].config.signal.aborted, true)
	assert.equal(view.state.draft.password, '')
	pending.resolve({ msg: 'late result' })
	await submission
	assert.equal(view.state.completed.value, '')
})

test('late email response for an edited address is aborted and cannot start another mailbox cooldown', async () => {
	const pending = deferred()
	const view = createDialog({ codeRequest: () => pending.promise })
	await view.mount()
	await validDraft(view)
	const sending = view.state.sendCode()
	await settle()
	view.state.draft.email = 'new@example.com'
	await settle()
	assert.equal(view.codes[0].config.signal.aborted, true)
	pending.resolve({ msg: 'late sent' })
	await sending
	assert.equal(view.state.codeSent.value, false)
	assert.equal(view.state.cooldown.value, 0)
	view.unmount()
})

test('unavailable mail and backend global throttles prevent further requests', async () => {
	const unavailable = createDialog({
		configRequest: () =>
			Promise.resolve({ data: { enabled: true, mailAvailable: false } }),
	})
	await unavailable.mount()
	await validDraft(unavailable)
	await unavailable.state.sendCode()
	await unavailable.state.submit()
	assert.equal(unavailable.codes.length, 0)
	assert.equal(unavailable.resets.length, 0)
	unavailable.unmount()
	const view = createDialog({
		codeRequest: () =>
			Promise.reject({
				response: {
					status: 429,
					headers: { 'retry-after': '90' },
					data: { msg: 'too frequent' },
				},
			}),
	})
	await view.mount()
	await validDraft(view)
	await view.state.sendCode()
	assert.equal(view.state.cooldown.value, 90)
	view.state.draft.email = 'other@example.com'
	await settle()
	assert.equal(view.state.cooldown.value, 90)
	view.unmount()
})
