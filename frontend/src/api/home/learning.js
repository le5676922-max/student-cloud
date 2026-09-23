import axios from '~/http/request'

export const learningRequest = (method, path, data = undefined) =>
	axios({
		method,
		url: `/api/learning${path}`,
		data,
		config: { showDefaultMsg: false },
	})

export const learningStorageList = () =>
	axios({ method: 'get', url: '/api/storage/list' })

export const learningFiles = (storageKey, path) =>
	axios({
		method: 'post',
		url: '/api/storage/files',
		data: { storageKey, path },
	})
