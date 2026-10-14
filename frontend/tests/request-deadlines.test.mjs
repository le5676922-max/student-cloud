import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { metadataRequestTimeout } from '../src/utils/request-deadlines.mjs'

test('management metadata and login checks have bounded deadlines', () => {
	for (const url of ['/admin/storages', '/admin/config', '/admin/users'])
		assert.equal(metadataRequestTimeout('get', url), 12000)
	assert.equal(metadataRequestTimeout('GET', '/user/login/check'), 10000)
})

test('metadata defaults do not shorten uploads, downloads, third-party calls or AI work', () => {
	for (const [method, url, config] of [
		['post', '/api/ai/chat'],
		['post', '/api/storage/upload'],
		['put', '/admin/storage'],
		['get', '/admin/log/download', { responseType: 'blob' }],
		['get', '/admin/export', { responseType: 'arraybuffer' }],
		['get', 'https://example.invalid/admin/storages'],
		['get', '/api/storage/files'],
	])
		assert.equal(metadataRequestTimeout(method, url, config), 0)
})

test('explicit request deadlines override the default and admin layout has no duplicate reads', async () => {
	const [request, layout] = await Promise.all([
		readFile(new URL('../src/http/request.js', import.meta.url), 'utf8'),
		readFile(new URL('../src/layouts/admin.vue', import.meta.url), 'utf8'),
	])
	assert.match(
		request,
		/timeout: metadataRequestTimeout\(method, url, config\),\s*\.\.\.config/
	)
	assert.doesNotMatch(layout, /checkLoginReq|useAdminSetting/)
	assert.match(layout, /loadConfigReq\(\{\s*timeout: 5000/)
})
