import axios from '~/http/request'

export const agentScheduleRequest = (
	method,
	path = '',
	data = undefined,
	config = {}
) =>
	axios({
		method,
		url: `/api/ai/schedules${path}`,
		data,
		config: { showDefaultMsg: false, timeout: 15000, ...config },
	})
