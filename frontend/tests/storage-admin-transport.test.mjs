import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import axios from 'axios'
import { metadataRequestTimeout } from '../src/utils/request-deadlines.mjs'

// Run the production API and request adapter against Axios' in-memory transport.
// This checks the actual query/body boundary without opening network connections.
async function storageTransport(respond = async () => ({ data: null })) {
	const calls = []
	const instance = axios.create({
		adapter: async (config) => {
			calls.push(config)
			return {
				status: 200,
				statusText: 'OK',
				headers: {},
				config,
				...(await respond(config)),
			}
		},
	})
	const [requestSource, apiSource] = await Promise.all([
		readFile(new URL('../src/http/request.js', import.meta.url), 'utf8'),
		readFile(
			new URL('../src/api/admin/admin-storage.js', import.meta.url),
			'utf8'
		),
	])
	const request = new Function(
		'instance',
		'metadataRequestTimeout',
		requestSource
			.replace(/^import .*$/gm, '')
			.replace('export default axios', 'return axios')
	)(instance, metadataRequestTimeout)
	const api = new Function(
		'axios',
		apiSource
			.replace(/^import .*$/gm, '')
			.replaceAll('export const ', 'const ') +
			'\nreturn { existStorageKeyReq, saveStorageSettingReq, testStorageConnectionReq, loadPendingStorageWritesReq, inspectPendingStorageWriteReq }'
	)(request)
	return { api, calls, instance }
}

test('confirmed storage deletion sends the explicit confirmation to the HTTP boundary', async () => {
	const calls = []
	const source = await readFile(
		new URL('../src/api/admin/admin-storage.js', import.meta.url),
		'utf8'
	)
	const api = new Function(
		'axios',
		source.replace(/^import .*$/m, '').replaceAll('export const ', 'const ') +
			'\nreturn { deleteStorageReq }'
	)((request) => {
		calls.push(request)
		return Promise.resolve({ data: null })
	})
	await api.deleteStorageReq(31)
	assert.deepEqual(calls, [
		{ url: '/admin/storage/31', method: 'delete', data: { confirmed: true } },
	])
})

test('manual alias queries retain canonical and compatibility parameter names through the real GET adapter', async () => {
	const { api, calls, instance } = await storageTransport(async () => ({
		data: false,
	}))
	for (const parameter of ['key', 'storageKey']) {
		const controller = new AbortController()
		const result = await api.existStorageKeyReq(
			{ [parameter]: 'course-files_2026' },
			{ signal: controller.signal, showDefaultMsg: false }
		)
		assert.equal(result.data, false)
		const request = calls.at(-1)
		assert.equal(request.method, 'get')
		assert.equal(
			instance.getUri(request),
			`/admin/storage/exist/key?${parameter}=course-files_2026`
		)
		assert.equal(request.data, undefined)
		assert.equal(request.signal, controller.signal)
		assert.equal(request.showDefaultMsg, false)
		assert.equal(request.timeout, 10000)
	}
	assert.equal(calls.length, 2)
})

test('connection checks retain explicit read/write flags, credentials and bounded mode-specific deadlines at POST boundary', async () => {
	const { api, calls } = await storageTransport()
	for (const verifyWrite of [false, true]) {
		const payload = {
			type: 's3',
			verifyWrite,
			storageSourceAllParam: {
				endPoint: 'storage.example.test',
				secretKey: 'synthetic-secret',
				bucketName: 'course-files',
			},
		}
		const controller = new AbortController()
		await api.testStorageConnectionReq(payload, {
			signal: controller.signal,
			showDefaultMsg: false,
		})
		const request = calls.at(-1)
		assert.equal(request.url, '/admin/storage/test-connection')
		assert.equal(request.method, 'post')
		assert.deepEqual(JSON.parse(request.data), payload)
		assert.equal(request.params, undefined)
		assert.equal(request.timeout, verifyWrite ? 75000 : 22000)
		assert.equal(request.signal, controller.signal)
		assert.equal(request.showDefaultMsg, false)
	}
	assert.equal(calls.length, 2)
})

test('failed saves propagate the original server response once without a transport retry or fallback request', async () => {
	const failure = Object.assign(new Error('synthetic conflict'), {
		response: {
			status: 409,
			data: { code: 'STORAGE_KEY_EXISTS', msg: '存储源别名已存在', data: null },
		},
	})
	const { api, calls } = await storageTransport(async () => {
		throw failure
	})
	const payload = { name: '课程资料', key: 'course-files', type: 'local' }
	await assert.rejects(
		api.saveStorageSettingReq(payload, { showDefaultMsg: false }),
		(error) => error === failure
	)
	await Promise.resolve()
	assert.equal(calls.length, 1)
	assert.equal(calls[0].url, '/admin/storage')
	assert.equal(calls[0].method, 'post')
	assert.deepEqual(JSON.parse(calls[0].data), payload)
	assert.equal(calls[0].timeout, 30000)
	assert.equal(calls[0].showDefaultMsg, false)
})

test('pending write loading and inspection send only saved IDs with bounded deadlines and cancellation', async () => {
	const { api, calls } = await storageTransport()
	const controller = new AbortController()
	await api.loadPendingStorageWritesReq(31, {
		signal: controller.signal,
		showDefaultMsg: false,
	})
	await api.inspectPendingStorageWriteReq(31, 'a'.repeat(32), {
		signal: controller.signal,
		showDefaultMsg: false,
	})
	assert.equal(calls.length, 2)
	assert.equal(calls[0].url, '/admin/storage/31/pending-writes')
	assert.equal(calls[0].method, 'get')
	assert.equal(calls[0].data, undefined)
	assert.equal(
		calls[1].url,
		'/admin/storage/31/pending-writes/' + 'a'.repeat(32) + '/inspect'
	)
	assert.equal(calls[1].method, 'post')
	assert.equal(calls[1].data, undefined)
	for (const request of calls) {
		assert.equal(request.signal, controller.signal)
		assert.equal(request.showDefaultMsg, false)
		assert.ok(request.timeout > 0 && request.timeout <= 30000)
	}
})
