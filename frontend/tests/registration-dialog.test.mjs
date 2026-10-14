import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import test from 'node:test'
import { createContext, Script } from 'node:vm'
import * as Vue from 'vue'
import * as registration from '../src/utils/registration.mjs'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

// Exercise the actual component setup with mocked requests; no mail is sent and
// no real account is created by these tests.
const require = createRequire(import.meta.url)
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
const source = await readFile(
	new URL('../src/components/auth/RegistrationDialog.vue', import.meta.url),
	'utf8'
)
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'registration-dialog-test' })
const executable = compiled.content
	.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '')
	.replace('export default', 'globalThis.RegistrationDialog =')

function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
const settle = async () => {
	await Vue.nextTick()
	await new Promise(setImmediate)
}
const snapshot = (value) => JSON.parse(JSON.stringify(value))

function createDialog(options = {}) {
	const mounted = [],
		unmounted = [],
		emissions = [],
		focuses = []
	const validations = [],
		codeRequests = [],
		registrations = [],
		configRequests = [],
		copies = []
	const intervals = new Map()
	let state,
		timerId = 0,
		now = 1000
	const sandbox = createContext({
		...Vue,
		...registration,
		createLatestRequest,
		AbortController,
		console,
		createRegistrationCodeCooldown: () =>
			registration.createRegistrationCodeCooldown(() => now),
		onMounted: (callback) => mounted.push(callback),
		onBeforeUnmount: (callback) => unmounted.push(callback),
		setInterval: (callback) => {
			intervals.set(++timerId, callback)
			return timerId
		},
		clearInterval: (id) => intervals.delete(id),
		useClipboard: () => ({ copy: async (id) => copies.push(id) }),
		ElMessage: { success() {}, warning() {} },
		registrationConfigReq: (config) => {
			configRequests.push(config)
			return (
				options.configRequest?.(config) ||
				Promise.resolve({ data: { enabled: true, mailAvailable: true } })
			)
		},
		registrationEmailCodeReq: (email, config) => {
			codeRequests.push({ email, config })
			return (
				options.codeRequest?.(email, config) || Promise.resolve({ data: null })
			)
		},
		registerReq: (payload, config) => {
			registrations.push({ payload: snapshot(payload), config })
			return (
				options.registerRequest?.(payload, config) ||
				Promise.resolve({
					data: {
						accountId: 'u_mock_123',
						email: payload.email,
						status: payload.joinType === 'NAME' ? 'PENDING' : 'ACTIVE',
						tenantName: payload.workspaceName || '示例空间',
						role: payload.registrationType,
					},
				})
			)
		},
	})
	for (const [name, metadata] of Object.entries(compiled.imports))
		if (!(name in sandbox))
			sandbox[name] =
				metadata.source === 'vue'
					? Vue[metadata.imported]
					: registration[metadata.imported]
	new Script(executable, {
		filename: 'registration-dialog.setup.js',
	}).runInContext(sandbox)
	const props = Vue.reactive({
		modelValue: true,
		initialIdentifier: '',
		...options.props,
	})
	const scope = Vue.effectScope()
	state = scope.run(() =>
		sandbox.RegistrationDialog.setup(props, {
			expose() {},
			emit: (...args) => emissions.push(args),
		})
	)
	state.formRef.value = {
		$el: {
			querySelector: (selector) => ({
				focus: () => focuses.push(selector),
				scrollIntoView() {},
			}),
		},
		clearValidate() {},
		validateField: (fields) => {
			const selected = Array.isArray(fields) ? [...fields] : [fields]
			validations.push(selected)
			if (options.validationRequest) return options.validationRequest(selected)
			const allErrors = registration.registrationErrors(state.draft)
			return selected.some((field) => allErrors[field])
				? Promise.reject(allErrors)
				: Promise.resolve(true)
		},
	}
	return {
		state,
		props,
		emissions,
		focuses,
		validations,
		codeRequests,
		registrations,
		configRequests,
		copies,
		intervals,
		async mount() {
			for (const callback of mounted) await callback()
			await settle()
		},
		unmount() {
			for (const callback of unmounted) callback()
			scope.stop()
		},
		advance(ms) {
			now += ms
			for (const callback of [...intervals.values()]) callback()
		},
	}
}

