import {
	normalizeRegistrationEmail,
	registrationEmailError,
} from './registration.mjs'

export const buildPasswordResetPayload = (draft) => ({
	email: normalizeRegistrationEmail(draft.email),
	emailCode: String(draft.emailCode || '').trim(),
	password: draft.password,
	confirmPassword: draft.confirmPassword,
})

export function passwordResetErrors(draft) {
	const data = buildPasswordResetPayload(draft)
	const errors = {}
	const emailError = registrationEmailError(data.email)
	if (emailError) errors.email = emailError
	if (!/^\d{6}$/.test(data.emailCode))
		errors.emailCode = '请输入邮件中的 6 位验证码'
	if (
		typeof data.password !== 'string' ||
		!data.password.trim() ||
		data.password.length < 8 ||
		data.password.length > 128
	)
		errors.password = '密码需为 8 至 128 位'
	if (!data.confirmPassword) errors.confirmPassword = '请再次输入新密码'
	else if (data.password !== data.confirmPassword)
		errors.confirmPassword = '两次输入的密码不一致'
	return errors
}
