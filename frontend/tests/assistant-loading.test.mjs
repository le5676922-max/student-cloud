import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { shouldMountAssistant } from '../src/utils/assistant-loading.mjs'

test('assistant is loaded only on signed-in business pages', () => {
	for (const path of ['/login', '/login/reset', '/install', '/sso/callback'])
		assert.equal(shouldMountAssistant(path, { isLogin: true }), false)
	for (const path of ['/', '/admin/overview', '/agent-tasks', '/workspace-2']) {
		assert.equal(shouldMountAssistant(path, { isLogin: true }), true)
		for (const info of [undefined, {}, { isLogin: false }, { isLogin: 'true' }])
			assert.equal(shouldMountAssistant(path, info), false)
	}
	assert.equal(shouldMountAssistant(undefined, { isLogin: true }), false)
})

test('root does not eagerly import the assistant or mount it behind a hidden inner template', async () => {
	const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
	assert.match(
		app,
		/defineAsyncComponent\([\s\S]*import\('~\/components\/ai\/AiAssistant\.vue'\)/
	)
	assert.doesNotMatch(app, /import AiAssistant from/)
	assert.match(app, /<AiAssistant v-if="showAssistant"/)
})

test('static startup status is visible before app and configuration finish loading, with a reload link', async () => {
	const html = await readFile(new URL('../index.html', import.meta.url), 'utf8')
	assert.match(html, /id="app">\s*<section id="startup-status"/)
	assert.match(html, /role="status"/)
	assert.match(html, /<a href="">/)
	assert.doesNotMatch(html, /onclick=/)
	const main = await readFile(
		new URL('../src/main.ts', import.meta.url),
		'utf8'
	)
	assert.match(main, /\.finally\([\s\S]*app.mount\('#app'\)/)
})

test('navigation synchronously drops old upload grants and root before awaiting the new source', async () => {
	const page = await readFile(
		new URL('../src/pages/file.vue', import.meta.url),
		'utf8'
	)
	assert.match(page, /flush: 'sync'/)
	assert.match(
		page,
		/const loadDirectory = async[\s\S]*cancelLoadFile\(\)[\s\S]*updateUserRootPath\(''\)[\s\S]*await loadUserRootPathReq/
	)
	const loader = await readFile(
		new URL('../src/composables/file/useFileData.js', import.meta.url),
		'utf8'
	)
	assert.match(
		loader,
		/const cancelLoadFile[\s\S]*folderConfig\.permission = \{\}/
	)
})

test('new directory reads force fresh metadata but cursor pages may reuse the bounded snapshot', async () => {
	const loader = await readFile(
		new URL('../src/composables/file/useFileData.js', import.meta.url),
		'utf8'
	)
	assert.match(loader, /param\.refreshMetadata = !initParam\?\.pageCursor/)
})