async function fillAccount(view) {
	view.state.draft.email = ' Member@Example.com '
	await settle()
	Object.assign(view.state.draft, {
		emailCode: '123456',
		password: '  pass word  ',
		confirmPassword: '  pass word  ',
		nickname: ' 小云 ',
	})
}

test('registration template compiles with accessible identity buttons and two steps', () => {
	const result = compileTemplate({
		source: descriptor.template.content,
		filename: 'RegistrationDialog.vue',
		id: 'registration-dialog-test',
	})
	assert.deepEqual(result.errors, [])
	assert.match(source, /role="group"\s+aria-label="注册身份"/)
	assert.match(source, /:aria-pressed="draft\.registrationType === 'USER'"/)
	assert.match(source, /v-show="step === 1"/)
	assert.match(source, /v-show="step === 2"/)
})

test('step one focuses its missing workspace input without validating account fields', async (t) => {
	const view = createDialog()
	t.after(() => view.unmount())
	await view.mount()
	await view.state.submit()
	assert.equal(view.state.step.value, 1)
	assert.equal(view.state.feedback.value, '请输入邀请码')
	assert.equal(view.focuses.at(-1), '#registration-join-value')
	assert.deepEqual(view.validations, [
		['registrationType', 'joinType', 'joinValue'],
	])
	assert.equal(view.registrations.length, 0)
	assert.equal(view.state.busy.value, false)
})

test('next and back preserve input and each join method has its own draft', async (t) => {
	const view = createDialog()
	t.after(() => view.unmount())
	await view.mount()
	view.state.draft.joinValue = 'invite-1'
	await view.state.submit()
	assert.equal(view.state.step.value, 2)
	assert.equal(view.focuses.at(-1), '#registration-email')
	assert.equal(view.registrations.length, 0)
	await fillAccount(view)
	await view.state.previousStep()
	assert.equal(view.state.draft.password, '  pass word  ')
	assert.equal(view.state.draft.joinValue, 'invite-1')
	view.state.selectJoinType('NAME')
	view.state.draft.joinValue = '学习小组'
	view.state.selectJoinType('INVITE')
	assert.equal(view.state.draft.joinValue, 'invite-1')
	view.state.selectJoinType('NAME')
	assert.equal(view.state.draft.joinValue, '学习小组')
	view.state.selectIdentity('TENANT_ADMIN')
	view.state.draft.workspaceName = '我的空间'
	view.state.selectIdentity('USER')
	assert.equal(view.state.draft.joinValue, '学习小组')
	view.state.selectIdentity('TENANT_ADMIN')
	assert.equal(view.state.draft.workspaceName, '我的空间')
})

test('step two reports and focuses the first account error', async (t) => {
	const view = createDialog()
	t.after(() => view.unmount())
	await view.mount()
	view.state.selectIdentity('TENANT_ADMIN')
	view.state.draft.workspaceName = '我的空间'
	await view.state.submit()
	await view.state.submit()
	assert.equal(view.state.step.value, 2)
	assert.equal(view.state.feedback.value, '请输入邮箱')
	assert.equal(view.focuses.at(-1), '#registration-email')
	assert.equal(view.registrations.length, 0)
})

test('a repeated next click cannot create concurrent validation or skip a step', async (t) => {
	const pending = deferred()
	const view = createDialog({ validationRequest: () => pending.promise })
	t.after(() => view.unmount())
	await view.mount()
	view.state.draft.joinValue = 'invite-1'
	const first = view.state.submit()
	const second = view.state.submit()
	assert.equal(view.validations.length, 1)
	assert.equal(view.state.busy.value, true)
	pending.resolve(true)
	await Promise.all([first, second])
	assert.equal(view.state.step.value, 2)
	assert.equal(view.registrations.length, 0)
})

test('resend delay resets for a different email and returns for the previous mailbox', async (t) => {
	const view = createDialog()
	t.after(() => view.unmount())
	await view.mount()
	view.state.draft.email = 'Member@Example.com'
	await settle()
	await view.state.sendCode()
	assert.equal(view.state.sentEmail.value, 'member@example.com')
	assert.equal(view.state.cooldown.value, 60)
	view.state.draft.emailCode = '123456'
	view.state.draft.email = 'other@example.com'
	await settle()
	assert.equal(view.state.cooldown.value, 0)
	assert.equal(view.state.codeSent.value, false)
	assert.equal(view.state.draft.emailCode, '')
	view.state.draft.email = 'member@example.com'
	await settle()
	assert.equal(view.state.cooldown.value, 60)
	view.advance(60000)
	assert.equal(view.state.cooldown.value, 0)
})

