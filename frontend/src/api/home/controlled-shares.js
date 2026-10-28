import request from '~/http/request'
import { controlledShareContentPath } from '~/utils/controlled-sharing.mjs'

export const controlledSharesRequest = (
	path = '',
	method = 'get',
	data,
	config = {}
) =>
	request({
		url: `/api/shares${path}`,
		method,
		data,
		config: { showDefaultMsg: false, timeout: 15000, ...config },
	})

export async function downloadControlledShare(id, config = {}) {
	let response
	try {
		response = await request({
			url: controlledShareContentPath(id),
			method: 'get',
			config: {
				showDefaultMsg: false,
				timeout: 60000,
				responseType: 'blob',
				responseIntercept: false,
				...config,
			},
		})
	} catch (failure) {
		const body = failure?.response?.data
		if (
			body instanceof Blob &&
			body.type.includes('json') &&
			body.size <= 16384
		) {
			let message
			try {
				message = JSON.parse(await body.text()).msg
			} catch {
				/* Retain the original failure. */
			}
			if (typeof message === 'string') throw new Error(message)
		}
		throw failure
	}
	if (!(response.data instanceof Blob))
		throw new Error('下载响应无效，请刷新分享后重试')
	if (response.data.type.includes('json')) {
		let message = '无法下载此分享，请刷新后重试'
		try {
			if (response.data.size <= 16384)
				message = JSON.parse(await response.data.text()).msg || message
		} catch {
			/* Keep the generic message. */
		}
		throw new Error(message)
	}
	return response.data
}
