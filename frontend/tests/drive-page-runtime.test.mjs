import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as latestRequest from '../src/utils/latest-request.mjs'
import * as headerLoad from '../src/utils/header-storage-load.mjs'
import * as presentation from '../src/utils/drive-presentation.mjs'
import * as automation from '../src/utils/automation-context.mjs'
import { uploadAccountIdentity } from '../src/composables/file/upload-lifecycle.mjs'

const [pageSource, headerSource] = await Promise.all([
	readFile(new URL('../src/pages/file.vue', import.meta.url), 'utf8'),
	readFile(
		new URL(
			'../src/composables/header/useHeaderStorageList.js',
			import.meta.url
		),
		'utf8'
	),
])
const { descriptor } = parse(pageSource)
const compiled = compileScript(descriptor, { id: 'drive-page-runtime' }).content
const plain = (value) => JSON.parse(JSON.stringify(value))
const sourceItem = (key) => ({ key, name: key, type: { key: 'local' } })
const flush = async () => {
	for (let step = 0; step < 5; step++) {
		await vue.nextTick()
		await Promise.resolve()
	}
}
function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
function execute(source, modules, globals) {
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
		.replace('export default', 'return')
	return vm.runInNewContext(`(function(){${executable}\n})()`, {
		...vue,
		modules,
		AbortController,
		...globals,
	})
}

