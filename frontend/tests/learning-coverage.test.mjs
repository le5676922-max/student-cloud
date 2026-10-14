import test from 'node:test'
import assert from 'node:assert/strict'
import { learningCoverage } from '../src/utils/learning.mjs'

test('old or missing index metadata never claims complete coverage', () => {
	assert.equal(learningCoverage({}).partial, true)
	assert.match(
		learningCoverage({ indexVersion: 1, coveredChars: 12000 }).text,
		/旧版/
	)
})

test('coverage displays bounds and partial page caveat', () => {
	const result = learningCoverage({
		indexVersion: 2,
		coveredChars: 240000,
		indexedPages: 140,
		totalPages: 400,
		truncated: true,
	})
	assert.equal(result.partial, true)
	assert.match(result.text, /240,000/)
	assert.match(result.text, /140 \/ 400/)
	assert.match(result.text, /末页/)
})

test('complete extraction does not promise OCR coverage', () => {
	const result = learningCoverage({
		indexVersion: 2,
		coveredChars: 13001,
		truncated: false,
	})
	assert.equal(result.partial, false)
	assert.match(result.text, /不含图片/)
})
