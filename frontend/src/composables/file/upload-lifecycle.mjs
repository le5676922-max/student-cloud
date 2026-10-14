const UPLOAD_TYPES = new Set(['PROXY', 'S3', 'MICROSOFT', 'UPYUN'])

// Snapshot only routing/permission metadata, never a mutable route or store reference.
export function uploadAccountIdentity(loginInfo) {
	if (loginInfo?.isLogin !== true || !loginInfo.username) return ''
	return JSON.stringify([
		String(loginInfo.username),
		loginInfo.tenantId ?? null,
	])
}

export function captureUploadTarget({
	storageKey,
	directory,
	folderConfig,
	rootPath,
	accountIdentity,
}) {
	if (!accountIdentity) throw new Error('请先登录后上传文件')
	if (typeof storageKey !== 'string' || !storageKey)
		throw new Error('未指定目标存储源')
	if (typeof directory !== 'string' || !directory.startsWith('/'))
		throw new Error('上传目录无效')
	if (folderConfig?.permission?.upload !== true)
		throw new Error('当前用户没有目标目录的上传权限')
	const uploadType = folderConfig?.metadata?.uploadType
	if (!UPLOAD_TYPES.has(uploadType))
		throw new Error('目标存储源暂不支持当前上传方式')
	return Object.freeze({
		storageKey,
		directory,
		uploadType,
		rootPath: typeof rootPath === 'string' ? rootPath : '',
		accountIdentity,
		canCreateFolder: folderConfig.permission.newFolder === true,
		needCreateFolderBeforeUpload:
			folderConfig.metadata.needCreateFolderBeforeUpload === true,
	})
}

export function uploadCancellationError() {
	const error = new Error('上传已取消，请检查目标目录确认是否已有文件')
	error.name = 'CanceledError'
	error.code = 'ERR_CANCELED'
	return error
}
