import test from 'node:test'
import assert from 'node:assert/strict'
import {
	emptyStorageNotice,
	loadHeaderStorageSources,
} from '../src/utils/header-storage-load.mjs'

test('an empty storage list resolves normally and applies the empty response', async () => {
	const response = { data: [] }
	let applied
	const result = await loadHeaderStorageSources({
		request: async () => response,
		onLoaded: (value) => {
			applied = value
		},
		onFailure: () => assert.fail('an empty list is not an error'),
	})
	assert.equal(result, response)
	assert.equal(applied, response)
})

test('a populated storage list preserves the API response', async () => {
	const response = { data: [{ key: 'space-42', name: '空间云盘' }] }
	let applied
	assert.equal(
		await loadHeaderStorageSources({
			request: async () => response,
			onLoaded: (value) => {
				applied = value
			},
		}),
		response
	)
	assert.equal(applied, response)
})

test('network failures invoke cleanup and reject with the original error', async () => {
	const failure = new Error('network unavailable')
	let cleaned = false
	await assert.rejects(
		loadHeaderStorageSources({
			request: async () => {
				throw failure
			},
			onLoaded: () => assert.fail('failed requests cannot apply storage'),
			onFailure: (error) => {
				assert.equal(error, failure)
				cleaned = true
			},
		}),
		(error) => error === failure
	)
	assert.equal(cleaned, true)
})

test('invalid list envelopes reject safely rather than hanging or appearing empty', async () => {
	for (const response of [undefined, null, {}, { data: null }, { data: {} }]) {
		let cleaned = false
		await assert.rejects(
			loadHeaderStorageSources({
				request: async () => response,
				onLoaded: () => assert.fail('invalid responses cannot apply storage'),
				onFailure: () => {
					cleaned = true
				},
			}),
			{
				code: 'INVALID_STORAGE_LIST',
				message: '云盘列表格式不正确，请刷新页面重试',
			}
		)
		assert.equal(cleaned, true)
	}
})

test('synchronous request and response application errors also reject with cleanup', async () => {
	for (const stage of ['request', 'apply']) {
		const failure = new Error(stage)
		let cleaned = false
		await assert.rejects(
			loadHeaderStorageSources({
				request: () => {
					if (stage === 'request') throw failure
					return Promise.resolve({ data: [] })
				},
				onLoaded: () => {
					throw failure
				},
				onFailure: () => {
					cleaned = true
				},
			}),
			(error) => error === failure
		)
		assert.equal(cleaned, true)
	}
})

test('only an authenticated global administrator is offered global storage configuration', () => {
	assert.equal(
		emptyStorageNotice({ isLogin: true, isAdmin: true, role: 'ADMIN' })
			.configurePath,
		'/admin/storage-list'
	)
	for (const info of [
		undefined,
		{},
		{ isLogin: false, isAdmin: true, role: 'ADMIN' },
		{ isLogin: true, isAdmin: false, role: 'ADMIN' },
		{ isLogin: true, isAdmin: true },
		{ isLogin: true, isAdmin: true, role: 'TENANT_ADMIN' },
		{ isLogin: true, isAdmin: false, role: 'TENANT_ADMIN' },
		{ isLogin: true, isAdmin: false, role: 'USER' },
		{ isLogin: false, isAdmin: false, role: 'GUEST' },
	]) {
		assert.equal(emptyStorageNotice(info).configurePath, undefined)
	}
})

test('workspace administrators and members receive actionable role-appropriate empty notices', () => {
	assert.match(
		emptyStorageNotice({ isLogin: true, role: 'TENANT_ADMIN' }).message,
		/联系全局管理员/
	)
	assert.match(
		emptyStorageNotice({ isLogin: true, role: 'USER' }).message,
		/联系空间管理员/
	)
	assert.equal(
		emptyStorageNotice({ isLogin: false }).message,
		'暂无可访问的云盘。'
	)
})
