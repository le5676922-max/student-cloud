import axios from '~/http/request'

export const agentTaskRequest = (
	method,
	path = '',
	data = undefined,
	config = {}
) =>
	axios({
		method,
		url: `/api/ai/tasks${path}`,
		data,
		config: { showDefaultMsg: false, timeout: 15000, ...config },
	})
