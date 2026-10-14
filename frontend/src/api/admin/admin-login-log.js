import axios from '~/http/request'

export const getLoginLogListReq = (data, config = {}) => {
	return axios({
		url: '/admin/login/log/list',
		method: 'get',
		data,
		config: { showDefaultMsg: false, ...config },
	})
}
