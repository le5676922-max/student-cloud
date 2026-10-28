import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import * as lifecycle from '../src/composables/file/upload-lifecycle.mjs'
import * as progress from '../src/composables/file/upload-progress.mjs'
import * as resumableUpload from '../src/utils/resumable-upload.mjs'
import * as independentResumableUpload from '../src/utils/independent-resumable-upload.mjs'
import * as fileChangeRefresh from '../src/utils/file-change-refresh.mjs'

// Run the real composable/watchers. All signing, mkdir and HTTP transfers are synthetic.
const source = await readFile(
	new URL('../src/composables/file/useFileUpload.js', import.meta.url),
	'utf8'
)
const deferred = () => {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
const file = (name = 'same.txt', size = 10) => ({
	name,
	size,
	slice: (start, end) => ({ start, end }),
})
const plain = (value) => JSON.parse(JSON.stringify(value))
const flush = async () => {
	for (let step = 0; step < 6; step++) {
		await vue.nextTick()
		await Promise.resolve()
	}
}
const waitFor = async (predicate) => {
	for (let step = 0; step < 200; step++) {
		await flush()
		if (predicate()) return
		await new Promise((resolve) => setTimeout(resolve, 5))
	}
	assert.fail('Expected asynchronous upload state was not reached')
}
const largeFile = () => new File([new Uint8Array(resumableUpload.RESUMABLE_THRESHOLD + 9)], 'large.bin')
const resumeId = '12345678-1234-1234-1234-123456789abc'
const resumeCaps = { enabled: true, chunkSize: resumableUpload.RESUMABLE_CHUNK_SIZE, maxFileSize: 2 * 1024 ** 3, expiresInSeconds: 86400 }
const resumeSession = (size, receivedChunks = [], state = 'OPEN') => ({ id: resumeId, state, size, chunkSize: resumableUpload.RESUMABLE_CHUNK_SIZE, receivedChunks, expiresAt: Date.now() + 86400000, message: state })

function setupUploads({ limit = 2, uploadType = 'PROXY', resumable } = {}) {
	const signatures = [],
		transfers = [],
		folders = [],
		notices = [],
		loadings = []
	const resumableRequests = [], passwordContexts = []
	const fileChanges = []
	const credential = vue.ref('fixture-session-one')
	const route = vue.reactive({ key: 'source-one', path: '/original' })
	const storage = vue.reactive({
		loginInfo: { isLogin: true, username: '10001', tenantId: 1 },
		globalConfig: { maxFileUploads: limit },
		user: { rootPath: '/user-one' },
		permission: { upload: true, newFolder: true },
		folderConfig: {
			permission: { upload: true, newFolder: true },
			metadata: { uploadType, needCreateFolderBeforeUpload: false },
		},
	})
	const listeners = new Map()
	const body = {
		classList: { add() {}, remove() {} },
		addEventListener: (type, callback) => {
			if (!listeners.has(type)) listeners.set(type, new Set())
			listeners.get(type).add(callback)
		},
		removeEventListener: (type, callback) =>
			listeners.get(type)?.delete(callback),
	}
	const http = (kind, args) => {
		const pending = deferred()
		transfers.push({ kind, args, ...pending })
		return pending.promise
	}
	const modules = {
		'element-plus': {
			ElLoading: {
				service: () => {
					const loading = {
						closed: false,
						close() {
							this.closed = true
						},
					}
					loadings.push(loading)
					return loading
				},
			},
		},
		'~/api/home/file-operator': {
			uploadFileReq: (data, config) => {
				const pending = deferred()
				signatures.push({ data: plain(data), config, ...pending })
				return pending.promise
			},
			newFolderReq: (data, config) => {
				const pending = deferred()
				folders.push({ data: plain(data), config, ...pending })
				return pending.promise
			},
		},
		axios: {
			default: {
				CancelToken: {
					source: () => {
						const token = { canceled: false }
						return {
							token,
							cancel: () => {
								token.canceled = true
							},
						}
					},
				},
				put: (...args) => http('put', args),
				post: (...args) => http('post', args),
				isAxiosError: (error) => error?.name === 'AxiosError',
			},
		},
		'~/http/request': { default: (config) => {
			if (config.url.startsWith('/api/uploads/resumable')) {
				resumableRequests.push(config)
				return resumable ? resumable(config) : Promise.reject(new Error('Unexpected resumable request'))
			}
			return http('proxy', [config])
		} },
		'~/composables/file/useFilePwd': { default: () => ({ getPathPwd: (path, ignore, target) => {
			passwordContexts.push({ path, ignore, target })
			return '目录%password'
		} }) },
		'~/utils/resumable-upload.mjs': resumableUpload,
		'~/utils/independent-resumable-upload.mjs': independentResumableUpload,
		'~/utils/file-change-refresh.mjs': fileChangeRefresh,
		'@vueuse/core': { useStorage: () => credential, useEventBus: (key) => ({ emit: (change) => {
			assert.equal(key, 'cloud-files-changed')
			fileChanges.push(plain(change))
		} }) },
		'~/utils': {
			concatPath: (...parts) =>
				parts.filter(Boolean).join('/').replace(/\/+/g, '/'),
			removeDuplicateSeparator: (path) => path.replace(/\/+/g, '/'),
			fileSizeFormat: (size) => `${size} B`,
		},
		'./upload-progress.mjs': progress,
		'./upload-lifecycle.mjs': lifecycle,
		vue,
		'~/stores/storage-config': { default: () => storage },
		'~/composables/file/useTableOperator': {
			hasDialog: () => false,
			hasSearchInputFocus: () => false,
			hasPasswordInputFocus: () => false,
		},
		'~/composables/useRouterData': {
			default: () => ({
				storageKey: vue.computed(() => route.key),
				currentPath: vue.computed(() => route.path),
			}),
		},
	}
	const executable = source
		.replace(
			/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
			(_whole, bindings, name) =>
				bindings.trim().startsWith('{')
					? `const ${bindings.replace(
							/\s+as\s+/g,
							': '
					  )} = modules[${JSON.stringify(name)}];`
					: `const ${bindings.trim()} = modules[${JSON.stringify(
							name
					  )}].default;`
		)
		.replace('export default function', 'function')
	const context = vm.createContext({
		modules,
		...vue,
		AbortController,
		URL,
		console: { log() {}, error() {} },
		document: { querySelector: () => body },
		FormData: class {
			append() {}
		},
		ElMessage: {
			warning: (message) => notices.push(message),
			error: (message) => notices.push(message),
			success: (message) => notices.push(message),
		},
		ElMessageBox: {
			confirm: (_message, _title, options) => {
				options.callback?.('confirm')
				return Promise.resolve('confirm')
			},
		},
	})
	const module = vm.runInContext(
		`(function(){${executable}\nreturn {
		factory: useFileUpload,
		dispose: () => { uploadWatchScope.stop(); detachDropListeners?.(); }
	};})()`,
		context
	)
	const callerScope = vue.effectScope()
	let api
	callerScope.run(() => {
		api = module.factory()
	})
	return {
		api,
		storage,
		route,
		signatures,
		transfers,
		folders,
		notices,
		loadings,
		listeners,
		resumableRequests,
		passwordContexts,
		fileChanges,
		credential,
		factory: module.factory,
		callerScope,
		dispose: () => {
			callerScope.stop()
			module.dispose()
		},
		drop(items) {
			for (const callback of listeners.get('drop') || [])
				callback({
					dataTransfer: { items, files: [file()] },
					stopPropagation() {},
					preventDefault() {},
				})
		},
	}
}

test('resumable composable preserves captured target/password context, skips accepted chunks and publishes once', async (t) => {
	const item = largeFile(), received = [0]
	const h = setupUploads({ resumable: async (request) => {
		if (request.url.endsWith('/capabilities')) return { data: resumeCaps }
		if (request.url.endsWith('/init')) return { data: resumeSession(item.size, received) }
		if (request.method === 'PUT') {
			received.push(Number(request.url.split('/').at(-1)))
			request.config.onUploadProgress({ loaded: request.data.size })
			return { data: resumeSession(item.size, received) }
		}
		return { data: resumeSession(item.size, received, 'COMPLETED') }
	} })
	t.after(h.dispose)
	h.api.uploadFile(item)
	h.route.key = 'source-two'
	h.route.path = '/different'
	h.storage.user.rootPath = '/another-root'
	await waitFor(() => h.api.uploadProgressInfoSorted.value[0]?.status === 'finished')
	assert.equal(h.signatures.length, 0)
	assert.equal(h.transfers.length, 0)
	assert.deepEqual(h.resumableRequests.filter((request) => request.method === 'PUT').map((request) => request.url.split('/').at(-1)), ['1', '2'])
	const initialized = h.resumableRequests.find((request) => request.url.endsWith('/init'))
	assert.equal(initialized.data.path, '/original')
	assert.equal(initialized.data.storageKey, 'source-one')
	assert.equal(h.resumableRequests.filter((request) => request.url.endsWith('/complete')).length, 1)
	assert.ok(h.resumableRequests.every((request) => request.config.headers['X-Upload-Directory-Password'] === encodeURIComponent('目录%password')))
	assert.ok(h.passwordContexts.every((context) => context.path === '/original' && context.target.storageKey === 'source-one' && context.target.rootPath === '/user-one'))
	assert.equal(h.api.uploadProgressInfoSorted.value[0].progress, 100)
	assert.deepEqual(h.fileChanges, [{
		storageKey: 'source-one', path: '/original', accountIdentity: '["10001",1]',
	}])
})

test('independent upload branch authenticates every request and omits legacy directory passwords', async (t) => {
	const item = largeFile(), received = [0]
	const h = setupUploads({ resumable: async (request) => {
		assert.equal(request.config.containToken, true)
		assert.equal(request.config.headers['X-Upload-Directory-Password'], undefined)
		if (request.url.endsWith('/capabilities')) return { data: { ...resumeCaps, protocol: 'independent-v1', maxFileSize: 50 * 1024 * 1024 } }
		if (request.method === 'PUT') received.push(Number(request.url.split('/').at(-1)))
		return { data: resumeSession(item.size, [...received], request.url.endsWith('/complete') ? 'COMPLETED' : 'OPEN') }
	} })
	t.after(h.dispose)
	h.storage.globalConfig.accountModel = 'independent'
	h.api.uploadFile(item)
	await waitFor(() => h.api.uploadProgressInfoSorted.value[0]?.status === 'finished')
	assert.equal(h.signatures.length, 0)
	assert.equal(h.passwordContexts.length, 0)
	assert.equal(h.fileChanges[0].accountIdentity, '["10001",1]')
	assert.deepEqual(h.resumableRequests.filter(request => request.method === 'PUT').map(request => request.url.split('/').at(-1)), ['1', '2'])
})

test('independent logout rejects new uploads before making any resumable request', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.globalConfig.accountModel = 'independent'
	h.storage.loginInfo.isLogin = false
	h.api.uploadFile(largeFile())
	await flush()
	assert.equal(h.resumableRequests.length, 0)
	assert.equal(h.signatures.length, 0)
})