test('IP throttling applies when the email changes and failure names its mailbox', async (t) => {
	const view = createDialog({
		codeRequest: async () => {
			throw { data: { code: '41005', msg: '请求太频繁了，请稍后再试' } }
		},
	})
	t.after(() => view.unmount())
	await view.mount()
	view.state.draft.email = 'member@example.com'
	await settle()
	await view.state.sendCode()
	assert.match(view.state.feedback.value, /^member@example\.com：/)
	view.state.draft.email = 'other@example.com'
	await settle()
	assert.equal(view.state.cooldown.value, 60)
	await view.state.sendCode()
	assert.equal(view.codeRequests.length, 1)
})

test('changing email aborts an in-flight code request and ignores its late success', async (t) => {
	const pending = deferred()
	const view = createDialog({ codeRequest: () => pending.promise })
	t.after(() => view.unmount())
	await view.mount()
	view.state.draft.email = 'member@example.com'
	await settle()
	const sending = view.state.sendCode()
	await settle()
	view.state.draft.email = 'other@example.com'
	await settle()
	assert.equal(view.codeRequests[0].config.signal.aborted, true)
	pending.resolve({ data: null })
	await sending
	assert.equal(view.state.codeSent.value, false)
	assert.equal(view.state.sentEmail.value, '')
	assert.equal(view.state.busy.value, false)
})

test('duplicate sends remain blocked while email validation is pending', async (t) => {
	const validation = deferred()
	const view = createDialog({ validationRequest: () => validation.promise })
	t.after(() => view.unmount())
	await view.mount()
	view.state.draft.email = 'member@example.com'
	await settle()
	const first = view.state.sendCode()
	const second = view.state.sendCode()
	assert.equal(view.validations.length, 1)
	validation.resolve(true)
	await Promise.all([first, second])
	assert.equal(view.codeRequests.length, 1)
})

test('successful registration preserves ID and pending membership without logging in', async (t) => {
	const view = createDialog()
	t.after(() => view.unmount())
	await view.mount()
	view.state.selectJoinType('NAME')
	view.state.draft.joinValue = '学习小组'
	await view.state.submit()
	await fillAccount(view)
	await settle()
	await view.state.submit()
	assert.equal(view.registrations.length, 1)
	assert.equal(view.registrations[0].payload.password, '  pass word  ')
	assert.equal(view.state.result.value.accountId, 'u_mock_123')
	assert.equal(view.state.result.value.status, 'PENDING')
	assert.equal(view.state.draft.password, '')
	assert.deepEqual(view.emissions, [])
	await view.state.copyAccountId()
	assert.deepEqual(view.copies, ['u_mock_123'])
	view.state.returnToLogin()
	assert.deepEqual(snapshot(view.emissions), [
		['return-to-login', 'member@example.com'],
		['update:modelValue', false],
	])
})

test('unmount aborts a registration and prevents a late result', async () => {
	const pending = deferred()
	const view = createDialog({ registerRequest: () => pending.promise })
	await view.mount()
	view.state.draft.joinValue = 'invite-1'
	await view.state.submit()
	await fillAccount(view)
	await settle()
	const submitting = view.state.submit()
	await settle()
	assert.equal(view.registrations.length, 1)
	view.unmount()
	assert.equal(view.registrations[0].config.signal.aborted, true)
	pending.resolve({ data: { accountId: 'late' } })
	await submitting
	assert.equal(view.state.result.value, null)
	assert.equal(view.intervals.size, 0)
})

test('a superseded config reply cannot overwrite the latest service status', async (t) => {
	const pending = [deferred(), deferred()]
	let call = 0
	const view = createDialog({ configRequest: () => pending[call++].promise })
	t.after(() => view.unmount())
	const first = view.state.loadConfig()
	const second = view.state.loadConfig()
	assert.equal(view.configRequests[0].signal.aborted, true)
	pending[1].resolve({ data: { enabled: true, mailAvailable: true } })
	await second
	pending[0].resolve({ data: { enabled: false, mailAvailable: false } })
	await first
	assert.equal(view.state.canRegister.value, true)
})
