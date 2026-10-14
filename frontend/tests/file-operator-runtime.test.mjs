import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as contexts from '../src/composables/file/file-operation-context.mjs'
import * as lifecycle from '../src/composables/file/upload-lifecycle.mjs'

const source = await readFile(
	new URL('../src/composables/file/useFileOperator.js', import.meta.url),
	'utf8'
)
const pickerSource = await readFile(
	new URL('../src/components/file/selectFolder/index.ts', import.meta.url),
	'utf8'
)
const pickerComponentSource = await readFile(
	new URL(
		'../src/components/file/selectFolder/SelectFolder.vue',
		import.meta.url
	),
	'utf8'
)
const plain = (value) => JSON.parse(JSON.stringify(value))
const deferred = () => {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
const flush = async () => {
	for (let step = 0; step < 12; step++) {
		await vue.nextTick()
		await Promise.resolve()
	}
}
const rewriteImports = (text) =>
	text.replace(
		/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
		(_whole, bindings, name) =>
			bindings.trim().startsWith('{')
				? `const ${bindings.replace(
						/\s+as\s+/g,
						': '
				  )} = modules[${JSON.stringify(name)}];`
				: `const ${bindings.trim()} = modules[${JSON.stringify(name)}].default;`
	)
const row = (type = 'FILE', name = 'original.txt') => ({
	path: '/original',
	name,
	type,
})

// Execute the real composable and synchronous Vue context watchers with controlled dialogs/APIs.
function setupOperators({ refresh } = {}) {
	const route = vue.reactive({ key: 'source-one', path: '/original' })
	const storage = vue.reactive({
		loginInfo: { isLogin: true, username: 'synthetic-user', tenantId: 1 },
		user: { rootPath: '/user-one' },
		globalConfig: { enableNormalDownloadConfirm: true },
		folderConfig: {
			permission: {
				newFolder: true,
				rename: true,
				delete: true,
				move: true,
				copy: true,
				download: true,
			},
			metadata: {
				supportRenameFolder: true,
				supportMoveFolder: true,
				supportCopyFolder: true,
				supportDeleteNotEmptyFolder: true,
			},
		},
	})
	const selection = vue.ref([row()])
	const requests = [],
		dialogs = [],
		pickers = [],
		messages = [],
		refreshes = [],
		loadings = [],
		details = [],
		passwords = [],
		downloads = []
	const api = {}
	for (const method of [
		'batchDeleteReq',
		'newFolderReq',
		'renameFileReq',
		'renameFolderReq',
		'moveFolderReq',
		'moveFileReq',
		'copyFileReq',
		'copyFolderReq',
	]) {
		api[method] = (data, config) => {
			const pending = deferred()
			requests.push({ method, data: plain(data), config, ...pending })
			return pending.promise
		}
	}
	const box = Object.fromEntries(
		['prompt', 'confirm'].map((kind) => [
			kind,
			(message, title, options) => {
				const pending = deferred()
				dialogs.push({ kind, message, title, options, ...pending })
				return pending.promise
			},
		])
	)
	const modules = {
		vue,
		'element-plus': {
			ElMessageBox: box,
			ElMessage: Object.fromEntries(
				['warning', 'error', 'success'].map((kind) => [
					kind,
					(message) => messages.push({ kind, message }),
				])
			),
			ElLoading: {
				service: () => {
					const loading = {
						closes: 0,
						close() {
							this.closes++
						},
					}
					loadings.push(loading)
					return loading
				},
			},
		},
		'~/api/home/file-operator': api,
		'~/stores/storage-config': { default: () => storage },
		'~/composables/useRouterData': {
			default: () => ({
				storageKey: vue.computed(() => route.key),
				currentPath: vue.computed(() => route.path),
			}),
		},
		'~/composables/file/useFileSelect': {
			default: () => ({ selectRows: selection }),
		},
		'~/composables/file/useFileData': {
			default: () => ({
				loadFile: (params) => {
					refreshes.push({ route: plain(route), params: plain(params) })
					return refresh?.(params)
				},
			}),
		},
		'~/composables/file/useFilePwd': {
			default: () => ({
				getPathPwd: (path, _ignore, context) => {
					passwords.push({ path, context })
					return 'synthetic-directory-password'
				},
			}),
		},
		'~/composables/file/useBatchOperatorResult': {
			default: () => ({ open: (data) => details.push(plain(data)) }),
		},
		'~/components/file/selectFolder': {
			default: (title, key, path, confirm, cancel, createFolder) => {
				const picker = {
					title,
					key,
					path,
					confirm,
					cancel,
					createFolder,
					stopped: 0,
					closed: 0,
				}
				picker.callback = {
					stopLoading: () => picker.stopped++,
					closeDialog: () => picker.closed++,
				}
				picker.choose = (value) => confirm({ value, callback: picker.callback })
				pickers.push(picker)
			},
		},
		'./upload-lifecycle.mjs': lifecycle,
		'./file-operation-context.mjs': contexts,
	}
	const executable = rewriteImports(source)
		.replaceAll('export const', 'const')
		.replace('export default function', 'function')
	const module = vm.runInContext(
		`(function(){${executable}\nreturn { factory: useFileOperator, busy: operationBusy, dispose: () => { activeOperation?.stop(); } };})()`,
		vm.createContext({
			modules,
			window: { open: (url) => downloads.push(url) },
		})
	)
	return {
		api: module.factory(),
		factory: module.factory,
		busy: module.busy,
		dispose: module.dispose,
		route,
		storage,
		selection,
		requests,
		dialogs,
		pickers,
		messages,
		refreshes,
		loadings,
		details,
		passwords,
		downloads,
	}
}

const starts = {
	newFolder: (h) => h.api.newFolder(),
	rename: (h) => h.api.rename(),
	batchDelete: (h) => h.api.batchDelete(),
	moveTo: (h) => h.api.moveTo(),
	copyTo: (h) => h.api.copyTo(),
}
const confirmOperation = (h, method) => {
	if (method === 'moveTo' || method === 'copyTo') h.pickers[0].choose('/target')
	else
		h.dialogs[0].resolve(
			method === 'batchDelete' ? 'confirm' : { value: 'renamed' }
		)
}

for (const [change, mutate] of Object.entries({
	source: (h) => {
		h.route.key = 'source-two'
	},
	directory: (h) => {
		h.route.path = '/different'
	},
	account: (h) => {
		h.storage.loginInfo.username = 'another-user'
	},
	tenant: (h) => {
		h.storage.loginInfo.tenantId = 2
	},
	root: (h) => {
		h.storage.user.rootPath = '/other-root'
	},
	awayAndBack: (h) => {
		h.route.path = '/different'
		h.route.path = '/original'
	},
	logoutAndBack: (h) => {
		h.storage.loginInfo.isLogin = false
		h.storage.loginInfo.isLogin = true
	},
})) {
	test(`every pending operation cancels before requesting after ${change} changes`, async () => {
		for (const [method, start] of Object.entries(starts)) {
			const h = setupOperators()
			try {
				const returned = start(h)
				assert.equal(h.busy.value, true)
				mutate(h)
				confirmOperation(h, method)
				await flush()
				if (method === 'newFolder') assert.equal(await returned, false)
				else assert.equal(returned, undefined)
				assert.equal(h.requests.length, 0)
				assert.equal(h.refreshes.length, 0)
				assert.equal(h.busy.value, false)
				assert.ok(h.messages.some(({ message }) => /已取消/.test(message)))
			} finally {
				h.dispose()
			}
		}
	})
}

test('latest permissions are required after confirmation, including revoked folder capabilities', async () => {
	for (const [method, permission] of [
		['newFolder', 'newFolder'],
		['rename', 'rename'],
		['batchDelete', 'delete'],
		['moveTo', 'move'],
		['copyTo', 'copy'],
	]) {
		const h = setupOperators()
		starts[method](h)
		h.storage.folderConfig.permission[permission] = false
		confirmOperation(h, method)
		await flush()
		assert.equal(h.requests.length, 0)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
	for (const [method, capability] of [
		['rename', 'supportRenameFolder'],
		['moveTo', 'supportMoveFolder'],
		['copyTo', 'supportCopyFolder'],
	]) {
		const h = setupOperators()
		h.selection.value = [row('FOLDER')]
		starts[method](h)
		h.storage.folderConfig.metadata[capability] = false
		confirmOperation(h, method)
		await flush()
		assert.equal(h.requests.length, 0)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
})

test('delete and rename use frozen original selected objects even after live selection changes', async () => {
	for (const method of ['batchDelete', 'rename']) {
		const h = setupOperators()
		starts[method](h)
		h.selection.value[0].name = 'must-not-use.txt'
		h.selection.value[0].path = '/must-not-use'
		h.selection.value = [row('FILE', 'replacement.txt')]
		confirmOperation(h, method)
		await flush()
		const request = h.requests[0]
		assert.equal(request.data.storageKey, 'source-one')
		const item =
			method === 'rename' ? request.data : request.data.deleteItems[0]
		assert.equal(item.name, 'original.txt')
		assert.equal(item.path, '/original')
		request.resolve({ data: [{ success: true }] })
		await flush()
		assert.deepEqual(h.refreshes, [
			{
				route: { key: 'source-one', path: '/original' },
				params: { path: '/original' },
			},
		])
		assert.equal(h.loadings[0].closes, 1)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
})

test('move/copy choose endpoint using all original rows and reject mixed selection without opening a dialog', async () => {
	for (const action of ['copyTo', 'moveTo']) {
		for (const type of ['FILE', 'FOLDER']) {
			const h = setupOperators()
			h.selection.value = [row(type, 'one'), row(type, 'two')]
			starts[action](h)
			h.selection.value = [
				row(type === 'FILE' ? 'FOLDER' : 'FILE', 'replacement'),
			]
			h.pickers[0].choose('/target')
			await flush()
			const request = h.requests[0]
			assert.equal(
				request.method,
				`${action === 'copyTo' ? 'copy' : 'move'}${
					type === 'FILE' ? 'File' : 'Folder'
				}Req`
			)
			assert.deepEqual(request.data.nameList, ['one', 'two'])
			assert.equal(request.data.path, '/original')
			assert.equal(request.data.targetPath, '/target')
			assert.ok(
				h.passwords.every(
					({ context }) =>
						context.storageKey === 'source-one' &&
						context.rootPath === '/user-one'
				)
			)
			request.resolve({ data: [{ success: true }] })
			await flush()
			assert.equal(h.busy.value, false)
			h.dispose()
		}
		const h = setupOperators()
		h.selection.value = [row('FILE'), row('FOLDER')]
		starts[action](h)
		assert.equal(h.pickers.length, 0)
		assert.equal(h.requests.length, 0)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
})

test('shared busy blocks repeated operations from every caller until the original refresh completes', async () => {
	const refreshing = deferred()
	const h = setupOperators({ refresh: () => refreshing.promise })
	const second = h.factory()
	assert.equal(h.api.operationBusy, second.operationBusy)
	h.api.rename()
	second.rename()
	second.batchDelete()
	assert.equal(await second.newFolder(), false)
	assert.equal(h.dialogs.length, 1)
	h.dialogs[0].resolve({ value: 'renamed' })
	await flush()
	second.moveTo()
	assert.equal(h.pickers.length, 0)
	assert.equal(h.requests.length, 1)
	h.requests[0].resolve({})
	await flush()
	assert.equal(h.busy.value, true)
	assert.equal(h.refreshes.length, 1)
	refreshing.resolve()
	await flush()
	assert.equal(h.busy.value, false)
	h.dispose()
})

test('newFolder preserves its promise success result and captures an explicit base path/password context', async () => {
	const h = setupOperators()
	const result = h.api.newFolder('/target-base')
	h.dialogs[0].resolve({ value: 'notes' })
	await flush()
	assert.deepEqual(h.requests[0].data, {
		storageKey: 'source-one',
		path: '/target-base',
		name: 'notes',
	})
	assert.equal(h.requests[0].config.uploadContext.rootPath, '/user-one')
	h.requests[0].resolve({})
	assert.equal(await result, undefined)
	assert.equal(h.busy.value, false)
	assert.deepEqual(h.refreshes[0].params, { path: '/original' })
	h.dispose()
})

test('prompt/confirm cancellation, API rejection and optional failure-details cancellation always release busy', async () => {
	for (const method of ['newFolder', 'rename', 'batchDelete']) {
		for (const cancellation of ['cancel', 'close']) {
			const h = setupOperators()
			const returned = starts[method](h)
			h.dialogs[0].reject(cancellation)
			await flush()
			if (method === 'newFolder') assert.equal(await returned, false)
			assert.equal(h.busy.value, false)
			assert.equal(h.requests.length, 0)
			assert.equal(h.refreshes.length, 0)
			h.dispose()
		}
	}
	for (const [method, start] of Object.entries(starts)) {
		const h = setupOperators()
		const returned = start(h)
		confirmOperation(h, method)
		await flush()
		h.requests[0].reject(new Error('synthetic failure'))
		await flush()
		if (method === 'newFolder')
			await assert.rejects(returned, /synthetic failure/)
		assert.equal(h.busy.value, false)
		assert.equal(h.refreshes.length, 1)
		assert.ok(h.loadings.every((loading) => loading.closes === 1))
		h.dispose()
	}
	for (const method of ['batchDelete', 'moveTo', 'copyTo']) {
		const h = setupOperators()
		starts[method](h)
		confirmOperation(h, method)
		await flush()
		h.requests[0].resolve({ data: [{ success: false, name: 'original.txt' }] })
		await flush()
		assert.equal(h.busy.value, true)
		h.dialogs.at(-1).reject('close')
		await flush()
		assert.equal(h.details.length, 0)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
})

test('partial batch failure details remain available after explicit confirmation', async () => {
	const h = setupOperators()
	h.api.batchDelete()
	h.dialogs[0].resolve('confirm')
	await flush()
	const results = [
		{ success: true, name: 'one' },
		{ success: false, name: 'two', message: 'denied' },
	]
	h.requests[0].resolve({ data: results })
	await flush()
	assert.match(h.dialogs[1].message, /部分文件删除失败/)
	h.dialogs[1].resolve('confirm')
	await flush()
	assert.deepEqual(h.details, [results])
	assert.equal(h.busy.value, false)
	h.dispose()
})

test('late completion never refreshes or presents another directory/account', async () => {
	for (const [method, start] of Object.entries(starts)) {
		const h = setupOperators()
		const returned = start(h)
		confirmOperation(h, method)
		await flush()
		h.route.key = 'source-two'
		h.route.path = '/different'
		h.storage.loginInfo.username = 'other-user'
		h.requests[0].resolve({ data: [{ success: false }] })
		await flush()
		if (method === 'newFolder') await returned
		assert.equal(h.refreshes.length, 0)
		assert.equal(h.details.length, 0)
		assert.equal(h.messages.filter(({ kind }) => kind === 'success').length, 0)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
})

test('picker same-target retry, repeated confirmation and cancellation during request keep correct busy lifetime', async () => {
	const h = setupOperators()
	h.api.moveTo()
	const picker = h.pickers[0]
	picker.choose('/original')
	assert.equal(picker.stopped, 1)
	assert.equal(picker.closed, 0)
	assert.equal(h.busy.value, true)
	picker.choose('/target')
	picker.choose('/different')
	assert.equal(h.requests.length, 1)
	picker.cancel()
	await flush()
	assert.equal(h.busy.value, true)
	h.requests[0].resolve({ data: [{ success: true }] })
	await flush()
	assert.equal(h.busy.value, false)
	h.api.copyTo()
	h.pickers[1].cancel()
	h.pickers[1].choose('/must-not-run-after-close')
	await flush()
	assert.equal(h.requests.length, 1)
	assert.equal(h.busy.value, false)
	h.dispose()
})

test('only the active picker can create a nested folder and the shared parent lock survives child completion', async () => {
	const h = setupOperators()
	h.api.copyTo()
	const picker = h.pickers[0]
	assert.equal(await h.api.newFolder('/target'), false)
	const child = picker.createFolder('/target')
	assert.equal(h.dialogs.length, 1)
	assert.equal(await picker.createFolder('/target'), false)
	picker.choose('/target')
	assert.equal(h.requests.length, 0)
	h.dialogs[0].resolve({ value: 'nested' })
	await flush()
	assert.equal(h.requests[0].method, 'newFolderReq')
	assert.deepEqual(h.requests[0].data, {
		storageKey: 'source-one',
		path: '/target',
		name: 'nested',
	})
	assert.equal(h.requests[0].config.uploadContext.rootPath, '/user-one')
	h.requests[0].resolve({})
	assert.equal(await child, undefined)
	assert.equal(h.busy.value, true)
	assert.equal(h.refreshes.length, 0)
	assert.deepEqual(plain(h.selection.value), [row()])
	picker.choose('/target/nested')
	await flush()
	assert.equal(h.requests[1].method, 'copyFileReq')
	assert.deepEqual(h.requests[1].data.nameList, ['original.txt'])
	h.requests[1].resolve({ data: [{ success: true }] })
	await flush()
	assert.equal(h.busy.value, false)
	assert.equal(h.refreshes.length, 1)
	assert.equal(await picker.createFolder('/target'), false)
	h.dispose()
})

test('nested mkdir cancellation and failure preserve the outer picker and recheck latest permissions', async () => {
	const h = setupOperators()
	h.api.moveTo()
	const picker = h.pickers[0]
	const canceled = picker.createFolder('/target')
	h.dialogs[0].reject('cancel')
	assert.equal(await canceled, false)
	assert.equal(h.busy.value, true)
	const denied = picker.createFolder('/target')
	h.storage.folderConfig.permission.newFolder = false
	h.dialogs[1].resolve({ value: 'denied' })
	assert.equal(await denied, false)
	assert.equal(h.requests.length, 0)
	assert.equal(h.busy.value, true)
	h.storage.folderConfig.permission.newFolder = true
	const failed = picker.createFolder('/target')
	h.dialogs[2].resolve({ value: 'failed' })
	await flush()
	h.requests[0].reject(new Error('synthetic mkdir error'))
	await assert.rejects(failed, /synthetic mkdir error/)
	assert.equal(h.busy.value, true)
	assert.equal(h.refreshes.length, 0)
	picker.cancel()
	await flush()
	assert.equal(h.busy.value, false)
	assert.equal(h.refreshes.length, 0)
	h.dispose()
})

test('closing a picker refreshes the original main listing once after successful child changes and holds busy through refresh', async () => {
	const refreshing = deferred()
	const h = setupOperators({ refresh: () => refreshing.promise })
	h.api.copyTo()
	const picker = h.pickers[0]
	for (const name of ['first', 'second']) {
		const child = picker.createFolder('/target')
		h.dialogs.at(-1).resolve({ value: name })
		await flush()
		h.requests.at(-1).resolve({})
		assert.equal(await child, undefined)
		assert.equal(h.refreshes.length, 0)
		assert.equal(h.busy.value, true)
	}
	picker.cancel()
	picker.cancel()
	await flush()
	assert.equal(h.refreshes.length, 1)
	assert.deepEqual(h.refreshes[0], {
		route: { key: 'source-one', path: '/original' },
		params: { path: '/original' },
	})
	assert.equal(h.busy.value, true)
	refreshing.resolve()
	await flush()
	assert.equal(h.busy.value, false)
	h.dispose()
})

test('closing while child mkdir is in flight waits and refreshes only if the original context is still current', async () => {
	for (const navigated of [false, true]) {
		const h = setupOperators()
		h.api.moveTo()
		const picker = h.pickers[0]
		const child = picker.createFolder('/target')
		h.dialogs[0].resolve({ value: 'nested' })
		await flush()
		picker.cancel()
		if (navigated) h.route.path = '/different'
		await flush()
		assert.equal(h.busy.value, true)
		assert.equal(h.refreshes.length, 0)
		h.requests[0].resolve({})
		assert.equal(await child, false)
		await flush()
		assert.equal(h.refreshes.length, navigated ? 0 : 1)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
})

test('outer copy completion after nested mkdir refreshes once and skips the main listing after navigation', async () => {
	for (const navigated of [false, true]) {
		const h = setupOperators()
		h.api.copyTo()
		const picker = h.pickers[0]
		const child = picker.createFolder('/target')
		h.dialogs[0].resolve({ value: 'nested' })
		await flush()
		h.requests[0].resolve({})
		await child
		assert.equal(h.refreshes.length, 0)
		picker.choose('/target/nested')
		await flush()
		if (navigated) h.route.key = 'source-two'
		h.requests[1].resolve({ data: [{ success: true }] })
		await flush()
		assert.equal(h.refreshes.length, navigated ? 0 : 1)
		assert.equal(h.busy.value, false)
		h.dispose()
	}
})

test('canceling a picker with pending child work releases only after child ends and never sends an unconfirmed mkdir', async () => {
	for (const started of [false, true]) {
		const h = setupOperators()
		h.api.moveTo()
		const picker = h.pickers[0]
		const child = picker.createFolder('/target')
		if (started) {
			h.dialogs[0].resolve({ value: 'nested' })
			await flush()
		}
		picker.cancel()
		await flush()
		assert.equal(h.busy.value, true)
		if (started) {
			h.route.key = 'source-two'
			h.requests[0].resolve({})
		} else h.dialogs[0].resolve({ value: 'must-not-create' })
		assert.equal(await child, false)
		await flush()
		assert.equal(h.busy.value, false)
		assert.equal(h.requests.length, started ? 1 : 0)
		assert.equal(h.refreshes.length, 0)
		h.dispose()
	}
})

test('nested mkdir also cancels when context changes during its prompt', async () => {
	const h = setupOperators()
	h.api.copyTo()
	const picker = h.pickers[0]
	const child = picker.createFolder('/target')
	h.storage.loginInfo.username = 'other-user'
	h.dialogs[0].resolve({ value: 'must-not-create' })
	assert.equal(await child, false)
	assert.equal(h.requests.length, 0)
	picker.choose('/target')
	await flush()
	assert.equal(h.busy.value, false)
	h.dispose()
})

function setupRealPicker() {
	let props,
		unmounted = 0,
		removed = 0,
		provided
	const modules = {
		vue: {
			createApp: (_component, input) => {
				props = input
				return {
					provide: (_key, value) => {
						provided = value
					},
					mount() {},
					unmount() {
						unmounted++
						props.onClose({ type: 'close' })
					},
				}
			},
		},
		'./SelectFolder.vue': { default: {} },
	}
	const compiled = ts.transpileModule(pickerSource, {
		compilerOptions: {
			module: ts.ModuleKind.ESNext,
			target: ts.ScriptTarget.ES2022,
		},
	}).outputText
	const executable = rewriteImports(compiled).replace(
		'export default createDialog;',
		''
	)
	const create = vm.runInContext(
		`(function(){${executable}\nreturn createDialog;})()`,
		vm.createContext({
			modules,
			ref: vue.ref,
			document: {
				createElement: () => ({}),
				body: {
					appendChild() {},
					removeChild() {
						removed++
					},
				},
			},
		})
	)
	return {
		create,
		props: () => props,
		counts: () => ({ unmounted, removed }),
		loading: () => provided,
	}
}

test('real selector invokes cancellation and disposes exactly once, including reentrant close/unmount', () => {
	for (const type of ['cancel', 'close']) {
		const h = setupRealPicker()
		let cancels = 0
		h.create(
			'移动',
			'one',
			'/',
			() => assert.fail('Unexpected confirmation'),
			() => {
				cancels++
			}
		)
		h.props().onClose({ type })
		h.props().onClose({ type })
		assert.equal(cancels, 1)
		assert.deepEqual(h.counts(), { unmounted: 1, removed: 1 })
	}
})

test('real selector keeps old callers compatible and close callbacks idempotent after confirmation', () => {
	const h = setupRealPicker()
	let callback,
		cancels = 0
	h.create(
		'复制',
		'one',
		'/',
		(result) => {
			callback = result.callback
		},
		() => {
			cancels++
		}
	)
	h.props().onClose({ type: 'confirm', value: '/target' })
	h.loading().value = true
	callback.stopLoading()
	assert.equal(h.loading().value, false)
	callback.closeDialog()
	callback.closeDialog()
	h.props().onClose({ type: 'cancel' })
	assert.equal(cancels, 0)
	assert.deepEqual(h.counts(), { unmounted: 1, removed: 1 })
	const old = setupRealPicker()
	old.create('移动', 'one', '/', () => {})
	assert.doesNotThrow(() => old.props().onClose({ type: 'cancel' }))
})

test('real selector passes the scoped mkdir capability only to its own component', () => {
	const h = setupRealPicker()
	const createFolder = () => Promise.resolve()
	h.create(
		'复制',
		'one',
		'/',
		() => {},
		() => {},
		createFolder
	)
	assert.equal(h.props().createFolder, createFolder)
	h.props().onClose({ type: 'cancel' })
})

function setupPickerComponent() {
	const child = deferred(),
		requests = [],
		createCalls = []
	const loading = vue.ref(false)
	let unmount
	const modules = {
		'~/utils': {
			concatPath: (...parts) =>
				parts.filter(Boolean).join('/').replace(/\/+/g, '/'),
		},
		'element-plus': { ElMessage: { warning() {}, error() {} } },
		vue: { inject: () => loading },
		'~/api/home/home': {
			loadFileListReq: async (data) => {
				requests.push(data)
				return { data: { files: [], passwordPattern: '' } }
			},
		},
		'~/stores/storage-config': {
			default: () => ({ permission: { newFolder: true } }),
		},
		'~/composables/file/useFilePwd': {
			default: () => ({
				getPathPwd: () => '',
				putPathPwd() {},
				popPassword() {},
			}),
		},
		'~/composables/file/useFileOperator': {
			default: () => ({
				newFolder: () =>
					assert.fail('Public mkdir must not bypass the picker capability'),
			}),
		},
	}
	const { descriptor } = parse(pickerComponentSource)
	const compiled = compileScript(descriptor, {
		id: 'file-picker-runtime-test',
	}).content
	const executable = rewriteImports(compiled).replace(
		'export default',
		'const component ='
	)
	const component = vm.runInContext(
		`(function(){${executable}\nreturn component;})()`,
		vm.createContext({
			modules,
			...vue,
			onMounted() {},
			onBeforeUnmount: (callback) => {
				unmount = callback
			},
		})
	)
	const props = {
		show: true,
		title: '复制',
		storageKey: 'one',
		basePath: '/',
		onClose() {},
		createFolder: (path) => {
			createCalls.push(path)
			return child.promise
		},
	}
	const api = component.setup(props, { expose() {}, emit() {} })
	return { api, child, requests, createCalls, unmount: () => unmount() }
}

test('real picker mkdir handler avoids duplicate calls, skips canceled/failed/stale refreshes and refreshes only success', async () => {
	for (const outcome of ['success', 'cancel', 'failure', 'unmount']) {
		const h = setupPickerComponent()
		const first = h.api.onNewFolder()
		await h.api.onNewFolder()
		assert.equal(h.createCalls.length, 1)
		assert.equal(h.api.folderCreating.value, true)
		if (outcome === 'failure')
			h.child.reject(new Error('synthetic mkdir failure'))
		else {
			if (outcome === 'unmount') h.unmount()
			h.child.resolve(outcome === 'cancel' ? false : undefined)
		}
		await first
		assert.equal(h.api.folderCreating.value, false)
		assert.equal(h.requests.length, outcome === 'success' ? 1 : 0)
	}
})