test('resumable probe failure is visible and never falls back to whole-file signing', async (t) => {
	const h = setupUploads({ resumable: async () => { throw new Error('续传服务暂不可用') } })
	t.after(h.dispose)
	h.api.uploadFile(largeFile())
	await waitFor(() => h.api.uploadProgressInfoSorted.value[0]?.status === 'error')
	assert.equal(h.resumableRequests.length, 1)
	assert.equal(h.signatures.length, 0)
	assert.match(h.api.uploadProgressInfoSorted.value[0].msg, /暂不可用/)
	assert.deepEqual(h.fileChanges, [])
})

test('resumable cancellation and account switch abort chunks, ignore late receipts, never DELETE or complete', async (t) => {
	for (const accountSwitch of [false, true]) {
		const item = largeFile(), pending = deferred()
		const h = setupUploads({ resumable: async (request) => {
			if (request.url.endsWith('/capabilities')) return { data: resumeCaps }
			if (request.url.endsWith('/init')) return { data: resumeSession(item.size) }
			return pending.promise
		} })
		t.after(h.dispose)
		h.api.uploadFile(item)
		await waitFor(() => h.resumableRequests.some((request) => request.method === 'PUT'))
		const chunk = h.resumableRequests.at(-1)
		if (accountSwitch) h.storage.loginInfo.username = 'other-user'
		else h.api.removeUploadFileByIndex(h.api.uploadProgressInfoSorted.value[0].index)
		assert.equal(chunk.config.signal.aborted, true)
		pending.resolve({ data: resumeSession(item.size, [0]) })
		await flush()
		assert.equal(h.api.uploadProgressInfoSorted.value.length, 0)
		assert.ok(!h.resumableRequests.some((request) => request.method === 'DELETE' || request.url.endsWith('/complete')))
		assert.equal(h.signatures.length, 0)
		assert.deepEqual(h.fileChanges, [])
	}
})