// Run the actual page setup and shared header loader. Directory HTTP behavior
// is separately covered by directory-load-runtime; here its state API is stubbed.
function setupPage({
	key,
	sources = [sourceItem('source-one'), sourceItem('source-two')],
} = {}) {
	const calls = {
		lists: [],
		roots: [],
		files: [],
		retries: 0,
		mkdir: 0,
		pushes: [],
	}
	const notices = [],
		cleanups = [],
		events = []
	const route = vue.reactive({
		path: key ? `/${key}/notes` : '/',
		params: key ? { storageKey: key, fullpath: ['notes'] } : {},
	})
	const loading = vue.ref(false),
		operationBusy = vue.ref(false)
	const directoryLoadError = vue.ref(''),
		directoryLoadState = vue.ref('idle')
	const setDirectoryLoadError = (message) => {
		directoryLoadError.value = message
		directoryLoadState.value = 'error'
	}
	const finishDirectoryLoadState = () => {
		directoryLoadError.value = ''
		directoryLoadState.value = 'ready'
	}
	const beginDirectoryLoadState = () => {
		directoryLoadError.value = ''
		directoryLoadState.value = 'loading'
	}
	const storage = vue.reactive({
		globalConfig: { rootShowStorage: true, siteName: 'Cloud' },
		folderConfig: { permission: {}, readmeText: '' },
		permission: {},
		loginInfo: { isLogin: true, username: 'student-one', tenantId: 1 },
		user: { rootPath: '' },
		updateUserRootPath(value) {
			this.user.rootPath = value
		},
	})
	const files = vue.reactive({
		fileListSource: [],
		directoryPage: null,
		currentStorageSource: {},
		imgMode: false,
		updateFileList(rows) {
			this.fileListSource = rows
		},
		updateCurrentStorageSource(source) {
			this.currentStorageSource = source
		},
	})
	const searchParam = vue.reactive({ nameQuery: '' })
	const data = {
		loading,
		searchParam,
		directoryLoadError,
		directoryLoadState,
		nativeSupported: vue.ref(false),
		nativeActive: vue.ref(false),
		canRetryDirectoryLoad: vue.ref(true),
		beginDirectoryLoadState,
		finishDirectoryLoadState,
		setDirectoryLoadError,
		cancelLoadFile() {
			loading.value = false
			directoryLoadError.value = ''
			directoryLoadState.value = 'idle'
			storage.folderConfig.permission = {}
		},
		async loadFile(...args) {
			calls.files.push({ args: plain(args), key: route.params.storageKey })
			files.updateFileList([
				{ type: 'FILE', name: `${route.params.storageKey}.txt` },
			])
			storage.folderConfig.permission = { download: true }
			loading.value = false
			finishDirectoryLoadState()
		},
		retryDirectoryLoad() {
			calls.retries++
		},
		resetDirectoryQuery() {
			searchParam.nameQuery = ''
		},
		applyDirectorySearch() {},
		setDirectoryMode() {},
	}
	const message = Object.fromEntries(
		['error', 'warning'].map((level) => [
			level,
			(value) => notices.push({ level, value }),
		])
	)
	const network = (kind, params, config) => {
		const reply = deferred()
		calls[kind].push({ params, config, ...reply })
		return reply.promise
	}
	const router = { push: (target) => calls.pushes.push(target) }
	const modules = {
		'~/api/home/home': {
			getSourceListReq: (config) => network('lists', undefined, config),
			loadUserRootPathReq: (storageKey, config) =>
				network('roots', storageKey, config),
		},
		'~/components/messageBox/messageBox': {
			default: { confirm: async () => false },
		},
		'~/utils/header-storage-load.mjs': headerLoad,
		'~/utils/latest-request.mjs': latestRequest,
		'~/utils/drive-presentation.mjs': presentation,
		'~/utils/automation-context.mjs': automation,
		'~/composables/file/upload-lifecycle.mjs': { uploadAccountIdentity },
		'~/utils': { isMobile: vue.ref(false) },
		'element-plus': { ElMessage: message },
		'@heroicons/vue/24/outline': {},
		'~/composables/file/useFileLoading': { default: () => data },
		'~/composables/file/useFileData': { default: () => data },
		'~/stores/file-data': { default: () => files },
		'~/stores/storage-config': { default: () => storage },
		'~/stores/global-config': {
			default: () => ({ studentCloudConfig: { gallery: {} } }),
		},
		'~/composables/useRouterData': {
			default: () => ({
				routeRef: vue.ref(route),
				routerRef: vue.ref(router),
				storageKey: vue.computed(() => route.params.storageKey),
				fullpath: vue.computed(() => route.params.fullpath),
			}),
		},
		'~/composables/file/useFileContextMenu': {
			default: () => ({ showFileMenu() {} }),
		},
		'~/composables/file/useFileLongPressEvent': { default() {} },
		'~/composables/header/useSetting': {
			default: () => ({
				studentCloudSettingCache: vue.ref({ view: { type: 'table' } }),
			}),
		},
		'~/composables/file/useFileUpload': {
			default: () => ({ openUploadDialog() {} }),
		},
		'~/composables/file/useFileOperator': {
			default: () => ({
				operationBusy,
				newFolder: async () => {
					calls.mkdir++
				},
			}),
		},
		'~/composables/file/useFileLink': {
			default: () => ({ generateLinkDialogVisible: vue.ref(false) }),
		},
		'~/composables/file/useFileShare': {
			default: () => ({ setCreateShareDialogRef() {} }),
		},
	}
	for (const name of [
		'~/components/file/readme/CardReadme.vue',
		'~/components/file/readme/DialogReadme.vue',
		'~/components/file/BatchOperatorResult.vue',
		'~/components/file/CreateShareDialog.vue',
		'~/components/file/FileSelectionToolbar.vue',
		'~/components/file/view/TableFileView.vue',
		'~/components/file/view/CardFileView.vue',
	])
		modules[name] = { default: {} }
	const globals = {
		ElMessage: message,
		document: { title: '' },
		useRoute: () => route,
		useRouter: () => router,
		onBeforeMount() {},
		onMounted() {},
		onBeforeUnmount: (callback) => cleanups.push(callback),
		useEventBus: () => ({ on: (callback) => events.push(callback) }),
	}
	const scope = vue.effectScope()
	let header, api
	scope.run(() => {
		header = execute(headerSource, modules, globals)()
		header.storageList.value = sources
		modules['~/composables/header/useHeaderStorageList'] = {
			default: () => header,
		}
		api = execute(compiled, modules, globals).setup({}, { expose() {} })
	})
	return {
		api,
		header,
		route,
		loading,
		operationBusy,
		directoryLoadError,
		directoryLoadState,
		files,
		storage,
		calls,
		notices,
		dispose() {
			for (const callback of cleanups) callback()
			scope.stop()
		},
	}
}

test('root list failure stays visible and root retry clears it only after a successful reload', async (t) => {
	const h = setupPage()
	t.after(h.dispose)
	const first = h.api.refreshDirectory()
	h.calls.lists[0].reject(new Error('offline'))
	await first
	assert.match(h.directoryLoadError.value, /云盘列表加载失败/)
	assert.equal(h.directoryLoadState.value, 'error')
	assert.equal(h.loading.value, false)
	assert.deepEqual(plain(h.files.fileListSource), [])
	const retry = h.api.retryDirectory()
	assert.equal(h.loading.value, true)
	assert.equal(h.directoryLoadError.value, '')
	h.calls.lists[1].resolve({ data: [sourceItem('source-one')] })
	await retry
	assert.equal(h.directoryLoadState.value, 'ready')
	assert.equal(h.loading.value, false)
	assert.equal(h.files.fileListSource[0].type, 'ROOT')
})

