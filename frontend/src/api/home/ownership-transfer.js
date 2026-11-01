import axios from '~/http/request'

const base = (type, id) => {
	if (type === 'workspace') return '/api/workspace-admin/ownership-transfers'
	if (type === 'course' && /^\d+$/.test(String(id)) && Number(id) > 0)
		return `/api/courses/${id}/ownership-transfers`
	throw new Error('交接对象无效')
}
const call = (url, method, data, config) =>
	axios({
		url,
		method,
		data,
		config: { showDefaultMsg: false, timeout: 15000, ...config },
	})
export const getOwnershipTransfersReq = (type, id, config) =>
	call(base(type, id), 'get', undefined, config)
export const createOwnershipTransferReq = (type, id, data, config) =>
	call(base(type, id), 'post', data, config)
export const decideOwnershipTransferReq = (
	type,
	id,
	transferId,
	action,
	config
) => {
	if (
		!/^[a-zA-Z0-9-]{1,36}$/.test(transferId) ||
		!['accept', 'reject', 'cancel'].includes(action)
	)
		throw new Error('交接操作无效')
	return call(
		`${base(type, id)}/${transferId}/${action}`,
		'post',
		undefined,
		config
	)
}