test('uncertain completion retry checks status with GET and never repeats complete', async (t) => {
	const item = largeFile()
	const h = setupUploads({ resumable: async (request) => {
		if (request.url.endsWith('/capabilities')) return { data: resumeCaps }
		if (request.url.endsWith('/init')) return { data: resumeSession(item.size, [0, 1, 2]) }
		if (request.url.endsWith('/complete')) throw new Error('Response lost after save')
		assert.equal(request.method, 'GET')
		return { data: resumeSession(item.size, [0, 1, 2], 'COMPLETED') }
	} })
	t.after(h.dispose)
	h.api.uploadFile(item)
	await waitFor(() => h.api.uploadProgressInfoSorted.value[0]?.status === 'error')
	const row = h.api.uploadProgressInfoSorted.value[0]
	assert.equal(row.resumableState, 'COMPLETING')
	assert.match(row.msg, /不要重复提交/)
	h.api.retryUpload(row)
	await waitFor(() => h.api.uploadProgressInfoSorted.value[0]?.status === 'finished')
	assert.equal(h.resumableRequests.at(-1).method, 'GET')
	assert.equal(h.resumableRequests.filter((request) => request.url.endsWith('/complete')).length, 1)
	assert.equal(h.signatures.length, 0)
	assert.equal(h.fileChanges.length, 1)
})

