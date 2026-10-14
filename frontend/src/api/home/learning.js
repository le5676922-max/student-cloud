import axios from '~/http/request'

export const learningRequest = (method, path, data = undefined, config = {}) =>
	axios({
		method,
		url: `/api/learning${path}`,
		data,
		config: { showDefaultMsg: false, ...config },
	})

export const learningStorageList = (config = {}) =>
	axios({ method: 'get', url: '/api/storage/list', config })

export const learningFiles = (storageKey, path) =>
	axios({
		method: 'post',
		url: '/api/storage/files',
		data: { storageKey, path },
	})
