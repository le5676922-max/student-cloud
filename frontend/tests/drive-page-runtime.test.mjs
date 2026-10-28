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
import * as fileChanges from '../src/utils/file-change-refresh.mjs'
import { driveSelectionActions } from '../src/utils/drive-selection-actions.mjs'
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

test('the recycle shortcut is hidden for directories whose backend does not offer recycling', () => {
	const find = (node) => {
		if (
			node.tag === 'router-link' &&
			node.props?.some(
				(prop) => prop.name === 'to' && prop.value?.content === '/recycle-bin'
			)
		)
			return node
		for (const child of node.children || []) {
			const result = find(child)
			if (result) return result
		}
	}
	const shortcut = find(descriptor.template.ast)
	assert.ok(shortcut)
	const condition = shortcut.props.find((prop) => prop.name === 'if').exp
		.content
	for (const [accountModel, recycleBin, visible] of [
		['independent', false, false],
		['independent', true, true],
		['independent', undefined, true],
		['legacy', true, false],
	]) {
		assert.equal(
			vm.runInNewContext(condition, {
				storageConfigStore: {
					globalConfig: { accountModel },
					folderConfig: { metadata: { recycleBin } },
				},
			}),
			visible
		)
	}
})
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
	const timers = new Map()
	let timerIndex = 0
	const route = vue.reactive({
		path: key ? `/${key}/notes` : '/',
		params: key ? { storageKey: key, fullpath: ['notes'] } : {},
	})
	const loading = vue.ref(false),
		operationBusy = vue.ref(false)
	const selectRows = vue.ref([])
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
		'~/utils/drive-selection-actions.mjs': { driveSelectionActions },
		'~/composables/file/useFileSelect': { default: () => ({ selectRows }) },
		'~/utils/file-change-refresh.mjs': {
			...fileChanges,
			createFileChangeRefresh: (options) =>
				fileChanges.createFileChangeRefresh({
					...options,
					schedule: (callback) => {
						const id = ++timerIndex
						timers.set(id, callback)
						return id
					},
					unschedule: (id) => timers.delete(id),
				}),
		},
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
			default: () => ({
				setCreateShareDialogRef() {},
				setControlledShareDialogRef() {},
			}),
		},
	}
	for (const name of [
		'~/components/file/readme/CardReadme.vue',
		'~/components/file/readme/DialogReadme.vue',
		'~/components/file/BatchOperatorResult.vue',
		'~/components/file/CreateShareDialog.vue',
		'~/components/file/ControlledShareDialog.vue',
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
		selectRows,
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
		emitFileChange(change) {
			for (const callback of events) callback(change)
		},
		timers,
		tick() {
			const callbacks = [...timers.values()]
			timers.clear()
			for (const callback of callbacks) callback()
		},
		dispose() {
			for (const callback of cleanups) callback()
			scope.stop()
		},
	}
}

function templateChain(
	predicate,
	node = descriptor.template.ast,
	parents = []
) {
	if (predicate(node)) return [...parents, node]
	for (const child of node.children || []) {
		const found = templateChain(predicate, child, [...parents, node])
		if (found) return found
	}
}

test('selection actions share the existing browse toolbar rather than inserting a new row above either file view', () => {
	const selection = templateChain((node) => node.tag === 'FileSelectionToolbar')
	const browsing = templateChain((node) =>
		node.props?.some(
			(prop) =>
				prop.name === 'class' && prop.value?.content === 'cloud-file-toolbar'
		)
	)
	assert.ok(selection)
	assert.ok(browsing)
	let common
	for (
		let index = 0;
		index < Math.min(selection.length, browsing.length);
		index++
	) {
		if (selection[index] !== browsing[index]) break
		common = selection[index]
	}
	assert.ok(common)
	assert.equal(
		Boolean(
			templateChain(
				(node) => ['table-file-view', 'card-file-view'].includes(node.tag),
				common
			)
		),
		false,
		'selection actions must not add a normal-flow row before the file views'
	)
})

test('selection mode disables hidden browse controls and clears for synthetic or empty rows', (t) => {
	const h = setupPage()
	t.after(h.dispose)
	const browsing = templateChain((node) =>
		node.props?.some(
			(prop) =>
				prop.name === 'class' && prop.value?.content === 'cloud-file-toolbar'
		)
	).at(-1)
	const binding = (name) =>
		browsing.props.find(
			(prop) => prop.name === 'bind' && prop.arg?.content === name
		)?.exp?.content
	assert.ok(
		binding('inert'),
		'hidden browse controls must leave keyboard navigation'
	)
	assert.ok(
		binding('aria-hidden'),
		'hidden browse controls must leave the accessibility tree'
	)
	for (const [rows, active] of [
		[[], false],
		[[{ type: 'FOLDER', name: 'course' }], true],
		[[{ type: 'FILE', name: 'note.txt' }], true],
		[[{ type: 'ROOT', name: 'drive' }], false],
		[[{ type: 'BACK', name: '..' }], false],
		[[null], false],
	]) {
		h.selectRows.value = rows
		assert.equal(h.api.selectionActive.value, active)
		assert.equal(
			Boolean(
				vm.runInNewContext(binding('inert'), { selectionActive: active })
			),
			active
		)
		assert.equal(
			Boolean(
				vm.runInNewContext(binding('aria-hidden'), { selectionActive: active })
			),
			active
		)
	}
})

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

