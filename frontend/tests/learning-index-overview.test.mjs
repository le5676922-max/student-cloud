import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createContext, Script } from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { learningIndexState, learningIndexOverview, filterLearningIndex } from '../src/utils/learning-index-overview.mjs'

const indexed = { id: 1, name: '数据库讲义.pdf', tags: '事务,复习', indexVersion: 2, coveredChars: 3000, truncated: false }
const partial = { id: 2, name: '数据结构笔记.pdf', tags: '', indexVersion: 2, coveredChars: 240000, truncated: true }
const legacy = { id: 3, name: '数据库实验.md', tags: '事务', indexVersion: 1 }
const samples = [indexed, partial, legacy]

test('index overview distinguishes recorded full text, partial and unknown without claiming live health', () => {
	assert.deepEqual(learningIndexOverview(samples), { all: 3, indexed: 1, partial: 1, unknown: 1, untagged: 1 })
	assert.equal(learningIndexState({ ...indexed, indexedPages: 2, totalPages: 3 }), 'partial')
	assert.equal(learningIndexState({ ...indexed, indexVersion: 3 }), 'indexed')
})

test('old, empty, missing and malformed metadata never count as indexed text', () => {
	for (const item of [null, {}, { ...indexed, indexVersion: '2' }, { ...indexed, indexVersion: 1 },
		{ ...indexed, coveredChars: 0 }, { ...indexed, coveredChars: -1 },
		{ ...indexed, coveredChars: NaN }, { ...indexed, truncated: undefined }])
		assert.equal(learningIndexState(item), 'unknown')
	assert.deepEqual(learningIndexOverview(null), { all: 0, indexed: 0, partial: 0, unknown: 0, untagged: 0 })
	assert.equal(learningIndexOverview([null, indexed, undefined]).all, 1)
})

test('state and text filters intersect, including Chinese tags and trimmed search', () => {
	assert.deepEqual(filterLearningIndex(samples, ' 数据库 ', 'indexed').map(item => item.id), [1])
	assert.deepEqual(filterLearningIndex(samples, '事务', 'unknown').map(item => item.id), [3])
	assert.deepEqual(filterLearningIndex(samples, '数据库', 'partial'), [])
	assert.deepEqual(filterLearningIndex(samples, '', 'partial').map(item => item.id), [2])
	assert.equal(filterLearningIndex(samples, '', 'not-a-state').length, 3)
})

test('untagged classification treats blank and separator-only tags as empty without changing inputs', () => {
	const items = ['', '  ', ' , ， ', null, undefined, '复习', '实验,'].map((tags, index) => Object.freeze({ ...indexed, id: index, tags }))
	Object.freeze(items)
	assert.equal(learningIndexOverview(items).untagged, 5)
	assert.equal(filterLearningIndex(items, '', 'untagged').length, 5)
	assert.equal(items.length, 7)
})

const filename = '../src/components/learning/LearningIndexOverview.vue'
const { descriptor } = parse(await readFile(new URL(filename, import.meta.url), 'utf8'))
const compiled = compileScript(descriptor, { id: 'learning-overview-test' })
const executable = compiled.content.replace(/^import[\s\S]*?['"][^'"]+['"]\s*;?/gm, '')
	.replace('export default', 'globalThis.Overview =')

function setup(materials = samples) {
	const sandbox = createContext({ ...vue, learningIndexOverview })
	new Script(executable).runInContext(sandbox)
	const props = vue.reactive({ materials, modelValue: 'all' })
	const events = []
	const scope = vue.effectScope()
	const api = scope.run(() => sandbox.Overview.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
	return { props, api, events, dispose: () => scope.stop() }
}

test('real overview setup updates counts and emits only selected filters, never a mutation request', (t) => {
	const h = setup()
	t.after(h.dispose)
	assert.equal(h.api.counts.value.all, 3)
	h.api.select('partial')
	h.api.select('delete')
	assert.deepEqual(h.events, [['update:modelValue', 'partial']])
	h.props.modelValue = 'unknown'
	assert.match(h.api.advice.value, /信息不足/)
	h.props.materials = [indexed]
	assert.equal(h.api.counts.value.partial, 0)
	assert.equal(h.api.counts.value.indexed, 1)
})

test('overview and learning page templates compile with accessible filters and truthful scope', async () => {
	const result = compileTemplate({ source: descriptor.template.content, filename, id: 'learning-overview-test', compilerOptions: { bindingMetadata: compiled.bindings } })
	assert.deepEqual(result.errors, [])
	assert.match(descriptor.template.content, /:aria-pressed=/)
	assert.match(descriptor.template.content, /不代表原文件仍有效/)
	const pageSource = await readFile(new URL('../src/pages/learning.vue', import.meta.url), 'utf8')
	const { descriptor: page } = parse(pageSource)
	const script = compileScript(page, { id: 'learning-page-overview-test' })
	assert.deepEqual(compileTemplate({ source: page.template.content, filename: 'learning.vue', id: 'learning-page-overview-test', compilerOptions: { bindingMetadata: script.bindings } }).errors, [])
	assert.match(pageSource, /watch\(selected, clearMaterialFilters/)
	assert.match(pageSource, /当前筛选条件下没有资料/)
})
