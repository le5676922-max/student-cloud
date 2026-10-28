import test from 'node:test'
import assert from 'node:assert/strict'
import { driveSelectionActions } from '../src/utils/drive-selection-actions.mjs'

const permission = { download: true, createShareLink: true, rename: true, move: true, copy: true, delete: true }
const metadata = { supportRenameFolder: true, supportMoveFolder: true, supportCopyFolder: true }
const file = { type: 'FILE', name: 'notes.txt' }
const folder = { type: 'FOLDER', name: 'courses' }

test('single files expose existing authorized actions; multiple downloads are not invented', () => {
	const single = driveSelectionActions([file], permission, metadata)
	for (const action of ['download', 'share', 'rename', 'move', 'copy', 'delete']) assert.equal(single[action], true)
	const multiple = driveSelectionActions([file, { ...file, name: 'other.txt' }], permission, metadata)
	assert.equal(multiple.count, 2)
	assert.equal(multiple.download, false)
	assert.equal(multiple.rename, false)
	assert.equal(multiple.move, true)
})

test('folder operations require explicit provider capability, not just user permission', () => {
	for (const capability of [undefined, null, false, 'true']) {
		const actions = driveSelectionActions([folder], permission, { supportRenameFolder: capability, supportMoveFolder: capability, supportCopyFolder: capability })
		for (const action of ['download', 'rename', 'move', 'copy']) assert.equal(actions[action], false)
		assert.equal(actions.share, true)
		assert.equal(actions.delete, true)
	}
	assert.equal(driveSelectionActions([folder], permission, metadata).rename, true)
})

test('mixed selection supports share and confirmed deletion but never the type-specific move/copy API', () => {
	const actions = driveSelectionActions([file, folder], permission, metadata)
	assert.equal(actions.files, 1)
	assert.equal(actions.folders, 1)
	assert.equal(actions.share, true)
	assert.equal(actions.delete, true)
	for (const action of ['download', 'rename', 'move', 'copy']) assert.equal(actions[action], false)
})

test('missing grants and synthetic parent/cloud rows fail closed', () => {
	for (const rows of [undefined, [], [{ type: 'BACK' }], [{ type: 'ROOT' }], [null], [file, { type: 'BACK' }]]) {
		const actions = driveSelectionActions(rows, permission, metadata)
		assert.equal(actions.count, 0)
		for (const action of ['download', 'share', 'rename', 'move', 'copy', 'delete']) assert.equal(actions[action], false)
	}
	for (const grant of [undefined, false, 'true']) {
		const actions = driveSelectionActions([file], { download: grant, createShareLink: grant, rename: grant, move: grant, copy: grant, delete: grant }, metadata)
		for (const action of ['download', 'share', 'rename', 'move', 'copy', 'delete']) assert.equal(actions[action], false)
	}
})

test('remote read-only and writable directory responses never offer unavailable mutations or sharing', () => {
	const remotePermission = { download: true, preview: true, upload: true, newFolder: true,
		copy: false, move: false, rename: false, delete: false, createShareLink: false }
	const remoteMetadata = { recycleBin: false, supportRenameFolder: false,
		supportMoveFolder: false, supportCopyFolder: false, supportDeleteNotEmptyFolder: false }
	for (const rows of [[file], [folder], [file, folder]]) {
		const result = driveSelectionActions(rows, remotePermission, remoteMetadata)
		for (const action of ['copy', 'move', 'rename', 'delete', 'share']) assert.equal(result[action], false)
		assert.equal(result.download, rows.length === 1 && rows[0] === file)
	}
})
