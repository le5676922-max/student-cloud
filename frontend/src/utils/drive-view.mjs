/** The parent-directory navigation row is not a directory entry. */
export function directoryHasEntries(files) {
	return (
		Array.isArray(files) && files.some((item) => item && item.type !== 'BACK')
	)
}

export function fileItemActionLabel(item) {
	const action =
		item.type === 'BACK'
			? '返回上一级'
			: item.type === 'ROOT'
			? '打开云盘'
			: item.type === 'FOLDER'
			? '打开文件夹'
			: '打开文件'
	return `${action}：${item.name}`
}
