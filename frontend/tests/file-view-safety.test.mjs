import test from 'node:test'
import assert from 'node:assert/strict'
import {
	fileShortcutAllowed,
	galleryColumns,
	galleryColumnCount,
} from '../src/utils/file-view-safety.mjs'

test('file shortcuts never intercept input, textareas, selects, buttons or editable descendants', () => {
	for (const tagName of ['INPUT', 'TEXTAREA', 'SELECT', 'BUTTON']) {
		assert.equal(
			fileShortcutAllowed({ key: 'Delete', target: { tagName } }),
			false
		)
		assert.equal(
			fileShortcutAllowed(
				{ key: 'a', target: { tagName: 'DIV' } },
				{ tagName }
			),
			false
		)
	}
	assert.equal(
		fileShortcutAllowed({ target: { isContentEditable: true } }),
		false
	)
	assert.equal(
		fileShortcutAllowed({
			target: { tagName: 'SPAN', closest: () => ({ tagName: 'DIV' }) },
		}),
		false
	)
	assert.equal(
		fileShortcutAllowed({
			target: { tagName: 'DIV' },
			composedPath: () => [{ tagName: 'INPUT' }],
		}),
		false
	)
})

test('file shortcuts ignore IME, repeated or handled keys but allow unhandled file-area keys', () => {
	for (const property of ['isComposing', 'repeat', 'defaultPrevented'])
		assert.equal(
			fileShortcutAllowed({ key: 'Delete', [property]: true }),
			false
		)
	assert.equal(fileShortcutAllowed({ key: 'Delete', keyCode: 229 }), false)
	assert.equal(fileShortcutAllowed(undefined), false)
	assert.equal(
		fileShortcutAllowed({
			key: 'a',
			ctrlKey: true,
			target: { tagName: 'DIV' },
		}),
		true
	)
	assert.equal(
		fileShortcutAllowed({
			key: 'Backspace',
			target: { tagName: 'TD', closest: () => null },
		}),
		true
	)
})

test('gallery has no empty cells for partial rows or directory page boundaries', () => {
	for (const total of [0, 1, 2, 3, 5, 199, 200, 201]) {
		const files = Array.from({ length: total }, (_, index) => ({
			name: `photo-${index}`,
		}))
		const columns = galleryColumns(files, 3)
		const flattened = columns.flat()
		assert.equal(flattened.length, total)
		assert.equal(new Set(flattened).size, total)
		assert.ok(flattened.every((item) => item && typeof item.name === 'string'))
		assert.ok(columns.every((column) => column.length > 0))
	}
	assert.deepEqual(galleryColumns(['a', 'b', 'c', 'd', 'e'], 3), [
		['a', 'd'],
		['b', 'e'],
		['c'],
	])
})

test('gallery validates column settings and ignores absent items without division by zero', () => {
	for (const invalid of [0, -1, NaN, Infinity, 'invalid'])
		assert.equal(galleryColumnCount(invalid), 3)
	assert.equal(galleryColumnCount(0.5), 1)
	assert.equal(galleryColumnCount(1000), 24)
	assert.equal(galleryColumnCount('4'), 4)
	assert.deepEqual(galleryColumns([undefined, 'a', null, 'b'], 0), [
		['a'],
		['b'],
	])
	assert.deepEqual(galleryColumns(undefined, 3), [])
})
