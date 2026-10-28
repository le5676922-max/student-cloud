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
	// Construct a protected same-origin endpoint. Never trust a download URL supplied by a row.
	const validId = (value) =>
		/^(?:[1-9][0-9]{0,18}|[a-f0-9]{32})$/.test(String(value))
	if (!validId(courseId) || !validId(material?.id))
		throw new Error('课程资料标识无效，请刷新后重试')
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
