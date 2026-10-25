import axios from '~/http/request'
const call = (url, method, data, config) =>
	axios({
		url: `/api/workspace-cloud${url}`,
		method,
		data,
		config: { showDefaultMsg: false, timeout: 15000, ...config },
	})
export const getMemberCloudsReq = (config) => call('', 'get', undefined, config)
export const applyMemberCloudReq = (id, reason, config) =>
	call(`/${id}/apply`, 'post', { reason }, config)
export const getCloudApplicationsReq = (config) =>
	call('/requests', 'get', undefined, config)
export const reviewCloudApplicationReq = (id, data, config) =>
	call(`/requests/${id}/review`, 'post', data, config)
