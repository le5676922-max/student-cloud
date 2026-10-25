import axios from '~/http/request'

export const getWorkspaceSummaryReq = (config = {}) =>
	axios({ url: '/api/workspace-admin/summary', method: 'get', config })
export const getWorkspaceMembersReq = (page, search = '', config = {}) =>
	axios({
		url: '/api/workspace-admin/members',
		method: 'get',
		data: { page, search },
		config,
	})
export const createWorkspaceMemberReq = (data) =>
	axios({
		url: '/api/workspace-admin/members',
		method: 'post',
		data,
	})
export const setWorkspaceMemberEnabledReq = (id, value) =>
	axios({
		url: `/api/workspace-admin/members/${id}/enabled?value=${value}`,
		method: 'post',
	})

export const getWorkspaceMemberPermissionsReq = (id, config = {}) =>
	axios({
		url: `/api/workspace-admin/members/${id}/permissions`,
		method: 'get',
		config,
	})

export const resetWorkspaceMemberPasswordReq = (id, data, config = {}) =>
	axios({
		url: `/api/workspace-admin/members/${id}/password`,
		method: 'post',
		data,
		config: { showDefaultMsg: false, ...config },
	})
export const saveWorkspaceMemberPermissionsReq = (id, data, config = {}) =>
	axios({
		url: `/api/workspace-admin/members/${id}/permissions`,
		method: 'put',
		data,
		config,
	})

export const getWorkspaceInvitationsReq = () =>
	axios({ url: '/api/workspace-admin/invitations', method: 'get' })
export const createWorkspaceInvitationReq = (data) =>
	axios({ url: '/api/workspace-admin/invitations', method: 'post', data })
export const revokeWorkspaceInvitationReq = (id) =>
	axios({ url: `/api/workspace-admin/invitations/${id}`, method: 'delete' })
export const getWorkspaceJoinRequestsReq = () =>
	axios({ url: '/api/workspace-admin/join-requests', method: 'get' })
export const reviewWorkspaceJoinRequestReq = (id, approve) =>
	axios({
		url: `/api/workspace-admin/join-requests/${id}/review`,
		method: 'post',
		data: { approve },
	})
