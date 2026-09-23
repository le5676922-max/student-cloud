import test from 'node:test'
import assert from 'node:assert/strict'
import { supportedLearningFile, learningFileRoute, filterLearningMaterials } from '../src/utils/learning.mjs'

test('only supported document extensions can be indexed', () => {
  for (const name of ['资料.txt', 'NOTE.MD', 'report.pdf', 'report.docx']) assert.equal(supportedLearningFile(name), true)
  for (const name of ['photo.png', 'file.doc', 'file.txt.exe', 'file']) assert.equal(supportedLearningFile(name), false)
})
test('file links cannot inject query or fragment components', () => {
  assert.equal(learningFileRoute('my drive', '/notes/a?b#c/'), '/my%20drive/notes/a%3Fb%23c/')
  assert.equal(learningFileRoute('local', '/'), '/local/')
})
test('material filters match names and tags without changing source data', () => {
  const data = [{ name: '数据库.txt', tags: '期末,重点' }, { name: 'README.md', tags: '参考' }]
  assert.equal(filterLearningMaterials(data, ' 期末 ').length, 1)
  assert.equal(filterLearningMaterials(data, 'readme')[0].name, 'README.md')
  assert.equal(filterLearningMaterials(data, '').length, 2)
  assert.equal(data.length, 2)
})
