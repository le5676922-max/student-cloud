import axios from '~/http/request'

// 登录
export const loginReq = (data, entry, config) => {
	const url = entry ? `/user/login/${entry}` : '/user/login'
	return axios({
		url,
		method: 'post',
		data,
		config,
	})
}

export const registrationConfigReq = (config = {}) =>
	axios({ url: '/user/registration/config', method: 'get', config })

export const registrationEmailCodeReq = (email, config = {}) =>
	axios({
		url: '/user/registration/email-code',
		method: 'post',
		data: { email, purpose: 'REGISTER' },
		config,
	})

export const registerReq = (data, config = {}) =>
	axios({ url: '/user/register', method: 'post', data, config })

export const passwordResetConfigReq = (config = {}) =>
	axios({ url: '/user/password-reset/config', method: 'get', config })

export const passwordResetEmailCodeReq = (email, config = {}) =>
	axios({
		url: '/user/password-reset/email-code',
		method: 'post',
		data: { email },
		config,
	})

export const passwordResetReq = (data, config = {}) =>
	axios({ url: '/user/password-reset', method: 'post', data, config })

// 校验安全登录入口
export const validateLoginEntryReq = (entry, config) => {
	const params = entry ? { entry } : {}
	return axios({
		url: '/user/login/entry/validate',
		method: 'get',
		data: params,
		config,
	})
}

// 获取登录验证方式
export const loginVerifyModeReq = (username, config) => {
	return axios({
		url: '/user/login/verify-mode',
		method: 'get',
		data: {
			username,
		},
		config,
	})
}

// 获取登录图片验证码
export const loginVerifyImgReq = (config) => {
	return axios({
		url: '/user/login/captcha',
		method: 'get',
		config,
	})
}

// 注销
export const logoutReq = () => {
	return axios({
		url: '/user/logout',
		method: 'post',
	})
}

// 检查是否已登录
export const checkLoginReq = (config) => {
	return axios({
		url: '/user/login/check',
		method: 'get',
		config,
	})
}

// 修改密码
export const updatePwdReq = (data) => {
	return axios({
		url: '/user/updatePwd',
		method: 'post',
		data,
	})
}

// 重置管理员密码
export const resetAdminPwdReq = (data) => {
	return axios({
		url: '/user/resetAdminPassword',
		method: 'put',
		data,
	})
}