test('large unsupported provider explicitly opts out and agent silent upload supports resume', async (t) => {
	const unsupported = setupUploads({ resumable: async () => ({ data: { enabled: false } }) })
	t.after(unsupported.dispose)
	unsupported.api.uploadFile(largeFile())
	await waitFor(() => unsupported.signatures.length === 1)
	assert.equal(unsupported.resumableRequests.length, 1)
	const item = largeFile()
	const h = setupUploads({ resumable: async (request) => ({ data: request.url.endsWith('/capabilities') ? resumeCaps : resumeSession(item.size, [0, 1, 2], 'COMPLETED') }) })
	t.after(h.dispose)
	const saved = await h.api.silentUploadFile(item, { showFeedback: false, showLoading: false })
	assert.equal(saved.storageKey, 'source-one')
	assert.equal(saved.path, '/original')
	assert.equal(h.signatures.length, 0)
	assert.equal(h.resumableRequests.length, 2)
	assert.deepEqual(h.fileChanges, [{
		storageKey: 'source-one', path: '/original', accountIdentity: '["10001",1]',
	}])
})

test('signing retains source, path, uploader and user root after navigation', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.route.key = 'source-two'
	h.route.path = '/changed'
	h.storage.user.rootPath = '/another-user-root'
	h.storage.folderConfig.metadata.uploadType = 'S3'
	h.signatures[0].resolve({ data: '/signed-upload' })
	await flush()
	assert.deepEqual(h.signatures[0].data, {
		storageKey: 'source-one',
		path: '/original',
		name: 'same.txt',
		size: 10,
	})
	assert.equal(h.signatures[0].config.uploadContext.rootPath, '/user-one')
	assert.equal(h.transfers[0].kind, 'proxy')
})

test('canceling one same-named file during signing cancels only its unique index', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.api.uploadFile(file())
	const [first, second] = h.api.uploadProgressInfoSorted.value
	h.api.cancelUpload(second)
	assert.equal(h.signatures[1].config.signal.aborted, true)
	assert.equal(h.signatures[0].config.signal.aborted, false)
	assert.deepEqual(
		plain(h.api.uploadProgressInfoSorted.value.map((item) => item.index)),
		[first.index]
	)
	h.signatures.forEach((request) => request.resolve({ data: '/signed' }))
	await flush()
	assert.equal(h.transfers.length, 1)
})

