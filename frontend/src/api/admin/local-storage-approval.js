import axios from '~/http/request'
export const loadLocalStorageRequestsReq = (isAdmin, config = {}) =>
	axios({
		url: isAdmin
			? '/admin/storage-local-requests'
			: '/api/workspace-storage/local-requests',
		method: 'get',
		config: { timeout: 12000, showDefaultMsg: false, ...config },
	})
export const submitLocalStorageRequestReq = (reason, config = {}) =>
	axios({
		url: '/api/workspace-storage/local-requests',
		method: 'post',
		data: { reason },
		config: { timeout: 12000, showDefaultMsg: false, ...config },
	})
export const reviewLocalStorageRequestReq = (id, approve, note, config = {}) =>
	axios({
		url: `/admin/storage-local-requests/${id}/review`,
		method: 'post',
		data: { approve, note },
		config: { timeout: 30000, showDefaultMsg: false, ...config },
	})
