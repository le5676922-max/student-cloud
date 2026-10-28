import {
	formatOverviewDate,
	normalizeOverviewWorkspaceQuery,
	formatOverviewCount,
} from './admin-overview.mjs'

export function workspaceUserTenantId(value) {
	if (!['number', 'string'].includes(typeof value)) return null
	if (typeof value === 'string' && !/^\d+$/.test(value.trim())) return null
	const number = Number(value)
	return Number.isSafeInteger(number) && number > 0 ? number : null
}

export function normalizeWorkspaceUserQuery(
	params = {},
	role = 'TENANT_ADMIN'
) {
	const normalized = normalizeOverviewWorkspaceQuery(params)
	return {
		keyword: normalized.keyword,
		page: normalized.page,
		pageSize: normalized.pageSize,
		role: role === 'USER' ? 'USER' : 'TENANT_ADMIN',
	}
}

// An initial exact workspace link must not constrain subsequent directory searches.
export function workspaceDirectorySearchQuery(keyword, params = {}) {
	return normalizeOverviewWorkspaceQuery({
		...params,
		workspaceId: undefined,
		keyword,
		page: 1,
	})
}

export function workspaceUserStatus(row) {
	if (row?.membershipStatus === 'PENDING')
		return { label: '待审核', type: 'warning' }
	if (row?.membershipStatus === 'REJECTED')
		return { label: '已拒绝', type: 'danger' }
	if (row?.membershipStatus !== 'ACTIVE')
		return { label: '状态未获取', type: 'info' }
	if (row.enable === true) return { label: '已启用', type: 'success' }
	if (row.enable === false) return { label: '已停用', type: 'info' }
	return { label: '状态未获取', type: 'info' }
}

export function canManageWorkspaceAdministrator(row, tenantId, accountModel) {
	return (
		row?.role === 'TENANT_ADMIN' &&
		Number.isSafeInteger(row.id) &&
		row.id > (accountModel === 'independent' || row.accountModel === 'independent' ? 0 : 2) &&
		workspaceUserTenantId(row.tenantId) === workspaceUserTenantId(tenantId) &&
		workspaceUserTenantId(tenantId) !== null &&
		row.membershipStatus === 'ACTIVE' &&
		typeof row.enable === 'boolean'
	)
}

export function validWorkspaceUserPage(data, tenantId, role) {
	const id = workspaceUserTenantId(tenantId)
	return Boolean(
		id !== null &&
			Array.isArray(data?.records) &&
			Number.isSafeInteger(data.total) &&
			data.total >= 0 &&
			Number.isInteger(data.page) &&
			data.page >= 1 &&
			data.page <= 10000 &&
			Number.isInteger(data.pageSize) &&
			data.pageSize >= 1 &&
			data.pageSize <= 100 &&
			data.records.length <= data.pageSize &&
			data.records.every(
				(row) =>
					row !== null &&
					Number.isSafeInteger(row?.id) &&
					row.id > (data.accountModel === 'independent' ? 0 : 2) &&
					workspaceUserTenantId(row.tenantId) === id &&
					row.role === role
			)
	)
}

export function validWorkspaceDirectoryPage(data) {
	return Boolean(
		Array.isArray(data?.records) &&
			Number.isSafeInteger(data.total) &&
			data.total >= 0 &&
			Number.isInteger(data.page) &&
			data.page >= 1 &&
			data.page <= 10000 &&
			Number.isInteger(data.pageSize) &&
			data.pageSize >= 1 &&
			data.pageSize <= 100 &&
			data.records.length <= data.pageSize &&
			data.records.every(
				(row) =>
					workspaceUserTenantId(row?.id) !== null &&
					typeof row?.name === 'string'
			)
	)
}

export function workspaceUserSequence(page, index) {
	if (!page || !Number.isInteger(index) || index < 0) return '—'
	const query = normalizeWorkspaceUserQuery(page)
	return formatOverviewCount((query.page - 1) * query.pageSize + index + 1)
}

export function workspaceUserCreatedAt(value) {
	return formatOverviewDate(value, 'Asia/Shanghai')
}

export function workspaceAdministratorCreateLink(tenantId) {
	const id = workspaceUserTenantId(tenantId)
	return id === null
		? null
		: {
				path: '/admin/user-edit',
				query: { role: 'TENANT_ADMIN', tenantId: String(id) },
		  }
}

export function createWorkspaceUserRequestGate() {
	let sequence = 0
	let controller = null
	let disposed = false
	const cancel = () => {
		sequence += 1
		controller?.abort()
		controller = null
	}
	return {
		begin() {
			if (disposed) return null
			cancel()
			controller = new AbortController()
			return { id: sequence, signal: controller.signal }
		},
		isCurrent(request) {
			return !disposed && Boolean(request) && request.id === sequence
		},
		finish(request) {
			if (!disposed && request?.id === sequence) controller = null
		},
		cancel,
		dispose() {
			disposed = true
			cancel()
		},
	}
}