test('late signing rejection after cancellation never recreates an error task', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.api.removeUploadFileByIndex(h.api.uploadProgressInfoSorted.value[0].index)
	h.signatures[0].reject(new Error('late failure'))
	await flush()
	assert.equal(h.api.uploadProgressInfoSorted.value.length, 0)
	assert.deepEqual(h.fileChanges, [])
	assert.equal(h.transfers.length, 0)
})

test('queue resumes its original source and nested directory without changing index', async (t) => {
	const h = setupUploads({ limit: 1 })
	t.after(h.dispose)
	h.api.uploadFile(file('first.txt'))
	h.api.uploadFile({
		...file('nested.txt'),
		webkitRelativePath: 'folder/sub/nested.txt',
	})
	const queuedIndex = h.api.uploadProgressInfoSorted.value.find(
		(item) => item.status === 'waiting'
	).index
	h.route.key = 'source-two'
	h.route.path = '/changed'
	h.storage.folderConfig.metadata.uploadType = 'S3'
	h.signatures[0].resolve({ data: '/first' })
	await flush()
	h.transfers[0].resolve({})
	await flush()
	assert.equal(h.signatures[1].data.storageKey, 'source-one')
	assert.equal(h.signatures[1].data.path, '/original/folder/sub')
	assert.equal(
		h.api.uploadProgressInfoSorted.value.find(
			(item) => item.name === 'nested.txt'
		).index,
		queuedIndex
	)
	h.signatures[1].resolve({ data: '/second' })
	await flush()
	assert.equal(h.transfers[1].kind, 'proxy')
})

test('failed upload retry keeps original target and duplicate retry does not enqueue twice', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.uploadFile({ ...file(), webkitRelativePath: 'folder/same.txt' })
	h.signatures[0].reject(new Error('signing unavailable'))
	await flush()
	const failed = h.api.uploadProgressInfoSorted.value[0]
	h.route.key = 'source-two'
	h.route.path = '/changed'
	h.storage.folderConfig.metadata.uploadType = 'S3'
	h.storage.folderConfig.permission.upload = false
	h.api.retryUpload(failed)
	h.api.retryUpload(failed)
	assert.equal(h.signatures.length, 2)
	assert.equal(h.signatures[1].data.storageKey, 'source-one')
	assert.equal(h.signatures[1].data.path, '/original/folder')
	h.signatures[1].resolve({ data: '/retry' })
	await flush()
	assert.equal(h.transfers[0].kind, 'proxy')
})

test('removing a queued same-named file leaves the active upload untouched', (t) => {
	const h = setupUploads({ limit: 1 })
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.api.uploadFile(file())
	const waiting = h.api.uploadProgressInfoSorted.value.find(
		(item) => item.status === 'waiting'
	)
	h.api.removeUploadFileByIndex(waiting.index)
	assert.equal(
		h.api.uploadProgressInfoStatistics.value.totalUploadingAndWaiting,
		1
	)
	assert.equal(h.signatures[0].config.signal.aborted, false)
})

test('account change cancels signing and clears queue; late results cannot start transfers', async (t) => {
	const h = setupUploads({ limit: 1 })
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.api.uploadFile(file('queued.txt'))
	h.storage.loginInfo.username = 'another-user'
	assert.equal(h.signatures[0].config.signal.aborted, true)
	assert.equal(h.api.uploadProgressInfoSorted.value.length, 0)
	h.signatures[0].resolve({ data: '/old-account' })
	await flush()
	assert.equal(h.signatures.length, 1)
	assert.equal(h.transfers.length, 0)
})

