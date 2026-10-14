import axios from '~/http/request'

export const getGlobalOverviewReq = (config = {}) =>
	axios({ url: '/admin/overview', method: 'get', config })

export const getGlobalWorkspacesReq = (params = {}, config = {}) =>
	axios({
		url: '/admin/overview/workspaces',
		method: 'get',
		data: params,
		config,
	})

export const provisionWorkspaceStorageReq = (tenantId, config = {}) =>
	axios({
		url: `/admin/overview/workspaces/${tenantId}/storage`,
		method: 'post',
		config,
	})
