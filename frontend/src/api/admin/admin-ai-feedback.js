import axios from '~/http/request'

const request = (method, path, data, config) =>
	axios({
		url: `/admin/ai-feedback${path}`,
		method,
		data,
		config: { timeout: 15000, showDefaultMsg: false, ...config },
	})

export const listAiFeedbackReq = (params, config = {}) =>
	request('get', '', params, config)
export const getAiFeedbackReq = (id, config = {}) =>
	request('get', `/${encodeURIComponent(id)}`, undefined, config)
export const saveAiFeedbackReq = (id, data, config = {}) =>
	request('put', `/${encodeURIComponent(id)}`, data, config)
export const exportAiFeedbackReq = (config = {}) =>
	request('get', '/export', undefined, config)

const knowledgeRequest = (method, path, data, config = {}) =>
	axios({
		url: `/admin/ai-knowledge${path}`,
		method,
		data,
		config: { timeout: 15000, showDefaultMsg: false, ...config },
	})
export const getAiKnowledgeStateReq = (id, config = {}) =>
	knowledgeRequest(
		'get',
		`/feedback/${encodeURIComponent(id)}`,
		undefined,
		config
	)
export const getAiKnowledgeHistoryReq = (id, config = {}) =>
	knowledgeRequest(
		'get',
		`/history/${encodeURIComponent(id)}`,
		undefined,
		config
	)
export const refineAiKnowledgeReq = (data, config = {}) =>
	knowledgeRequest('post', '/refine', data, { timeout: 50000, ...config })
export const publishAiKnowledgeReq = (data, config = {}) =>
	knowledgeRequest('post', '/publish', data, config)
export const withdrawAiKnowledgeReq = (data, config = {}) =>
	knowledgeRequest('post', '/withdraw', data, config)
