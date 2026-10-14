import assert from 'node:assert/strict'
import test from 'node:test'
import {
	buildRegistrationPayload,
	createRegistrationCodeCooldown,
	registrationCodeThrottle,
	registrationEmailError,
	registrationErrors,
	registrationStepErrors,
} from '../src/utils/registration.mjs'

const valid = {
	email: ' Member@Example.com ',
	emailCode: '123456',
	password: '  pass word  ',
	confirmPassword: '  pass word  ',
	nickname: ' 小云 ',
	registrationType: 'USER',
	joinType: 'INVITE',
	joinValue: ' one-time-invite ',
	workspaceName: 'Should not be sent',
}

test('normalizes email while preserving password spaces and excluding other role fields', () => {
	const payload = buildRegistrationPayload(valid)
	assert.equal(payload.email, 'member@example.com')
	assert.equal(payload.password, valid.password)
	assert.equal(payload.confirmPassword, valid.confirmPassword)
	assert.equal(payload.nickname, '小云')
	assert.equal(payload.joinValue, 'one-time-invite')
	assert.equal('workspaceName' in payload, false)
	assert.deepEqual(registrationErrors(valid), {})
})

test('new workspace administrators cannot submit a member join value', () => {
	const draft = {
		...valid,
		registrationType: 'TENANT_ADMIN',
		workspaceName: ' 新空间 ',
	}
	const payload = buildRegistrationPayload(draft)
	assert.equal(payload.workspaceName, '新空间')
	assert.equal('joinType' in payload, false)
	assert.equal('joinValue' in payload, false)
	assert.deepEqual(registrationErrors(draft), {})
})

test('name join requires a space name and mismatched passwords are rejected', () => {
	const errors = registrationErrors({
		...valid,
		joinType: 'NAME',
		joinValue: ' ',
		confirmPassword: 'different',
	})
	assert.equal(errors.joinValue, '请输入工作空间名称')
	assert.equal(errors.confirmPassword, '两次输入的密码不一致')
})

test('registration checks email, verification code, and input limits', () => {
	assert.ok(registrationEmailError('account-id'))
	assert.ok(registrationEmailError('a@b'))
	assert.ok(registrationEmailError('a'.repeat(250) + '@example.com'))
	assert.equal(registrationEmailError('Person@example.com'), '')
	const errors = registrationErrors({
		...valid,
		emailCode: '12',
		password: '1234567',
		nickname: 'a'.repeat(101),
	})
	assert.ok(errors.emailCode)
	assert.ok(errors.password)
	assert.ok(errors.nickname)
})

test('workspace choice can validate before any account details are entered', () => {
	const draft = {
		...valid,
		email: '',
		emailCode: '',
		password: '',
		confirmPassword: '',
	}
	assert.deepEqual(registrationStepErrors(draft, 1), {})
	assert.ok(registrationStepErrors(draft, 2).email)
	assert.deepEqual(
		registrationStepErrors(
			{ ...draft, registrationType: 'TENANT_ADMIN', workspaceName: '' },
			1
		),
		{
			workspaceName: '请输入新工作空间名称',
		}
	)
})

test('resend cooldown follows its email while global throttles follow every email', () => {
	let now = 1000
	const cooldown = createRegistrationCodeCooldown(() => now)
	cooldown.sent(' A@Example.com ')
	assert.equal(cooldown.remaining('a@example.com'), 60)
	assert.equal(cooldown.remaining('b@example.com'), 0)
	now += 10000
	assert.equal(cooldown.remaining('a@example.com'), 50)
	cooldown.throttle(90)
	assert.equal(cooldown.remaining('a@example.com'), 90)
	assert.equal(cooldown.remaining('b@example.com'), 90)
	now += 90000
	assert.equal(cooldown.remaining('a@example.com'), 0)
})

test('code throttles distinguish the per-mailbox delay from IP-level limits', () => {
	assert.deepEqual(
		registrationCodeThrottle({
			data: { msg: '验证码发送过于频繁，请60秒后重试' },
		}),
		{ global: false, seconds: 60 }
	)
	assert.deepEqual(registrationCodeThrottle({ data: { code: '41005' } }), {
		global: true,
		seconds: 60,
	})
	assert.deepEqual(
		registrationCodeThrottle({
			response: { status: 429, headers: { 'retry-after': '120' } },
		}),
		{ global: true, seconds: 120 }
	)
	assert.equal(
		registrationCodeThrottle({ data: { msg: '邮件发送失败' } }),
		null
	)
})
