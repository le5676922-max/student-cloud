import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'path-browserify'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import * as directoryPage from '../src/utils/directory-page.mjs'
import * as latestRequest from '../src/utils/latest-request.mjs'

// Execute the real shared composables with Vue refs and synthetic network replies.
const [dataSource, loadingSource] = await Promise.all([
	readFile(new URL('../src/composables/file/useFileData.js', import.meta.url), 'utf8'),
	readFile(new URL('../src/composables/file/useFileLoading.js', import.meta.url), 'utf8'),
])
const plain = (value) => JSON.parse(JSON.stringify(value))
function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
const fileReply = (name = 'current.txt', extra = {}) => ({
	data: {
		files: [{ name, type: 'FILE', path: '/courses' }],
		...extra,
	},
})
const configReply = (permission = { preview: true, download: true }) => ({
	data: { permission, readmeText: 'Current folder' },
})
const flush = async () => {
	for (let step = 0; step < 4; step++) {
		await vue.nextTick()
		await Promise.resolve()
	}
}

function runFactory(source, modules, extra = {}) {
	const executable = source
		.replace(
			/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
			(_whole, bindings, name) =>
				bindings.trim().startsWith('{')
					? `const ${bindings.replace(/\s+as\s+/g, ': ')} = modules[${JSON.stringify(name)}];`
					: `const ${bindings.trim()} = modules[${JSON.stringify(name)}].default;`
		)
		.replace('export default', 'return')
	return vm.runInNewContext(`(function(){${executable}\n})()`, {
		...vue,
		modules,
		AbortController,
		...extra,
	})
}

function setupDirectory({ type = 'local' } = {}) {
	const calls = { files: [], config: [], passwords: [], prompts: [], pushes: [] }
	const notices = []
	const route = vue.reactive({ storageKey: 'source-one', path: '/courses' })
	const selection = vue.ref([{ name: 'old-selection.txt' }])
	const storage = vue.reactive({
		globalConfig: {
			siteName: 'Cloud',
			maxShowSize: 100,
			defaultSortField: 'name',
			defaultSortOrder: 'asc',
			rootShowStorage: true,
		},
		folderConfig: {
			permission: { upload: true, preview: true },
			readmeText: 'Previous folder',
		},
		updateFolderConfig(data) {
			this.folderConfig = data
		},
	})
	const files = vue.reactive({
		fileListSource: [{ name: 'previous.txt', type: 'FILE' }],
		directoryPage: null,
		updateFileList(rows, response) {
			this.directoryPage = directoryPage.directoryPageMetadata(response)
			this.fileListSource = rows
		},
		updateOldStorageKey(key) {
			this.oldStorageKey = key
		},
		updateCurrentClickRow(row) {
			this.currentClickRow = row
		},
	})
	const globalConfig = {
		studentCloudConfig: { skeleton: { size: 2, enable: true, show: 'always' } },
	}
	const loadingFactory = runFactory(loadingSource, {
		'~/stores/global-config': { default: () => globalConfig },
	})
	const network = (kind, params, config) => {
		const response = deferred()
		calls[kind].push({ params: plain(params), config, ...response })
		return response.promise
	}
	const modules = {
		'element-plus': {
			ElMessage: Object.fromEntries(
				['error', 'warning', 'info'].map((level) => [
					level,
					(message) => notices.push({ level, message }),
				])
			),
		},
		'~/utils': { concatPathAndEncodeAll: (...parts) => parts.join('/') },
		'~/utils/directory-page.mjs': directoryPage,
		'~/utils/latest-request.mjs': latestRequest,
		'path-browserify': { default: path },
		'~/composables/file/useFileContextMenu': {
			default: () => ({
				contextMenuTargetFile: vue.ref(false),
				contextMenuTargetBlank: vue.ref(false),
			}),
		},
		'~/api/home/home': {
			loadFileListReq: (params, config) => network('files', params, config),
			loadStorageConfigReq: (params, config) => network('config', params, config),
		},
		'~/composables/useRouterData': {
			default: () => ({
				storageKey: vue.computed(() => route.storageKey),
				currentPath: vue.computed(() => route.path),
				fullpath: vue.computed(() => route.path === '/' ? undefined : route.path.slice(1).split('/')),
				routerRef: vue.ref({ push: (target) => calls.pushes.push(target) }),
			}),
		},
		'~/composables/file/useFilePwd': {
			default: () => ({
				getPathPwd: () => 'synthetic-password',
				putPathPwd: (...args) => calls.passwords.push(args),
				popPassword: (confirm, cancel) => calls.prompts.push({ confirm, cancel }),
			}),
		},
		'~/composables/header/useHeaderStorageList': {
			default: () => ({
				findStorageByKey: () => ({ type: { key: type } }),
				storageListAsFileList: vue.ref([{ name: 'Cloud', type: 'ROOT' }]),
			}),
		},
		'~/composables/file/useFileLoading': { default: loadingFactory },
		'~/stores/file-data': { default: () => files },
		'~/stores/storage-config': { default: () => storage },
		'~/stores/global-config': { default: () => globalConfig },
		'~/composables/file/useFilePreview': { default: () => ({}) },
		'~/composables/file/useFileOperator': { default: () => ({}) },
		'~/composables/file/useFileSelect': {
			default: () => ({ selectRows: selection, clearSelection: () => { selection.value = [] } }),
		},
	}
	const factory = runFactory(dataSource, modules, {
		useTitle: () => vue.ref('Cloud'),
		constant: {
			responseCode: {
				DIRECTORY_CHANGED: 410,
				INVALID_PASSWORD: 401,
				REQUIRED_PASSWORD: 402,
			},
		},
	})
	return { api: factory(), factory, loadingFactory, route, storage, files, selection, calls, notices }
}