test('same account metadata refresh preserves uploads but tenant change cancels them', (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.storage.loginInfo = { ...h.storage.loginInfo, nickname: 'new nickname' }
	assert.equal(h.signatures[0].config.signal.aborted, false)
	h.storage.loginInfo.tenantId = 2
	assert.equal(h.signatures[0].config.signal.aborted, true)
})
test('replacement credential cancels active uploads and queued work for the same account', async (t) => {
	const h = setupUploads({ limit: 1 })
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.api.uploadFile(file('queued.txt'))
	h.credential.value = 'fixture-session-two'
	assert.equal(h.signatures[0].config.signal.aborted, true)
	assert.equal(h.api.uploadProgressInfoSorted.value.length, 0)
	h.signatures[0].resolve({ data: '/old-session' })
	await flush()
	assert.equal(h.transfers.length, 0)
	assert.equal(h.signatures.length, 1)
})

test('late transfer progress/success after cancel is ignored', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.signatures[0].resolve({ data: '/proxy' })
	await flush()
	const item = h.api.uploadProgressInfoSorted.value[0]
	const config = h.transfers[0].args[0].config
	h.api.cancelUpload(item)
	assert.equal(config.signal.aborted, true)
	assert.equal(config.cancelToken.canceled, true)
	config.onUploadProgress({ loaded: 10, total: 10 })
	h.transfers[0].resolve({})
	await flush()
	assert.equal(h.api.uploadProgressInfoSorted.value.length, 0)
})

test('OneDrive retains cancellation token across blocks and canceled 202 starts no next block', async (t) => {
	const h = setupUploads({ uploadType: 'MICROSOFT' })
	t.after(h.dispose)
	h.api.uploadFile(file('large.dat', 220000000))
	h.signatures[0].resolve({ data: 'https://upload.example/session' })
	await flush()
	const firstToken = h.transfers[0].args[2].cancelToken
	h.transfers[0].resolve({ status: 202 })
	await flush()
	assert.equal(h.transfers.length, 2)
	assert.equal(h.transfers[1].args[2].cancelToken, firstToken)
	h.api.cancelUpload(h.api.uploadProgressInfoSorted.value[0])
	h.transfers[1].resolve({ status: 202 })
	await flush()
	assert.equal(firstToken.canceled, true)
	assert.equal(h.transfers.length, 2)
})

test('silent upload external cancellation covers signing and settles its promise', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	const controller = new AbortController()
	const promise = h.api.silentUploadFile(file(), {
		signal: controller.signal,
		showFeedback: false,
	})
	const canceled = assert.rejects(promise, { code: 'ERR_CANCELED' })
	controller.abort()
	await canceled
	h.signatures[0].resolve({ data: '/late' })
	await flush()
	assert.equal(h.transfers.length, 0)
	assert.equal(h.loadings[0].closed, true)
})

test('silent transfer account switch rejects and never displays success from late response', async (t) => {
	const h = setupUploads({ uploadType: 'S3' })
	t.after(h.dispose)
	const promise = h.api.silentUploadFile(file(), { path: '/explicit' })
	const canceled = assert.rejects(promise, { code: 'ERR_CANCELED' })
	h.signatures[0].resolve({ data: 'https://upload.example/file' })
	await flush()
	h.storage.loginInfo.isLogin = false
	await canceled
	h.transfers[0].resolve({})
	await flush()
	assert.equal(h.notices.length, 0)
	assert.equal(h.transfers[0].args[2].signal.aborted, true)
	assert.deepEqual(h.fileChanges, [])
})

test('successful hidden-dialog uploads publish their captured source, account and affected parent folders once', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.openUploadDialog()
	h.api.uploadFile({ ...file(), webkitRelativePath: 'folder/sub/same.txt' })
	h.api.visible.value = false
	assert.equal(h.api.clearALlFinishedUploadFile(), 0)
	h.route.key = 'source-two'
	h.route.path = '/changed'
	h.signatures[0].resolve({ data: '/signed-upload' })
	await flush()
	h.transfers[0].resolve({})
	await flush()
	assert.equal(h.api.uploadProgressInfoSorted.value[0].status, 'finished')
	assert.deepEqual(h.fileChanges, ['/original', '/original/folder', '/original/folder/sub'].map((path) => ({
		storageKey: 'source-one', path, accountIdentity: '["10001",1]',
	})))
	assert.equal(h.api.clearALlFinishedUploadFile(), 1)
	assert.equal(h.api.clearALlFinishedUploadFile(), 0)
	assert.equal(h.fileChanges.length, 3)
})

