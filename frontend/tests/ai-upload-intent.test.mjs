import test from 'node:test'
import assert from 'node:assert/strict'
import { isUploadCommand, explicitUploadPath, existingUploadNames } from '../src/utils/ai-upload-intent.mjs'

test('only explicit upload commands open the file picker', () => {
	assert.equal(isUploadCommand('帮我上传实验报告到云盘'), true)
	assert.equal(isUploadCommand('把文件存入云盘'), true)
	assert.equal(isUploadCommand('怎么上传文件？'), false)
	assert.equal(isUploadCommand('可以上传文件吗'), false)
})

test('explicit paths and exact duplicate names are extracted conservatively', () => {
	assert.equal(explicitUploadPath('上传到 /课程/实验'), '/课程/实验')
	assert.equal(explicitUploadPath('上传到课程文件夹'), null)
	assert.deepEqual(existingUploadNames([{ name: 'A.pdf' }, { name: 'B.pdf' }], [{ name: 'A.pdf' }]), ['A.pdf'])
})