function reply(directory, index, name = 'current.txt') {
	directory.calls.files[index].resolve(fileReply(name))
	directory.calls.config[index].resolve(configReply())
}

test('config failure preserves current rows, exposes persistent shared error and retries the captured request', async () => {
	const directory = setupDirectory()
	const { api, files, storage, calls, selection } = directory
	const loading = api.loadFile({ nameQuery: 'course', orderBy: 'size', orderDirection: 'desc' })
	assert.equal(api.directoryLoadState.value, 'loading')
	assert.equal(api.loading.value, true)
	assert.deepEqual(plain(files.fileListSource), [])
	assert.deepEqual(plain(storage.folderConfig.permission), {})
	assert.deepEqual(plain(selection.value), [])
	calls.files[0].resolve(fileReply('course.txt'))
	await flush()
	assert.equal(files.fileListSource.at(-1).name, 'course.txt')
	assert.equal(api.loading.value, false)
	assert.equal(api.directoryLoadState.value, 'loading')
	assert.deepEqual(plain(storage.folderConfig.permission), {})
	calls.config[0].reject({ code: 'ETIMEDOUT' })
	await loading
	assert.equal(api.directoryLoadState.value, 'error')
	assert.match(api.directoryLoadError.value, /文件列表已载入.*目录权限和说明未能加载.*超时/)
	assert.equal(api.canRetryDirectoryLoad.value, true)
	assert.equal(api.directoryNameQuery.value, 'course')
	assert.equal(files.fileListSource.at(-1).name, 'course.txt')
	assert.equal(directory.factory().directoryLoadError, api.directoryLoadError)
	assert.equal(directory.loadingFactory().directoryLoadState, api.directoryLoadState)
	api.searchParam.nameQuery = 'unsubmitted draft'
	assert.equal(api.directoryNameQuery.value, 'course')
	api.searchParam.orderBy = 'name'
	const retry = directory.factory().retryDirectoryLoad()
	assert.equal(api.directoryLoadError.value, '')
	assert.equal(api.directoryLoadState.value, 'loading')
	assert.equal(api.canRetryDirectoryLoad.value, false)
	assert.equal(api.directoryNameQuery.value, 'course')
	assert.deepEqual(calls.files[1].params, calls.files[0].params)
	assert.equal(files.fileListSource.at(-1).name, 'course.txt')
	reply(directory, 1)
	await retry
	assert.equal(api.directoryLoadState.value, 'ready')
	assert.equal(api.directoryLoadError.value, '')
	assert.equal(api.canRetryDirectoryLoad.value, false)
	assert.equal(storage.folderConfig.permission.preview, true)
	assert.equal(api.directoryNameQuery.value, 'course')
})

