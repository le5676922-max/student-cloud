import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { shareDownloadUrl } from '../src/utils/share-download.mjs'

test('share download keeps the exact backend-bound relative ticket', () => {
	const url = '/pd/1/file.txt?Signature=SHARE1.synthetic-proof&share=fixture'
	assert.equal(shareDownloadUrl({ name: 'file.txt', url }), url)
})

test('share download accepts absolute HTTP and HTTPS content URLs without rebuilding them', () => {
	for (const url of [
		'http://127.0.0.1:8080/pd/1/file.txt?Signature=synthetic',
		'https://cloud.example.invalid/pd/1/file.txt?Signature=synthetic',
	]) {
		assert.equal(shareDownloadUrl({ url }), url)
	}
})

test('missing or denied URLs never become clickable share download endpoints', () => {
	for (const row of [undefined, null, {}, { url: null }, { url: '' }, { url: 42 }]) {
		assert.equal(shareDownloadUrl(row), '')
	}
})

test('share content links reject executable or ambiguous URL schemes', () => {
	for (const url of [
		'javascript:alert(1)',
		'data:text/html,invalid',
		'file:///tmp/invalid',
		'//example.invalid/file.txt',
		'not-a-url',
	]) {
		assert.equal(shareDownloadUrl({ url }), '')
	}
})

test('share listing and actions preserve server URLs instead of generating fresh unbound links', async () => {
	const listing = await readFile(
		new URL('../src/composables/share/useShareData.js', import.meta.url),
		'utf8'
	)
	const actions = await readFile(
		new URL('../src/composables/file/useShareActions.js', import.meta.url),
		'utf8'
	)
	assert.match(listing, /let list = res\.data\.fileItemList \|\| \[\]/)
	assert.doesNotMatch(listing, /getShareFileDownloadUrlReq/)
	assert.match(actions, /return shareDownloadUrl\(row\)/)
	assert.doesNotMatch(actions, /getShareFileDownloadUrlReq/)
	assert.match(actions, /const url = buildDownloadUrl\(row\)\s+if \(!url\) return/)
})