test('silent S3 success emits the explicit original target without reopening the upload dialog', async (t) => {
	const h = setupUploads({ uploadType: 'S3' })
	t.after(h.dispose)
	const saved = h.api.silentUploadFile(file(), { path: '/explicit', showFeedback: false })
	h.signatures[0].resolve({ data: 'https://upload.example/file' })
	await flush()
	h.route.key = 'source-two'
	h.route.path = '/changed'
	h.transfers[0].resolve({})
	assert.deepEqual(plain(await saved), { name: 'same.txt', path: '/explicit', storageKey: 'source-one' })
	assert.equal(h.api.visible.value, false)
	assert.deepEqual(h.fileChanges, [{
		storageKey: 'source-one', path: '/explicit', accountIdentity: '["10001",1]',
	}])
})

test('member S3 upload returned as an app proxy uses authenticated multipart instead of a raw provider PUT', async (t) => {
	const h = setupUploads({ uploadType: 'S3' })
	t.after(h.dispose)
	const saved = h.api.silentUploadFile(file(), { path: '/explicit', showFeedback: false })
	h.signatures[0].resolve({ data: '/file/upload/source-one/explicit/same.txt' })
	await flush()
	assert.equal(h.transfers[0].kind, 'proxy')
	assert.equal(h.transfers[0].args[0].config.containToken, true)
	assert.equal(typeof h.transfers[0].args[0].data.append, 'function')
	h.transfers[0].resolve({})
	await saved
})

test('failed or canceled normal transfers and late old-account success publish no completion event', async (t) => {
	for (const outcome of ['failed', 'canceled', 'account']) {
		const h = setupUploads()
		t.after(h.dispose)
		h.api.uploadFile(file())
		h.signatures[0].resolve({ data: '/signed-upload' })
		await flush()
		if (outcome === 'failed') h.transfers[0].reject(new Error('upload failed'))
		else {
			if (outcome === 'account') h.storage.loginInfo.username = 'other-user'
			else h.api.removeUploadFileByIndex(h.api.uploadProgressInfoSorted.value[0].index)
			h.transfers[0].resolve({})
		}
		await flush()
		assert.deepEqual(h.fileChanges, [])
	}
})

test('normal and silent uploads with missing permission reject before signing', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.folderConfig.permission.upload = undefined
	h.api.uploadFile(file())
	await assert.rejects(h.api.silentUploadFile(file()), /上传权限/)
	assert.equal(h.signatures.length, 0)
})

test('queue watcher survives the first caller component scope disposal', async (t) => {
	const h = setupUploads({ limit: 1 })
	t.after(h.dispose)
	h.api.uploadFile(file())
	h.api.uploadFile(file('queued.txt'))
	h.callerScope.stop()
	h.signatures[0].reject(new Error('failed'))
	await flush()
	assert.equal(h.signatures.length, 2)
})

test('dragged folder batch captures target before directory reads and mkdir awaits', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.folderConfig.metadata.needCreateFolderBeforeUpload = true
	h.api.listenDropFile()
	let returnFile
	const entry = {
		isFile: true,
		fullPath: '/folder/same.txt',
		file: (done) => {
			returnFile = done
		},
	}
	h.drop([{ webkitGetAsEntry: () => entry }])
	h.route.key = 'source-two'
	h.route.path = '/changed'
	h.storage.folderConfig.metadata.needCreateFolderBeforeUpload = false
	returnFile(file())
	await flush()
	assert.equal(h.folders[0].data.storageKey, 'source-one')
	assert.equal(h.folders[0].data.path, '/original')
	assert.equal(h.folders[0].config.uploadContext.rootPath, '/user-one')
	h.folders[0].resolve({})
	await flush()
	assert.equal(h.signatures[0].data.path, '/original/folder')
	assert.equal(h.signatures[0].data.storageKey, 'source-one')
})