test('failed cursor page keeps the visible page and retry uses its query and cursor instead of input drafts', async () => {
	const directory = setupDirectory()
	const { api, calls, files } = directory
	const initial = api.loadFile({ nameQuery: 'submitted', orderBy: 'size', orderDirection: 'desc' })
	reply(directory, 0, 'visible-page.txt')
	await initial
	api.searchParam.nameQuery = 'draft'
	assert.equal(api.directoryNameQuery.value, 'submitted')
	const nextPage = api.loadPage('next-page-cursor')
	assert.equal(files.fileListSource.at(-1).name, 'visible-page.txt')
	calls.files[1].reject({ code: 'ECONNABORTED' })
	calls.config[1].resolve(configReply())
	await nextPage
	assert.equal(api.directoryLoadError.value, '目录加载超时，请重试')
	assert.equal(api.canRetryDirectoryLoad.value, true)
	assert.equal(api.directoryNameQuery.value, 'submitted')
	api.searchParam.nameQuery = 'changed again'
	const retry = api.retryDirectoryLoad()
	const duplicate = api.retryDirectoryLoad()
	assert.equal(duplicate, undefined)
	assert.equal(calls.files.length, 3)
	assert.equal(calls.files[2].params.pageCursor, 'next-page-cursor')
	assert.equal(calls.files[2].params.nameQuery, 'submitted')
	assert.equal(calls.files[2].params.refreshMetadata, false)
	assert.deepEqual(calls.files[2].params, calls.files[1].params)
	reply(directory, 2, 'next-page.txt')
	await retry
	assert.equal(files.fileListSource.at(-1).name, 'next-page.txt')
	assert.equal(api.directoryLoadState.value, 'ready')
	assert.equal(api.directoryNameQuery.value, 'submitted')
})

test('empty-state query changes only after successful files, survives failed refresh and resets on navigation or cancel', async () => {
	const directory = setupDirectory()
	const { api, calls, route } = directory
	const initial = api.loadFile()
	calls.files[0].resolve(fileReply('', { files: [] }))
	calls.config[0].resolve(configReply())
	await initial
	assert.equal(api.directoryNameQuery.value, '')
	assert.equal(directory.factory().directoryNameQuery, api.directoryNameQuery)
	api.searchParam.nameQuery = '  submitted filter  '
	assert.equal(api.directoryNameQuery.value, '')
	const searched = api.applyDirectorySearch()
	assert.equal(api.directoryNameQuery.value, '')
	calls.files[1].reject(new Error('filter unavailable'))
	calls.config[1].resolve(configReply())
	await searched
	assert.equal(api.directoryNameQuery.value, '')
	api.searchParam.nameQuery = 'different draft'
	const retry = api.retryDirectoryLoad()
	assert.equal(calls.files[2].params.nameQuery, 'submitted filter')
	assert.equal(api.directoryNameQuery.value, '')
	calls.files[2].resolve(fileReply('', { files: [] }))
	await flush()
	assert.equal(api.directoryNameQuery.value, 'submitted filter')
	calls.config[2].resolve(configReply())
	await retry
	const refresh = api.loadFile()
	assert.equal(api.directoryNameQuery.value, 'submitted filter')
	calls.files[3].reject(new Error('refresh unavailable'))
	calls.config[3].resolve(configReply())
	await refresh
	assert.equal(api.directoryNameQuery.value, 'submitted filter')
	api.resetDirectoryQuery()
	assert.equal(api.directoryNameQuery.value, '')
	const cleared = api.loadFile()
	reply(directory, 4)
	await cleared
	assert.equal(api.directoryNameQuery.value, '')
	const filtered = api.loadFile({ nameQuery: 'old-directory' })
	reply(directory, 5)
	await filtered
	assert.equal(api.directoryNameQuery.value, 'old-directory')
	route.path = '/new-directory'
	const navigated = api.loadFile()
	assert.equal(api.directoryNameQuery.value, '')
	reply(directory, 6)
	await navigated
	const filtering = api.loadFile({ nameQuery: 'cancelled-filter' })
	api.cancelLoadFile()
	assert.equal(api.directoryNameQuery.value, '')
	calls.files[7].resolve(fileReply())
	calls.config[7].resolve(configReply())
	await filtering
	assert.equal(api.directoryNameQuery.value, '')
})

