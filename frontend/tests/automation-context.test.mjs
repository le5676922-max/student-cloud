import test from 'node:test'
import assert from 'node:assert/strict'
import {
	normalizeAutomationDirectory,
	parseAutomationContext,
	buildAutomationRoute,
	automationContextFromRoute,
	canPreviewDirectory,
} from '../src/utils/automation-context.mjs'

test('automation directory accepts canonical unicode directories without decoding twice', () => {
	for (const [input, expected] of [
		['/', '/'],
		['/课程/第一周/', '/课程/第一周'],
		['/my files', '/my files'],
	])
		assert.equal(normalizeAutomationDirectory(input), expected)
	for (const value of [
		'',
		'files',
		'//host/a',
		'/a//b',
		'/a/../b',
		'/./b',
		'/%2e%2e',
		'/%252e',
		'/a\\b',
		'/C:/a',
		'/a?x',
		'/a#x',
		'/a\n',
		' /a',
		'/a ',
		['/a'],
		null,
		'/'.repeat(501),
	])
		assert.equal(normalizeAutomationDirectory(value), null)
})

test('automation query only prefills scalar whitelisted fields, never execution or identity', () => {
	assert.deepEqual(
		parseAutomationContext({
			storageKey: 'workspace-2',
			path: '/资料/',
			type: 'ORGANIZE_FILES',
			confirmed: true,
			courseId: 8,
			ownerUserId: 1,
			tags: 'private',
		}),
		{ storageKey: 'workspace-2', path: '/资料', type: 'ORGANIZE_FILES' }
	)
	assert.deepEqual(
		parseAutomationContext({
			storageKey: ['safe', 'other'],
			path: ['/'],
			type: ['ORGANIZE_FILES'],
		}),
		{}
	)
	assert.deepEqual(
		parseAutomationContext({
			storageKey: '../other',
			path: '/..',
			type: 'DELETE_FILES',
		}),
		{}
	)
	assert.deepEqual(
		buildAutomationRoute({
			storageKey: 'one',
			path: '/',
			type: 'RENAME_BY_DATE',
		}),
		{
			path: '/agent-tasks',
			query: { storageKey: 'one', path: '/', type: 'RENAME_BY_DATE' },
		}
	)
})

test('current-directory shortcuts ignore non-file routes and invalid path segments', () => {
	const file = {
		meta: { layout: 'file' },
		params: { storageKey: 'workspace-2', fullpath: ['课程', '第一周'] },
	}
	assert.deepEqual(automationContextFromRoute(file), {
		storageKey: 'workspace-2',
		path: '/课程/第一周',
	})
	assert.deepEqual(
		automationContextFromRoute({
			...file,
			params: { storageKey: 'one', fullpath: '课程/' },
		}),
		{ storageKey: 'one', path: '/课程' }
	)
	assert.deepEqual(
		automationContextFromRoute({ ...file, params: { storageKey: 'one' } }),
		{ storageKey: 'one', path: '/' }
	)
	for (const route of [
		{ ...file, meta: { layout: 'admin' } },
		{ ...file, params: {} },
		{ ...file, params: { storageKey: 'one', fullpath: ['..'] } },
		{ ...file, params: { storageKey: 'one', fullpath: ['a/b'] } },
	])
		assert.deepEqual(automationContextFromRoute(route), {})
})

test('quick preview requires current local source and explicit per-operation grants', () => {
	const context = { storageKey: 'one', path: '/' }
	const source = { key: 'one', type: { key: 'local' } }
	assert.equal(
		canPreviewDirectory(
			context,
			source,
			{ move: true, newFolder: true },
			'ORGANIZE_FILES'
		),
		true
	)
	assert.equal(
		canPreviewDirectory(context, source, { rename: true }, 'RENAME_BY_DATE'),
		true
	)
	for (const [candidate, grants, type] of [
		[{ ...source, key: 'old' }, { rename: true }, 'RENAME_BY_DATE'],
		[{ ...source, type: 's3' }, { rename: true }, 'RENAME_BY_DATE'],
		[source, { move: true }, 'ORGANIZE_FILES'],
		[source, { rename: 1 }, 'RENAME_BY_DATE'],
		[source, { rename: true }, 'IMPORT_LEARNING'],
	])
		assert.equal(canPreviewDirectory(context, candidate, grants, type), false)
})
