import request from '~/http/request'

export const getBusinessMetricsReq = (tenantId, config = {}) =>
	request({
		url: '/admin/business-metrics',
		method: 'get',
		data: tenantId ? { tenantId } : {},
		config: { timeout: 10000, showDefaultMsg: false, ...config },
	})
