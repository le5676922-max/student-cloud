export function canAccessAdministration(loginInfo) {
  return loginInfo?.isLogin === true && loginInfo?.isAdmin === true && loginInfo?.role === 'ADMIN';
}

export function roleLabel(role) {
  return { ADMIN: '系统管理员', USER: '普通用户', GUEST: '访客' }[role] ?? '未知角色';
}
