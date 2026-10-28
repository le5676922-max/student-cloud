const opaqueId = (value) => {
	if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value))
		throw new Error('分享标识无效，请重新打开完整链接')
	return value
}
export const controlledSharePath = (id) => `/controlled-shares/${opaqueId(id)}`
export const controlledShareContentPath = (id) =>
	`/api/shares/${opaqueId(id)}/content`
const unsafeControl = (value) =>
	[...value].some(
		(character) =>
			character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127
	)

export function captureControlledShare(rows, context) {
	if (!Array.isArray(rows) || rows.length !== 1 || rows[0]?.type !== 'FILE')
		throw new Error('当前仅支持单个文件分享，请选择一个文件')
	const row = rows[0]
	const parent = row.path || context?.path || '/'
	if (
		!/^[a-z0-9][a-z0-9_-]{2,63}$/.test(context?.storageKey || '') ||
		!parent.startsWith('/') ||
		parent.includes('\\') ||
		unsafeControl(parent) ||
		parent.split('/').some((part) => part === '.' || part === '..') ||
		typeof row.name !== 'string' ||
		!row.name ||
		row.name.length > 255 ||
		/[\\/]/.test(row.name) ||
		unsafeControl(row.name) ||
		['.', '..'].includes(row.name)
	)
		throw new Error('文件路径无效，请刷新目录后重试')
	if (Number(row.size) > 50 * 1024 * 1024)
		throw new Error('分享文件不能超过50MiB')
	return Object.freeze({
		storageKey: context.storageKey,
		path: `${parent.replace(/\/$/, '')}/${row.name}`,
	})
}

export function controlledShareInput(file, options, now = Date.now()) {
	if (!options?.consent) throw new Error('请确认已了解分享的访问范围')
	if (!['OWN_WORKSPACE', 'COURSE'].includes(options.audience))
		throw new Error('请选择本空间或课程成员作为访问范围')
	const courseId =
		options.audience === 'COURSE' ? Number(options.courseId) : null
	if (
		options.audience === 'COURSE' &&
		(!Number.isSafeInteger(courseId) || courseId < 1)
	)
		throw new Error('请选择已加入的课程')
	const days = options.days ?? 7
	if (![1, 7, 30].includes(days)) throw new Error('分享有效期须为1、7或30天')
	return {
		...file,
		audience: options.audience,
		courseId,
		expiresAt: days === 7 ? undefined : now + days * 86400000,
	}
}
