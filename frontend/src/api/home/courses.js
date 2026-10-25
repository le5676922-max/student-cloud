import request from '~/http/request'

export const courseRequest = (path = '', method = 'get', data, config = {}) =>
	request({
		url: `/api/courses${path}`,
		method,
		data,
		config: { showDefaultMsg: false, ...config },
	})

export const uploadCourseMaterial = (id, file, config = {}) => {
	const data = new FormData()
	data.append('file', file)
	return courseRequest(`/${id}/materials`, 'post', data, {
		timeout: 120000,
		headers: { 'Content-Type': undefined },
		...config,
	})
}

export async function downloadCourseMaterial(courseId, material, config = {}) {
	const response = await courseRequest(
		`/${courseId}/materials/${material.id}/download`,
		'get',
		undefined,
		{
			timeout: 60000,
			responseType: 'blob',
			responseIntercept: false,
			...config,
		}
	)
	if (response.data?.type?.includes('json')) {
		let message = '无法下载课程资料，请刷新后重试'
		try {
			message = JSON.parse(await response.data.text()).msg || message
		} catch {
			/* Keep a bounded generic error. */
		}
		throw new Error(message)
	}
	return response.data
}
