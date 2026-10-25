export function normalizeCourseInvitation(value) {
	if (typeof value !== 'string') return ''
	const code = value.trim().toLowerCase()
	return /^[a-f0-9]{32}$/.test(code) ? code : ''
}

export function courseInvitationUrl(origin, code) {
	const normalized = normalizeCourseInvitation(code)
	if (!normalized) throw new Error('邀请信息无效，请重新生成')
	const base = new URL(origin)
	if (!['https:', 'http:'].includes(base.protocol) || base.origin !== origin)
		throw new Error('当前网站地址无法生成邀请链接')
	const url = new URL('/courses', base.origin)
	url.searchParams.set('invite', normalized)
	return url.href
}

export function courseInvitationStatus(invitation, now = Date.now()) {
	if (Number(invitation.revokedAt) > 0) return '已撤销'
	if (invitation.expiresAt === undefined) return '已失效'
	if (invitation.expiresAt !== null) {
		if (!Number.isFinite(Number(invitation.expiresAt))) return '已失效'
		if (Number(invitation.expiresAt) <= now) return '已过期'
	}
	if (
		invitation.maxMembers != null &&
		Number(invitation.usedCount) >= Number(invitation.maxMembers)
	)
		return '人数已满'
	return '有效'
}