for (const staleStage of ['files', 'config']) {
	test(`a late old ${staleStage} failure cannot overwrite a new directory state or grant`, async () => {
		const directory = setupDirectory()
		const { api, calls, route, files, storage, notices } = directory
		const old = api.loadFile()
		if (staleStage === 'config') {
			calls.files[0].resolve(fileReply('old.txt'))
			await flush()
		}
		route.storageKey = 'source-two'
		route.path = '/different'
		const current = api.loadFile()
		assert.equal(calls.files[0].config.signal.aborted, true)
		assert.equal(api.directoryLoadError.value, '')
		assert.deepEqual(plain(files.fileListSource), [])
		calls.files[1].resolve(fileReply('new.txt'))
		await flush()
		calls[staleStage][0].reject(new Error('old failure'))
		if (staleStage === 'files') calls.config[0].resolve(configReply({ upload: true }))
		await old
		assert.equal(api.directoryLoadState.value, 'loading')
		assert.equal(api.directoryLoadError.value, '')
		assert.equal(api.canRetryDirectoryLoad.value, false)
		assert.equal(files.fileListSource.at(-1).name, 'new.txt')
		assert.deepEqual(plain(storage.folderConfig.permission), {})
		assert.deepEqual(notices, [])
		calls.config[1].resolve(configReply({ download: true }))
		await current
		assert.equal(api.directoryLoadState.value, 'ready')
		assert.deepEqual(plain(storage.folderConfig.permission), { download: true })
	})
}

test('changing route disables old retries and cancellation clears failure before a new request', async () => {
	const directory = setupDirectory()
	const { api, calls, route } = directory
	const load = api.loadFile()
	calls.files[0].reject({ response: { data: { msg: 'Network unavailable' } } })
	calls.config[0].resolve(configReply())
	await load
	assert.equal(api.directoryLoadError.value, 'Network unavailable')
	assert.equal(api.canRetryDirectoryLoad.value, true)
	route.path = '/other'
	assert.equal(api.canRetryDirectoryLoad.value, false)
	assert.equal(api.retryDirectoryLoad(), undefined)
	api.cancelLoadFile()
	assert.equal(api.directoryLoadError.value, '')
	assert.equal(api.directoryLoadState.value, 'idle')
	assert.equal(api.retryDirectoryLoad(), undefined)
	assert.equal(calls.files.length, 1)
	const next = api.loadFile()
	api.cancelLoadFile()
	calls.files[1].reject(new Error('cancelled request failed later'))
	calls.config[1].reject(new Error('cancelled config failed later'))
	await next
	assert.equal(api.loading.value, false)
	assert.equal(api.directoryLoadState.value, 'idle')
	assert.equal(api.directoryLoadError.value, '')
})

for (const code of [401, 402]) {
	test(`password response ${code} opens its prompt without a normal retry error and late callbacks stay scoped`, async () => {
		const directory = setupDirectory()
		const { api, calls, route } = directory
		const load = api.loadFile()
		calls.files[0].reject({ data: { code } })
		calls.config[0].resolve(configReply())
		await load
		assert.equal(api.directoryLoadState.value, 'password')
		assert.equal(api.directoryLoadError.value, '')
		assert.equal(api.canRetryDirectoryLoad.value, false)
		assert.equal(calls.prompts.length, 1)
		const prompt = calls.prompts[0]
		route.path = '/new'
		const next = api.loadFile()
		prompt.confirm('old-password', true)
		prompt.cancel()
		assert.equal(calls.files.length, 2)
		assert.equal(calls.pushes.length, 0)
		assert.equal(api.directoryLoadState.value, 'loading')
		reply(directory, 1)
		await next
	})
}

