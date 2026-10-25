import axios from '~/http/request'

/** Authentication stays in the existing request interceptor, never in memory URLs or local storage. */
export const aiMemoryRequest = (method, path = '', data, config = {}) =>
	axios({
		method,
		url: `/api/ai/memories${path}`,
		data,
		config: { showDefaultMsg: false, timeout: 15000, ...config },
	})
