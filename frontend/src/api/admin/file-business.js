import request from '~/http/request'

export const getFileBusinessOverviewReq = (days = 7, config = {}) =>
	request({
		url: '/admin/file-business/overview',
		method: 'get',
		data: { days },
		config: { timeout: 15000, showDefaultMsg: false, ...config },
	})
