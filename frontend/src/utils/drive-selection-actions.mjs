// The toolbar mirrors existing endpoints: mixed file/folder moves and multi-file
// downloads are deliberately not offered as if the backend supported them.
export function driveSelectionActions(rows, permission = {}, metadata = {}) {
	const selected = Array.isArray(rows) ? rows : []
	const valid =
		selected.length > 0 &&
		selected.every((item) => item?.type === 'FILE' || item?.type === 'FOLDER')
	const files = selected.filter((item) => item?.type === 'FILE').length
	const folders = selected.filter((item) => item?.type === 'FOLDER').length
	const single = valid && selected.length === 1
	const uniform =
		valid && (files === selected.length || folders === selected.length)
	const allowed = (name) => valid && permission[name] === true
	return {
		count: valid ? selected.length : 0,
		files,
		folders,
		download: single && files === 1 && allowed('download'),
		share: allowed('createShareLink') && (metadata.controlledShareFilesOnly !== true || (single && files === 1)),
		rename:
			single &&
			allowed('rename') &&
			(files === 1 || metadata.supportRenameFolder === true),
		move:
			uniform &&
			allowed('move') &&
			(folders === 0 || metadata.supportMoveFolder === true),
		copy:
			uniform &&
			allowed('copy') &&
			(folders === 0 || metadata.supportCopyFolder === true),
		delete: allowed('delete'),
	}
}