test('root-path bootstrap failure retries bootstrap, then future refresh uses only the directory loader', async (t) => {
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	const first = h.api.loadDirectory()
	h.calls.roots[0].reject({ data: { msg: 'root unavailable' } })
	await first
	assert.equal(h.api.navigationFailed.value, true)
	assert.equal(h.directoryLoadError.value, 'root unavailable')
	assert.equal(h.calls.files.length, 0)
	const retry = h.api.retryDirectory()
	h.calls.roots[1].resolve({ data: '/student' })
	await retry
	assert.equal(h.storage.user.rootPath, '/student')
	assert.equal(h.api.navigationFailed.value, false)
	assert.deepEqual(h.calls.files[0].args, [{ init: true }])
	await h.api.refreshDirectory({ type: 'click' })
	assert.equal(h.calls.roots.length, 2)
	assert.deepEqual(h.calls.files[1].args, [])
})

test('navigation ignores late root-path success and rejection after the new directory loads', async (t) => {
	for (const outcome of ['success', 'failure']) {
		const h = setupPage({ key: 'source-one' })
		t.after(h.dispose)
		const previous = h.api.loadDirectory()
		h.route.path = '/source-two/new'
		h.route.params = { storageKey: 'source-two', fullpath: ['new'] }
		assert.equal(h.calls.roots[0].config.signal.aborted, true)
		h.calls.roots[1].resolve({ data: '/new-root' })
		await flush()
		if (outcome === 'success') h.calls.roots[0].resolve({ data: '/old-root' })
		else h.calls.roots[0].reject(new Error('old root failed'))
		await previous
		assert.equal(h.storage.user.rootPath, '/new-root')
		assert.equal(h.calls.files.length, 1)
		assert.equal(h.calls.files[0].key, 'source-two')
		assert.equal(h.directoryLoadError.value, '')
		assert.equal(h.notices.length, 0)
	}
})

test('refresh, retry and mkdir cannot start during loading or an active file operation', async (t) => {
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	for (const ref of [h.loading, h.operationBusy]) {
		ref.value = true
		await h.api.refreshDirectory()
		await h.api.retryDirectory()
		await h.api.createDirectory()
		ref.value = false
	}
	assert.equal(h.calls.files.length, 0)
	assert.equal(h.calls.roots.length, 0)
	assert.equal(h.calls.lists.length, 0)
	assert.equal(h.calls.retries, 0)
	assert.equal(h.calls.mkdir, 0)
})

test('same-route header/bootstrap calls share one promise and preserve real success/rejection contracts', async (t) => {
	const h = setupPage()
	t.after(h.dispose)
	const first = h.header.loadStorageSourceList()
	const parallel = h.header.loadStorageSourceList()
	assert.equal(first, parallel)
	assert.equal(h.calls.lists.length, 1)
	assert.equal(h.calls.lists[0].config.timeout, 30000)
	assert.equal(h.calls.lists[0].config.showDefaultMsg, false)
	const response = { data: [] }
	h.calls.lists[0].resolve(response)
	assert.equal(await first, response)
	const rejected = h.header.loadStorageSourceList()
	const original = new Error('real network failure')
	h.calls.lists[1].reject(original)
	await assert.rejects(rejected, (error) => error === original)
})

test('a direct directory bootstrap shares an in-flight header request and still loads its user root and files', async (t) => {
	const h = setupPage({ key: 'source-one', sources: [] })
	t.after(h.dispose)
	const header = h.header.loadStorageSourceList()
	const page = h.api.loadDirectory()
	assert.equal(h.calls.lists.length, 1)
	assert.equal(h.calls.lists[0].config.signal.aborted, false)
	h.calls.lists[0].resolve({ data: [sourceItem('source-one')] })
	await header
	await flush()
	assert.equal(h.calls.roots.length, 1)
	h.calls.roots[0].resolve({ data: '/student' })
	await page
	assert.equal(h.storage.user.rootPath, '/student')
	assert.equal(h.calls.files.length, 1)
	assert.equal(h.directoryLoadState.value, 'ready')
	assert.equal(h.directoryLoadError.value, '')
})

