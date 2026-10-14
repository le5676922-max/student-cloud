import { canAccessAdministration } from './roles.mjs'

// Preserve the request's result/rejection, including successful empty lists.
export async function loadHeaderStorageSources({
	request,
	onLoaded,
	onFailure,
}) {
	try {
		const response = await request()
		if (!Array.isArray(response?.data)) {
			const error = new Error('云盘列表格式不正确，请刷新页面重试')
			error.code = 'INVALID_STORAGE_LIST'
			throw error
		}
		onLoaded(response)
		return response
	} catch (error) {
		onFailure?.(error)
		throw error
	}
}

export function emptyStorageNotice(loginInfo) {
	if (canAccessAdministration(loginInfo)) {
		return {
			message: '当前没有可用云盘，是否前往全局控制台检查存储源配置？',
			configurePath: '/admin/storage-list',
		}
	}
	if (loginInfo?.isLogin === true && loginInfo.role === 'TENANT_ADMIN') {
		return {
			message:
				'当前空间没有可用云盘，请刷新重试；仍不可用请联系全局管理员检查空间存储。',
		}
	}
	if (loginInfo?.isLogin === true) {
		return {
			message: '当前没有可用云盘，请联系空间管理员检查存储授权。',
		}
	}
	return { message: '暂无可访问的云盘。' }
}