test('route-driven storage selection preserves nested directories across Back and Forward without a second navigation', async (t) => {
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	const bootstrap = h.header.loadStorageSourceList()
	h.calls.lists[0].resolve({
		data: [sourceItem('source-one'), sourceItem('source-two')],
	})
	await bootstrap
	await flush()
	assert.equal(h.header.currentStorageKey.value, 'source-one')
	assert.deepEqual(h.calls.pushes, [])

	for (const [key, folder] of [
		['source-two', 'reports'],
		['source-one', 'notes'],
		['source-two', 'reports'],
	]) {
		h.route.path = `/${key}/${folder}`
		h.route.params = { storageKey: key, fullpath: [folder] }
		await flush()
		assert.equal(h.header.currentStorageKey.value, key)
		assert.equal(h.files.currentStorageSource.key, key)
		assert.deepEqual(h.calls.pushes, [])
		h.calls.roots.at(-1).resolve({ data: '/student' })
		await flush()
		assert.equal(h.calls.files.at(-1).key, key)
		assert.equal(h.loading.value, false)
	}
})

test('an explicit storage dropdown selection still navigates to the newly selected cloud root', async (t) => {
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	const bootstrap = h.header.loadStorageSourceList()
	h.calls.lists[0].resolve({
		data: [sourceItem('source-one'), sourceItem('source-two')],
	})
	await bootstrap
	await flush()
	h.header.currentStorageKey.value = 'source-two'
	await flush()
	assert.deepEqual(h.calls.pushes, ['/source-two'])
	assert.equal(h.files.currentStorageSource.key, 'source-two')
})

test('same-route header/bootstrap calls share one promise and preserve real success/rejection contracts', async (t) => {
	const h = setupPage()
	t.after(h.dispose)
	const first = h.header.loadStorageSourceList()
	const parallel = h.header.loadStorageSourceList()
	assert.equal(first, parallel)
	assert.equal(h.calls.lists.length, 1)
	assert.equal(h.calls.lists[0].config.timeout, 10000)
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

test('real page coalesces successful upload and Agent changes for the current directory only', async (t) => {
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	const change = {
		storageKey: 'source-one',
		path: '/notes',
		accountIdentity: uploadAccountIdentity(h.storage.loginInfo),
	}
	h.emitFileChange({ ...change, storageKey: 'source-two' })
	h.emitFileChange({ ...change, path: '/elsewhere' })
	h.emitFileChange({ ...change, accountIdentity: '["old-account",1]' })
	assert.equal(h.timers.size, 0)
	h.emitFileChange(change)
	h.emitFileChange({ storageKey: 'source-one', path: '/notes/' })
	h.emitFileChange(change)
	assert.equal(h.calls.files.length, 0)
	assert.equal(h.timers.size, 1)
	h.tick()
	await flush()
	assert.equal(h.calls.files.length, 1)
	assert.equal(h.calls.files[0].key, 'source-one')
	assert.equal(h.timers.size, 0)
})

test('real page waits for loading and operation dialogs before refreshing a completed upload', async (t) => {
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	h.loading.value = true
	h.emitFileChange({ storageKey: 'source-one', path: '/notes' })
	h.tick()
	assert.equal(h.calls.files.length, 0)
	h.loading.value = false
	h.operationBusy.value = true
	h.tick()
	assert.equal(h.calls.files.length, 0)
	h.operationBusy.value = false
	h.tick()
	await flush()
	assert.equal(h.calls.files.length, 1)
})

test('real page cancels queued changes on navigation away and back without refreshing the next directory', async (t) => {
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	h.emitFileChange({ storageKey: 'source-one', path: '/notes' })
	const stale = [...h.timers.values()][0]
	h.route.path = '/source-two/different'
	h.route.path = '/source-one/notes'
	assert.equal(h.timers.size, 0)
	stale()
	h.tick()
	await flush()
	assert.equal(h.calls.files.length, 0)
})

test('real page cancels queued changes on account/workspace/logout changes but retains same-account metadata refresh', async (t) => {
	for (const change of [
		(h) => {
			h.storage.loginInfo.username = 'other'
		},
		(h) => {
			h.storage.loginInfo.tenantId = 2
		},
		(h) => {
			h.storage.loginInfo.isLogin = false
		},
	]) {
		const h = setupPage({ key: 'source-one' })
		t.after(h.dispose)
		h.emitFileChange({ storageKey: 'source-one', path: '/notes' })
		change(h)
		assert.equal(h.timers.size, 0)
		h.tick()
		await flush()
		assert.equal(h.calls.files.length, 0)
	}
	const h = setupPage({ key: 'source-one' })
	t.after(h.dispose)
	h.emitFileChange({ storageKey: 'source-one', path: '/notes' })
	h.storage.loginInfo = { ...h.storage.loginInfo, nickname: 'Updated name' }
	assert.equal(h.timers.size, 1)
	h.tick()
	await flush()
	assert.equal(h.calls.files.length, 1)
})

test('real page unmount disposes pending refresh and ignores a late successful upload event', async () => {
	const h = setupPage({ key: 'source-one' })
	h.emitFileChange({ storageKey: 'source-one', path: '/notes' })
	const stale = [...h.timers.values()][0]
	h.dispose()
	stale()
	h.emitFileChange({ storageKey: 'source-one', path: '/notes' })
	h.tick()
	await flush()
	assert.equal(h.calls.files.length, 0)
	assert.equal(h.timers.size, 0)
})
