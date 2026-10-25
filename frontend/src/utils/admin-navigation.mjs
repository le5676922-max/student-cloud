// Keep daily file/member work in the cloud drive and workspace console.
export const adminPrimaryNavigation = [
	{
		id: 'overview',
		name: '总览',
		href: '/admin/overview',
		icon: 'overview',
	},
	{
		id: 'workspaces',
		name: '工作空间',
		href: '/admin/tenant-list',
		icon: 'workspace',
	},
	{
		id: 'members',
		name: '空间人员',
		href: '/admin/user-list',
		icon: 'members',
	},
	{
		id: 'storage',
		name: '存储源',
		href: '/admin/storage-list',
		icon: 'storage',
	},
	{
		id: 'support',
		name: '人工客服',
		href: '/admin/support',
		icon: 'members',
	},
]
export const adminNavigationGroups = [
	{
		id: 'resources',
		name: '分享与链接',
		items: [
			{
				id: 'shares',
				name: '分享记录',
				href: '/admin/share-link',
				icon: 'table',
			},
			{
				id: 'short-links',
				name: '短链接',
				href: '/admin/download-link',
				icon: 'table',
			},
			{
				id: 'link-settings',
				name: '下载链接设置',
				href: '/admin/download-link/setting',
				icon: 'setting',
			},
		],
	},
	{
		id: 'security',
		name: '站点设置',
		items: [
			{
				id: 'site',
				name: '基本设置',
				href: '/admin/site-setting',
				icon: 'setting',
			},
			{
				id: 'view',
				name: '显示设置',
				href: '/admin/view-setting',
				icon: 'view',
			},
			{
				id: 'security',
				name: '安全设置',
				href: '/admin/security-setting',
				icon: 'shield',
			},
			{ id: 'access', name: '访问控制', href: '/admin/access', icon: 'shield' },
			{ id: 'sso', name: '单点登录', href: '/admin/sso', icon: 'members' },
			{
				id: 'global-account',
				name: '全局管理员账号',
				href: '/admin/user-edit/1',
				icon: 'members',
			},
		],
	},
	{
		id: 'logs',
		name: '日志诊断',
		items: [
			{
				id: 'login-log',
				name: '登录日志',
				href: '/admin/login-log',
				icon: 'document',
			},
			{
				id: 'download-log',
				name: '下载日志',
				href: '/admin/download-link/log',
				icon: 'document',
			},
			{
				id: 'ai-feedback',
				name: 'AI 反馈',
				href: '/admin/ai-feedback',
				icon: 'document',
			},
			{
				id: 'system-log',
				name: '下载系统日志',
				action: 'download-log',
				icon: 'download',
			},
		],
	},
]
export function isAdminNavigationActive(item, path) {
	if (!item.href) return false
	if (item.id === 'members' && /^\/admin\/user-edit(?:\/|$)/.test(path))
		return !/^\/admin\/user-edit\/[012]$/.test(path)
	if (item.id === 'storage' && /^\/admin\/storage-edit(?:\/|$)/.test(path))
		return true
	return item.href === path
}
export function activeAdminNavigationGroup(path) {
	return adminNavigationGroups.find((group) =>
		group.items.some((item) => isAdminNavigationActive(item, path))
	)?.id
}
// Local UI state only: a route opens its own section, not previously visited sections.
export function adminNavigationExpansionForPath(path) {
	const group = activeAdminNavigationGroup(path)
	return group ? ['more', group] : []
}
export function toggleAdminNavigationGroup(expanded, id) {
	if (id === 'more') return expanded.includes('more') ? [] : ['more']
	if (!adminNavigationGroups.some((group) => group.id === id))
		return [...expanded]
	return expanded.includes(id) ? ['more'] : ['more', id]
}
export function workspaceMemberPermissionLink(row, tenantId, loginInfo) {
	if (
		loginInfo?.isLogin !== true ||
		loginInfo?.role !== 'TENANT_ADMIN' ||
		loginInfo.tenantId !== tenantId ||
		row?.role !== 'USER' ||
		row.membershipStatus !== 'ACTIVE' ||
		!Number.isSafeInteger(row.id) ||
		row.id <= 2 ||
		!Number.isSafeInteger(tenantId) ||
		tenantId <= 0 ||
		row.tenantId !== tenantId
	)
		return null
	return `/workspace-admin?memberId=${row.id}`
}
export function workspaceManagerPreset(query, userId) {
	if (userId || query?.role !== 'TENANT_ADMIN') return null
	const rawId = query.tenantId
	if (typeof rawId !== 'string' || !/^[1-9]\d{0,9}$/.test(rawId)) return null
	const tenantId = Number(rawId)
	return tenantId <= 2147483647 ? { role: 'TENANT_ADMIN', tenantId } : null
}

export function managedWorkspaceGrantDefaults(preset, source) {
	if (
		!preset ||
		source?.tenantId !== preset.tenantId ||
		source.key !== `workspace-${preset.tenantId}` ||
		source.remark !== 'managed-workspace-shared-storage:v1' ||
		source.enable !== true
	)
		return { enable: false, permissions: [] }
	return {
		enable: true,
		permissions: [
			'preview',
			'download',
			'upload',
			'newFolder',
			'rename',
			'copy',
			'move',
			'delete',
			'search',
			'createShareLink',
			'batchDownload',
			'packageDownload',
		],
	}
}
