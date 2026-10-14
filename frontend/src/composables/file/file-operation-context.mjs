// Copy only request identity fields, never live selection or routing objects.
export function captureFileOperationContext(context, rows = []) {
	if (typeof context.storageKey !== 'string' || !context.storageKey)
		throw new Error('请先选择存储源')
	if (typeof context.path !== 'string' || !context.path.startsWith('/'))
		throw new Error('当前目录无效')
	return Object.freeze({
		storageKey: context.storageKey,
		path: context.path,
		rootPath: context.rootPath || '',
		accountIdentity: context.accountIdentity || '',
		rows: Object.freeze(
			rows.map((row) =>
				Object.freeze({
					name: row.name,
					path: row.path,
					type: row.type,
				})
			)
		),
	})
}

export function isFileOperationContextCurrent(snapshot, current) {
	return (
		snapshot.storageKey === current.storageKey &&
		snapshot.path === current.path &&
		snapshot.rootPath === (current.rootPath || '') &&
		snapshot.accountIdentity === (current.accountIdentity || '')
	)
}

export function fileOperationSelectionType(rows) {
	const type = rows[0]?.type
	return (type === 'FILE' || type === 'FOLDER') &&
		rows.every((row) => row.type === type)
		? type
		: null
}
