import axios from '~/http/request'

// Keep the normal session credential in the existing HTTP interceptor, never a URL.
export const supportRequest = (method, path = '', data, config = {}) =>
	axios({
		method,
		url: `/api/support${path}`,
		data,
		config: { timeout: 15000, showDefaultMsg: false, ...config },
	})
