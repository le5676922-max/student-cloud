export function canAccessAdministration(loginInfo) {
	return (
		loginInfo?.isLogin === true &&
		loginInfo?.isAdmin === true &&
		loginInfo?.role === 'ADMIN'
	)
}

export function canAccessWorkspaceAdministration(loginInfo) {
	return (
		loginInfo?.isLogin === true &&
		loginInfo?.role === 'TENANT_ADMIN' &&
		Number.isInteger(loginInfo?.tenantId)
	)
}

export function roleLabel(role) {
	return (
		{
			ADMIN: '全局管理员',
			TENANT_ADMIN: '工作空间管理员',
			USER: '普通用户',
		}[role] ?? '未知角色'
	)
}