test('late root refresh success/failure never overwrites a new directory, permissions or storage source', async (t) => {
	for (const outcome of ['success', 'failure']) {
		const h = setupPage()
		t.after(h.dispose)
		const oldRefresh = h.api.refreshDirectory()
		h.route.path = '/source-two/new'
		h.route.params = { storageKey: 'source-two', fullpath: ['new'] }
		h.calls.roots[0].resolve({ data: '/new-root' })
		await flush()
		const source = plain(h.files.currentStorageSource)
		if (outcome === 'success')
			h.calls.lists[0].resolve({ data: [sourceItem('old-source')] })
		else h.calls.lists[0].reject(new Error('stale offline'))
		await oldRefresh
		assert.equal(h.calls.lists[0].config.signal.aborted, true)
		assert.equal(h.files.fileListSource[0].name, 'source-two.txt')
		assert.deepEqual(plain(h.storage.folderConfig.permission), {
			download: true,
		})
		assert.deepEqual(plain(h.files.currentStorageSource), source)
		assert.equal(h.directoryLoadState.value, 'ready')
		assert.equal(h.directoryLoadError.value, '')
		assert.equal(h.loading.value, false)
		assert.deepEqual(h.calls.pushes, [])
	}
})

test('leaving and returning to the same route creates a fresh list request and cancels the old reply', async (t) => {
	const h = setupPage()
	t.after(h.dispose)
	const old = h.header.loadStorageSourceList()
	const canceled = assert.rejects(old, { code: 'ERR_CANCELED' })
	h.route.path = '/learning'
	h.route.path = '/'
	const current = h.header.loadStorageSourceList()
	assert.equal(h.calls.lists.length, 2)
	h.calls.lists[1].resolve({ data: [sourceItem('source-two')] })
	await current
	h.calls.lists[0].resolve({ data: [sourceItem('old-source')] })
	await canceled
	assert.equal(h.header.storageList.value[0].key, 'source-two')
})

test('same-route account, workspace and logout changes cancel old list success/failure without touching current source or permissions', async (t) => {
	for (const change of [
		(h) => {
			h.storage.loginInfo.username = 'student-two'
		},
		(h) => {
			h.storage.loginInfo.tenantId = 2
		},
		(h) => {
			h.storage.loginInfo.isLogin = false
		},
	]) {
		for (const outcome of ['success', 'failure']) {
			const h = setupPage()
			t.after(h.dispose)
			const old = h.header.loadStorageSourceList()
			const canceled = assert.rejects(old, { code: 'ERR_CANCELED' })
			change(h)
			assert.equal(h.route.path, '/')
			assert.equal(h.calls.lists[0].config.signal.aborted, true)
			const current = h.header.loadStorageSourceList()
			assert.notEqual(old, current)
			h.calls.lists[1].resolve({ data: [sourceItem('source-two')] })
			await current
			h.files.currentStorageSource = sourceItem('source-two')
			h.storage.folderConfig.permission = { upload: true, download: true }
			h.storage.user.rootPath = '/current-student'
			if (outcome === 'success')
				h.calls.lists[0].resolve({ data: [sourceItem('old-account-source')] })
			else h.calls.lists[0].reject(new Error('old-account offline'))
			await canceled
			assert.equal(h.header.storageList.value[0].key, 'source-two')
			assert.equal(h.files.fileListSource[0].name, 'source-two')
			assert.equal(h.files.currentStorageSource.key, 'source-two')
			assert.deepEqual(plain(h.storage.folderConfig.permission), {
				upload: true,
				download: true,
			})
			assert.equal(h.storage.user.rootPath, '/current-student')
			assert.equal(h.directoryLoadError.value, '')
			assert.deepEqual(h.notices, [])
			assert.deepEqual(h.calls.pushes, [])
		}
	}
})

test('same-account nickname refresh preserves and shares the pending storage-list request', async (t) => {
	const h = setupPage()
	t.after(h.dispose)
	const first = h.header.loadStorageSourceList()
	h.storage.loginInfo = { ...h.storage.loginInfo, nickname: 'Updated nickname' }
	assert.equal(h.calls.lists[0].config.signal.aborted, false)
	assert.equal(h.header.loadStorageSourceList(), first)
	assert.equal(h.calls.lists.length, 1)
	h.calls.lists[0].resolve({ data: [sourceItem('source-one')] })
	await first
	assert.equal(h.header.storageList.value[0].key, 'source-one')
})
