import assert from 'node:assert/strict'
import test from 'node:test'
import { privateDownloadUrl } from '../src/utils/private-file-download.mjs'

test('protected file download accepts only a relative endpoint in the selected drive', () => {
	const url =
		'/api/drives/drive-one/content?path=%2F%E8%AF%BE%E7%A8%8B%2Fnotes.txt'
	assert.equal(privateDownloadUrl(url, 'drive-one'), url)
	for (const invalid of [
		'https://other.invalid/api/drives/drive-one/content?path=/file',
		'//other.invalid/api/drives/drive-one/content?path=/file',
		'/api/drives/drive-other/content?path=/file',
		'/api/drives/drive-one/content?path=/file&token=secret',
		'/api/drives/drive-one/content?path=/one&path=/two',
		'/api/drives/drive-one/content?path=/file#fragment',
		'/api/drives/drive-one/content?path=/%2e%2e/file',
		'/api/drives/drive-one/content?path=%5cfile',
		'/api/drives/drive-one/content?path=%00',
		'/api/drives/drive-one/content?path=relative',
		'/api/drives/drive-one/content?path=%invalid',
		'/api/drives/drive-one/content?path=/file\\name',
	])
		assert.throws(() => privateDownloadUrl(invalid, 'drive-one'))
})
