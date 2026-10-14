export const normalizeRegistrationEmail = (email) =>
	String(email || '')
		.trim()
		.toLowerCase()

export const registrationEmailError = (email) => {
	const normalized = normalizeRegistrationEmail(email)
	if (!normalized) return '请输入邮箱'
	if (normalized.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized))
		return '请输入有效的邮箱地址'
	return ''
}

export const buildRegistrationPayload = (draft) => ({
	email: normalizeRegistrationEmail(draft.email),
	emailCode: String(draft.emailCode || '').trim(),
	password: draft.password,
	confirmPassword: draft.confirmPassword,
	nickname: String(draft.nickname || '').trim(),
	registrationType: draft.registrationType,
	...(draft.registrationType === 'TENANT_ADMIN'
		? { workspaceName: String(draft.workspaceName || '').trim() }
		: {
				joinType: draft.joinType,
				joinValue: String(draft.joinValue || '').trim(),
		  }),
})

export const registrationErrors = (draft) => {
	const data = buildRegistrationPayload(draft)
	const errors = {}
	const emailError = registrationEmailError(data.email)
	if (emailError) errors.email = emailError
	if (!/^\d{6}$/.test(data.emailCode))
		errors.emailCode = '请输入邮件中的 6 位验证码'
	if (
		typeof data.password !== 'string' ||
		!data.password.trim() ||
		data.password.length < 8
	)
		errors.password = '密码至少需要 8 位'
	else if (data.password.length > 128) errors.password = '密码不能超过 128 位'
	if (!data.confirmPassword) errors.confirmPassword = '请再次输入密码'
	else if (data.confirmPassword !== data.password)
		errors.confirmPassword = '两次输入的密码不一致'
	if (data.nickname.length > 100) errors.nickname = '昵称不能超过 100 字'
	if (data.registrationType === 'TENANT_ADMIN') {
		if (!data.workspaceName) errors.workspaceName = '请输入新工作空间名称'
		else if (data.workspaceName.length > 80)
			errors.workspaceName = '工作空间名称不能超过 80 字'
	} else if (data.registrationType === 'USER') {
		if (!['INVITE', 'NAME'].includes(data.joinType))
			errors.joinType = '请选择加入方式'
		if (!data.joinValue)
			errors.joinValue =
				data.joinType === 'NAME' ? '请输入工作空间名称' : '请输入邀请码'
		else if (data.joinValue.length > 80) errors.joinValue = '输入内容过长'
	} else errors.registrationType = '请选择注册身份'
	return errors
}

export const registrationFieldsForStep = (draft, step) =>
	step === 1
		? draft.registrationType === 'TENANT_ADMIN'
			? ['registrationType', 'workspaceName']
			: ['registrationType', 'joinType', 'joinValue']
		: ['email', 'emailCode', 'password', 'confirmPassword', 'nickname']

export const registrationStepErrors = (draft, step) => {
	const errors = registrationErrors(draft)
	return Object.fromEntries(
		registrationFieldsForStep(draft, step)
			.filter((field) => errors[field])
			.map((field) => [field, errors[field]])
	)
}

// Changing the address should not inherit another mailbox's resend delay, but
// returning to that mailbox must preserve it. IP-level throttles apply to all.
export function createRegistrationCodeCooldown(now = Date.now) {
	const deadlines = new Map()
	let blockedUntil = 0
	return {
		remaining(email) {
			return Math.max(
				0,
				Math.ceil(
					(Math.max(
						deadlines.get(normalizeRegistrationEmail(email)) || 0,
						blockedUntil
					) -
						now()) /
						1000
				)
			)
		},
		sent(email, seconds = 60) {
			deadlines.set(normalizeRegistrationEmail(email), now() + seconds * 1000)
		},
		throttle(seconds = 60) {
			blockedUntil = Math.max(blockedUntil, now() + seconds * 1000)
		},
	}
}

export function registrationCodeThrottle(failure) {
	const response = failure?.response || failure
	const code = String(response?.data?.code || '')
	const message = response?.data?.msg || ''
	const retryAfter = Number(response?.headers?.['retry-after'])
	if (response?.status === 429 || code === '41005')
		return {
			global: true,
			seconds: retryAfter > 0 ? Math.ceil(retryAfter) : 60,
		}
	if (/邮件服务繁忙，请60秒/.test(message)) return { global: true, seconds: 60 }
	if (/验证码发送过于频繁|邮件发送失败，请60秒/.test(message))
		return { global: false, seconds: 60 }
	return null
}
