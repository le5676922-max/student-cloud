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

test('missing version and malformed metadata never claim current complete coverage', () => {
	for (const material of [
		{ coveredChars: 100, truncated: false },
		{ indexVersion: 2, coveredChars: -1, truncated: false },
		{ indexVersion: 2, coveredChars: 100 },
	]) assert.equal(learningCoverage(material).partial, true)
})

test('empty indexed text is actionable rather than displayed as fully searchable', () => {
	const result = learningCoverage({ indexVersion: 2, coveredChars: 0, truncated: false })
	assert.equal(result.partial, true)
	assert.match(result.text, /未记录可检索文字/)
})

test('plain text does not invent pages and incomplete page counts agree with overview classification', () => {
	const text = learningCoverage({ indexVersion: 2, coveredChars: 240000, truncated: true })
	assert.match(text.text, /剩余文字未覆盖/)
	assert.doesNotMatch(text.text, /末页/)
	const pages = learningCoverage({ indexVersion: 2, coveredChars: 200, truncated: false, indexedPages: 2, totalPages: 4 })
	assert.equal(pages.partial, true)
	assert.match(pages.text, /部分索引/)
})
