import assert from 'node:assert/strict'
import test from 'node:test'
import {
	directoryCountLabel,
	driveEmptyState,
} from '../src/utils/drive-presentation.mjs'

test('directory count uses real page totals, not loaded rows or parent navigation', () => {
	assert.equal(
		directoryCountLabel([{ type: 'BACK' }, { type: 'FILE' }], { total: 327 }),
		'327 项'
	)
	assert.equal(
		directoryCountLabel([
			{ type: 'BACK' },
			{ type: 'FILE' },
			{ type: 'FOLDER' },
		]),
		'2 项'
	)
	assert.equal(directoryCountLabel([], { total: 0 }), '0 项')
	for (const total of [-1, NaN, Infinity, 2.5, '200'])
		assert.equal(directoryCountLabel([{ type: 'FILE' }], { total }), '1 项')
})

test('root count describes clouds and ignores stale directory paging', () => {
	assert.equal(
		directoryCountLabel(
			[{ type: 'ROOT' }, { type: 'ROOT' }],
			{ total: 999 },
			false
		),
		'2 个云盘'
	)
	assert.equal(directoryCountLabel(undefined, undefined, false), '0 个云盘')
	assert.equal(directoryCountLabel([null, { type: 'BACK' }]), '0 项')
})

test('empty states distinguish no permission, an empty directory, filtering and shares', () => {
	assert.match(driveEmptyState().title, /云盘/)
	assert.match(driveEmptyState({ hasStorage: true }).detail, /上传/)
	assert.match(
		driveEmptyState({ hasStorage: true, query: ' notes ' }).detail,
		/清空筛选/
	)
	assert.match(driveEmptyState({ shareMode: true }).detail, /分享者/)
	assert.equal(
		driveEmptyState({ query: '   ', hasStorage: true }).title,
		'这个目录还是空的'
	)
})
