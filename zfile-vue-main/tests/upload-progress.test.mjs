import assert from 'node:assert/strict'
import test from 'node:test'
import { calculateUploadProgress } from '../src/composables/file/upload-progress.mjs'

test('multipart overhead does not change the displayed file size', () => {
	assert.deepEqual(calculateUploadProgress(107, { loaded: 300, total: 300 }), {
		loaded: 107, progress: 100,
	})
	assert.deepEqual(calculateUploadProgress(100, { loaded: 150, total: 300 }), {
		loaded: 50, progress: 50,
	})
})

test('upload progress handles missing totals and is clamped', () => {
	assert.deepEqual(calculateUploadProgress(100, { loaded: 50 }), { loaded: 50, progress: 50 })
	assert.deepEqual(calculateUploadProgress(100, { loaded: 200, total: 100 }), { loaded: 100, progress: 100 })
})

test('empty or invalid progress never produces NaN or Infinity', () => {
	for (const size of [0, -1, NaN, undefined]) {
		assert.deepEqual(calculateUploadProgress(size, { loaded: NaN, total: 0 }), { loaded: 0, progress: 0 })
	}
	assert.deepEqual(calculateUploadProgress(100, { loaded: -1, total: 100 }), { loaded: 0, progress: 0 })
})