test('account change during folder creation cancels it and never schedules files', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.folderConfig.metadata.needCreateFolderBeforeUpload = true
	h.api.listenDropFile()
	h.drop([
		{
			webkitGetAsEntry: () => ({
				isFile: true,
				fullPath: '/one/two/same.txt',
				file: (done) => done(file()),
			}),
		},
	])
	await flush()
	assert.equal(h.folders.length, 1)
	h.storage.loginInfo.username = 'another-user'
	assert.equal(h.folders[0].config.signal.aborted, true)
	assert.ok(h.loadings.every((loading) => loading.closed))
	h.folders[0].resolve({})
	await flush()
	assert.equal(h.folders.length, 1)
	assert.equal(h.signatures.length, 0)
})

test('plain dropped files without new-folder permission are still uploaded', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.folderConfig.permission.newFolder = false
	h.api.listenDropFile()
	h.drop([{ webkitGetAsEntry: () => null, getAsFile: () => file() }])
	await flush()
	assert.equal(h.signatures.length, 1)
})

test('repeated drop-listener initialization never creates duplicate uploads', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.api.listenDropFile()
	h.api.listenDropFile()
	assert.equal(h.listeners.get('drop').size, 1)
	h.drop([{ webkitGetAsEntry: () => null, getAsFile: () => file() }])
	await flush()
	assert.equal(h.signatures.length, 1)
})

test('all directory-entry pages are read instead of silently truncating after first page', async (t) => {
	const h = setupUploads({ limit: 4 })
	t.after(h.dispose)
	h.api.listenDropFile()
	const pages = [
		[
			{
				isFile: true,
				fullPath: '/folder/a.txt',
				file: (done) => done(file('a.txt')),
			},
		],
		[
			{
				isFile: true,
				fullPath: '/folder/b.txt',
				file: (done) => done(file('b.txt')),
			},
		],
		[],
	]
	h.drop([
		{
			webkitGetAsEntry: () => ({
				isDirectory: true,
				fullPath: '/folder',
				createReader: () => ({ readEntries: (done) => done(pages.shift()) }),
			}),
		},
	])
	await flush()
	assert.deepEqual(
		h.signatures.map((request) => request.data.name),
		['a.txt', 'b.txt']
	)
})

test('folder-selector preparation is cancelable before signing and late mkdir does not continue', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.folderConfig.metadata.needCreateFolderBeforeUpload = true
	h.api.uploadFile({ ...file(), webkitRelativePath: 'folder/sub/same.txt' })
	assert.equal(h.folders.length, 1)
	assert.equal(h.signatures.length, 0)
	h.api.cancelUpload(h.api.uploadProgressInfoSorted.value[0])
	assert.equal(h.folders[0].config.signal.aborted, true)
	h.folders[0].resolve({})
	await flush()
	assert.equal(h.folders.length, 1)
	assert.equal(h.signatures.length, 0)
})

test('folder-selector mkdir and signing use the captured source and directory after navigation', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.folderConfig.metadata.needCreateFolderBeforeUpload = true
	h.api.uploadFile({ ...file(), webkitRelativePath: 'folder/sub/same.txt' })
	h.route.key = 'source-two'
	h.route.path = '/changed'
	h.folders[0].resolve({})
	await flush()
	assert.deepEqual(
		h.folders.map((request) => request.data),
		[
			{ storageKey: 'source-one', path: '/original', name: 'folder' },
			{ storageKey: 'source-one', path: '/original/folder', name: 'sub' },
		]
	)
	h.folders[1].resolve({})
	await flush()
	assert.equal(h.signatures[0].data.storageKey, 'source-one')
	assert.equal(h.signatures[0].data.path, '/original/folder/sub')
})

test('new-folder permission is required only for directory preparation, not ordinary files', async (t) => {
	const h = setupUploads()
	t.after(h.dispose)
	h.storage.folderConfig.metadata.needCreateFolderBeforeUpload = true
	h.storage.folderConfig.permission.newFolder = false
	h.api.uploadFile({ ...file(), webkitRelativePath: 'folder/same.txt' })
	await flush()
	assert.equal(h.api.uploadProgressInfoSorted.value[0].status, 'error')
	assert.equal(h.folders.length, 0)
	assert.equal(h.signatures.length, 0)
	h.api.uploadFile(file('plain.txt'))
	assert.equal(h.signatures.length, 1)
})
