/** Count actual directory entries, never the synthetic parent/back row. */
export function directoryCountLabel(files, page, isStorageDirectory = true) {
	const items = Array.isArray(files) ? files : []
	const count =
		isStorageDirectory && Number.isInteger(page?.total) && page.total >= 0
			? page.total
			: items.filter((item) => item && item.type !== 'BACK').length
	return isStorageDirectory ? `${count} 项` : `${count} 个云盘`
}

export function driveEmptyState({
	hasStorage = false,
	query = '',
	shareMode = false,
} = {}) {
	if (String(query).trim())
		return {
			title: '没有找到匹配的文件',
			detail: '换个关键词，或清空筛选后再试。',
		}
	if (shareMode)
		return {
			title: '这里还没有分享文件',
			detail: '分享内容可能已变化，请联系分享者确认。',
		}
	return hasStorage
		? { title: '这个目录还是空的', detail: '上传文件，或新建文件夹开始整理。' }
		: { title: '还没有可访问的云盘', detail: '请联系管理员开通存储空间。' }
}