test('confirming or cancelling the current password prompt clears its waiting state safely', async () => {
	for (const action of ['confirm', 'cancel']) {
		const directory = setupDirectory()
		const { api, calls } = directory
		const load = api.loadFile()
		calls.files[0].reject({ data: { code: 402 } })
		calls.config[0].resolve(configReply())
		await load
		if (action === 'confirm') {
			calls.prompts[0].confirm('confirmed-password', true)
			assert.equal(api.directoryLoadState.value, 'loading')
			assert.equal(calls.files[1].params.password, 'confirmed-password')
			assert.equal(calls.files[1].params.rememberPassword, true)
			reply(directory, 1)
			await flush()
			assert.equal(api.directoryLoadState.value, 'ready')
		} else {
			calls.prompts[0].cancel()
			assert.equal(api.directoryLoadState.value, 'idle')
			assert.deepEqual(calls.pushes, ['/source-one/'])
			assert.equal(calls.files.length, 1)
		}
		assert.equal(api.directoryLoadError.value, '')
		assert.equal(api.canRetryDirectoryLoad.value, false)
	}
})

test('a current transport cancellation leaves no failure or retry state', async () => {
	const { api, calls } = setupDirectory()
	const load = api.loadFile()
	calls.files[0].reject({ code: 'ERR_CANCELED' })
	calls.config[0].resolve(configReply())
	await load
	assert.equal(api.loading.value, false)
	assert.equal(api.directoryLoadState.value, 'idle')
	assert.equal(api.directoryLoadError.value, '')
	assert.equal(api.canRetryDirectoryLoad.value, false)
})

test('directory-change cursor recovery reloads the first page and a recovery failure remains retryable', async () => {
	const directory = setupDirectory({ type: 's3' })
	const { api, calls, files } = directory
	const initial = api.loadFile()
	reply(directory, 0)
	await initial
	const nextPage = api.loadPage('stale-cursor')
	calls.files[1].reject({ data: { code: 410 } })
	calls.config[1].resolve(configReply())
	await flush()
	assert.equal(calls.files.length, 3)
	assert.equal(calls.files[2].params.pageCursor, undefined)
	assert.equal(calls.files[2].params.refreshMetadata, true)
	assert.equal(api.directoryLoadState.value, 'loading')
	assert.deepEqual(plain(files.fileListSource), [])
	calls.files[2].reject(new Error('recovery network failure'))
	calls.config[2].resolve(configReply())
	await nextPage
	assert.equal(api.directoryLoadState.value, 'error')
	assert.equal(api.canRetryDirectoryLoad.value, true)
	const retry = api.retryDirectoryLoad()
	assert.equal(calls.files[3].params.pageCursor, undefined)
	assert.equal(calls.files[3].params.nativePagination, true)
	reply(directory, 3, 'fresh-first-page.txt')
	await retry
	assert.equal(api.directoryLoadState.value, 'ready')
	assert.equal(api.directoryLoadError.value, '')
})

test('the shared state helpers let navigation report and clear a bootstrap failure', () => {
	const { api, loadingFactory } = setupDirectory()
	const state = loadingFactory()
	api.beginDirectoryLoadState()
	assert.equal(state.directoryLoadState.value, 'loading')
	state.setDirectoryLoadError('Root path unavailable')
	assert.equal(api.directoryLoadState.value, 'error')
	assert.equal(api.directoryLoadError.value, 'Root path unavailable')
	assert.equal(api.canRetryDirectoryLoad.value, false)
	api.beginDirectoryLoadState()
	assert.equal(state.directoryLoadError.value, '')
	api.finishDirectoryLoadState()
	assert.equal(state.directoryLoadState.value, 'ready')
	api.cancelLoadFile()
	assert.equal(state.directoryLoadState.value, 'idle')
})
