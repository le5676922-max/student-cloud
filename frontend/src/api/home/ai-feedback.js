import axios from '~/http/request'

// One explicitly selected question/answer pair only; never the full assistant history.
export const submitAiFeedbackReq = (data, config = {}) =>
	axios({
		url: '/api/ai/feedback',
		method: 'post',
		data,
		config: { timeout: 15000, showDefaultMsg: false, ...config },
	})
