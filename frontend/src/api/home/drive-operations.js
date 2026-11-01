import axios from '~/http/request'

export const listDriveOperationsReq = (key, params, config) =>
	axios({
		url: `/api/drives/${encodeURIComponent(key)}/operations`,
		method: 'get',
		data: params,
		config: { showDefaultMsg: false, timeout: 15000, ...config },
	})
