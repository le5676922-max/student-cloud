import axios from '~/http/request'

export const getTenantListReq = (config = {}) =>
	axios({
		url: '/admin/tenants',
		method: 'get',
		config,
	})

export const saveTenantReq = (data) =>
	axios({
		url: '/admin/tenant',
		method: 'post',
		data,
	})
