import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as setupUtils from '../src/utils/storage-setup.mjs'
import * as recognizerUtils from '../src/utils/storage-source-recognizer.mjs'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

// Real SFC setup and Vue watchers; synthetic API responses, no browser/server/model.
const source = await readFile(
	new URL('../src/pages/admin/storage-edit/index.vue', import.meta.url),
	'utf8'
)
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, {
	id: 'storage-edit-runtime-test',
}).content
const plain = (value) => JSON.parse(JSON.stringify(value))
const field = (key, defaultValue = '', required = true, extra = {}) => ({
	key,
	name: key,
	defaultValue,
	required,
	condition: '',
	hidden: false,
	type: 'input',
	...extra,
})

function deferred() {
	let resolve, reject
	const promise = new Promise((finish, fail) => {
		resolve = finish
		reject = fail
	})
	return { promise, resolve, reject }
}
function schema(type) {
	if (type === 'local') return [field('filePath', '', true)]
	if (type === 'webdav')
		return [field('url', '', true), field('username', '', false)]
	return [
		field('endPoint', '', true),
		field('accessKey', '', true),
		field('secretKey', '', true),
		field('bucketName', '', true),
		field('region', '', true),
		field('corsConfigList', '[]'),
		field('proxyEnabled', 'false', true, { type: 'switch' }),
	]
}

function setupPage({
	storageId,
	supported = ['local', 'webdav', 's3', 'aliyun', 'minio', 'ftp', 'sftp'],
	item = async (id) => ({ data: storageRecord(id) }),
	params = async ({ storageType }) => ({ data: schema(storageType) }),
	buckets = async () => ({ data: [] }),
	cors = async () => ({ data: [] }),
	save = async () => ({ data: 12 }),
	preflight = async () => ({
		data: { passed: true, message: 'Readonly checked' },
	}),
	applyCors = async () => ({ data: null }),
	confirmation = async () => true,
	alias = async () => ({ data: false }),
	validation = async () => true,
} = {}) {
	const calls = {
			items: [],
			params: [],
			save: [],
			aliases: [],
			dialogs: [],
			buckets: [],
			cors: [],
			preflight: [],
			applyCors: [],
		},
		notices = [],
		cleanups = [],
		mounts = [],
		pushes = []
	const route = vue.reactive({
		params: storageId === undefined ? {} : { storageId },
	})
	const router = { push: (path) => pushes.push(path) }
	const storageStore = vue.reactive({ loginInfo: { isAdmin: false } })
	const modules = {
		'~/components/common/SafeHtml.vue': { default: {} },
		'~/utils/latest-request.mjs': { createLatestRequest },
		'~/utils/storage-setup.mjs': setupUtils,
		'~/utils/storage-source-recognizer.mjs': recognizerUtils,
		'@heroicons/vue/24/solid': { CheckBadgeIcon: {}, LinkIcon: {} },
		'~/composables/admin/useAdminSetting': {
			default: () => ({ siteSetting: vue.ref(null) }),
		},
		'~/stores/global-config': {
			default: () => ({ adminForm: {}, serverAddress: 'http://example.test' }),
		},
		'~/api/admin/admin-storage': {
			loadStorageItemReq: (...args) => {
				calls.items.push(args)
				return item(...args)
			},
			loadSupportStorageReq: async () => ({
				data: supported.map((value) =>
					typeof value === 'string' ? { key: value } : value
				),
			}),
			storageParamsReq: (...args) => {
				calls.params.push(args)
				return params(...args)
			},
			saveStorageSettingReq: (...args) => {
				calls.save.push(args)
				return save(...args)
			},
			testStorageConnectionReq: (...args) => {
				calls.preflight.push(args)
				return preflight(...args)
			},
			applySavedStorageCorsReq: (...args) => {
				calls.applyCors.push(args)
				return applyCors(...args)
			},
			existStorageKeyReq: (...args) => {
				calls.aliases.push(args)
				return alias(...args)
			},
		},
		'element-plus': {
			ElMessageBox: {
				confirm: (...args) => {
					calls.dialogs.push(args)
					return confirmation(...args)
				},
			},
		},
		'~/api/tools/tools-sharepoint': {
			loadSharePointSiteListsReq: async () => ({ data: [] }),
			loadSharePointSitesReq: async () => ({ data: [] }),
		},
		'~/api/tools/tools-s3': {
			loadS3BucketsReq: async (...args) => {
				calls.buckets.push(args)
				return buckets(...args)
			},
			loadS3CorsConfigReq: async (...args) => {
				calls.cors.push(args)
				return cors(...args)
			},
		},
		'~/api/tools/tools-gd': { loadGDDrivesReq: async () => ({ data: [] }) },
		'@soerenmartius/vue3-clipboard': { toClipboard: async () => true },
		'~/utils': {
			concatPath: (...parts) => parts.join('/'),
			isNotMobile: true,
			convertStr: (value) =>
				value === 'true'
					? true
					: value === 'false'
					? false
					: value === 'null'
					? null
					: value === '[]'
					? []
					: value,
		},
		'~/api/admin/admin-tenant': {
			getTenantListReq: async () => ({ data: [] }),
		},
		'~/stores/storage-config': { default: () => storageStore },
	}
	const executable = compiled
		.replace(
			/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
			(_whole, bindings, name) => {
				const key = JSON.stringify(name)
				return bindings.trim().startsWith('{')
					? `const ${bindings.replace(/\s+as\s+/g, ': ')} = modules[${key}];`
					: `const ${bindings.trim()} = modules[${key}].default;`
			}
		)
		.replace('export default', 'return')
	const context = vm.createContext({
		...vue,
		modules,
		AbortController,
		URL,
		console,
		onMounted: (callback) => mounts.push(callback),
		onBeforeUnmount: (callback) => cleanups.push(callback),
		useRoute: () => route,
		useRouter: () => router,
		constant: {
			systemNames: ['admin', 'login', 'learning'],
			region: {},
			storageType: { s3Type: ['s3', 'aliyun', 'minio'] },
		},
		window: { location: { protocol: 'http:' } },
		ElMessage: {
			success: (message) => notices.push(message),
			error: (message) => notices.push(message),
		},
		alert(message) {
			throw new Error('unexpected expression alert: ' + message)
		},
	})
	const Component = vm.runInContext(`(function(){${executable}\n})()`, context)
	const scope = vue.effectScope()
	let api
	scope.run(() => {
		api = Component.setup({}, { expose() {} })
	})
	api.storageEditForm.value = { validate: validation }
	const flush = async () => {
		await vue.nextTick()
		await Promise.resolve()
		await vue.nextTick()
	}
	return {
		api,
		calls,
		notices,
		pushes,
		route,
		storageStore,
		flush,
		async mount() {
			await Promise.all(mounts.map((callback) => callback()))
			await flush()
		},
		async navigate(id) {
			route.params.storageId = id
			await flush()
			await flush()
		},
		async choose(type) {
			api.storageItem.value.type = type
			await flush()
		},
		unmount() {
			for (const cleanup of cleanups) cleanup()
			scope.stop()
		},
	}
}

function storageRecord(id, type = 'local', params = {}) {
	return {
		id: Number(id),
		key: 'storage-' + id,
		name: '资料库 ' + id,
		type: { key: type },
		storageSourceAllParam: {
			filePath: '/data/source-' + id,
			corsConfigList: [],
			...params,
		},
	}
}

function complete(page, type = 'local') {
	const item = page.api.storageItem.value
	item.name = '本人资料'
	if (type === 'local') item.storageSourceAllParam.filePath = '/data/My Files'
	else
		Object.assign(item.storageSourceAllParam, {
			endPoint: 's3.example.com',
			accessKey: 'test-id',
			secretKey: 'synthetic-secret',
			bucketName: 'course-files',
			region: 'test-region',
		})
}

test('read-only preflight is explicit, uses one separate request and never saves or applies CORS', async () => {
	const response = deferred()
	const page = setupPage({ preflight: () => response.promise })
	try {
		await page.choose('local')
		complete(page)
		assert.equal(page.calls.preflight.length, 0)
		const first = page.api.testConnection(),
			second = page.api.testConnection()
		await page.flush()
		assert.equal(page.calls.preflight.length, 1)
		assert.equal(page.api.safetyBusy.value, 'test')
		await page.api.submitForm()
		assert.equal(page.calls.save.length, 0)
		response.resolve({
			data: { passed: true, message: 'Only read access checked; not saved' },
		})
		await Promise.all([first, second])
		assert.equal(page.api.safetyBusy.value, '')
		assert.equal(page.api.safetyPassed.value, true)
		assert.equal(page.calls.save.length + page.calls.applyCors.length, 0)
	} finally {
		page.unmount()
	}
})

for (const change of ['params', 'route', 'account', 'unmount']) {
	test(`late read-only preflight cannot report stale success after ${change} changes`, async () => {
		const response = deferred()
		const page = setupPage({
			storageId: '901',
			preflight: () => response.promise,
		})
		try {
			await page.mount()
			const checking = page.api.testConnection()
			await page.flush()
			assert.equal(page.calls.preflight.length, 1)
			if (change === 'params')
				page.api.storageItem.value.storageSourceAllParam.filePath = '/different'
			if (change === 'route') await page.navigate('902')
			if (change === 'account')
				page.storageStore.loginInfo.username = 'different-user'
			if (change === 'unmount') page.unmount()
			assert.equal(page.calls.preflight[0][1].signal.aborted, true)
			response.resolve({ data: { passed: true, message: 'Stale' } })
			await checking
			assert.equal(page.api.safetyNotice.value, '')
			assert.equal(page.api.safetyPassed.value, false)
			assert.equal(page.calls.save.length + page.calls.applyCors.length, 0)
		} finally {
			if (change !== 'unmount') page.unmount()
		}
	})
}

test('unsupported protocol preflight does not fall back to saving and failure never automatically retries', async () => {
	const page = setupPage({
		preflight: async () => {
			throw new Error('synthetic failure')
		},
	})
	try {
		await page.choose('webdav')
		await page.api.testConnection()
		assert.equal(page.calls.preflight.length + page.calls.save.length, 0)
		await page.choose('local')
		complete(page)
		await page.api.testConnection()
		await page.flush()
		assert.equal(page.calls.preflight.length, 1)
		assert.equal(page.api.safetyPassed.value, false)
		assert.match(page.api.safetyNotice.value, /未保存/)
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('cancelled standalone CORS confirmation creates neither save nor remote application', async () => {
	const page = setupPage({
		storageId: '901',
		confirmation: async () => {
			throw new Error('cancel')
		},
	})
	try {
		await page.mount()
		await page.choose('s3')
		await page.api.applySavedCors()
		assert.equal(page.calls.dialogs.length, 1)
		assert.equal(page.calls.applyCors.length + page.calls.save.length, 0)
		assert.equal(page.api.safetyBusy.value, '')
	} finally {
		page.unmount()
	}
})

test('standalone CORS sends only saved source ID once, never current form credentials, and blocks save while pending', async () => {
	const dialog = deferred(),
		response = deferred()
	const page = setupPage({
		storageId: '901',
		confirmation: () => dialog.promise,
		applyCors: () => response.promise,
	})
	try {
		await page.mount()
		await page.choose('s3')
		complete(page, 's3')
		const first = page.api.applySavedCors(),
			duplicate = page.api.applySavedCors()
		await page.flush()
		assert.equal(page.calls.dialogs.length, 1)
		await page.api.submitForm()
		assert.equal(page.calls.save.length, 0)
		dialog.resolve(true)
		await page.flush()
		assert.equal(page.calls.applyCors.length, 1)
		assert.equal(page.calls.applyCors[0][0], 901)
		assert.equal(page.calls.applyCors[0][1].signal.aborted, false)
		assert.equal(Object.hasOwn(page.calls.applyCors[0][1], 'secretKey'), false)
		response.resolve({ data: null })
		await Promise.all([first, duplicate])
		assert.equal(page.calls.save.length, 0)
		assert.equal(page.api.safetyPassed.value, true)
		assert.equal(page.pushes.length, 0)
	} finally {
		page.unmount()
	}
})

test('late CORS confirmation for old route cannot write or clear a new route preflight busy state', async () => {
	const dialog = deferred(),
		check = deferred()
	const page = setupPage({
		storageId: '901',
		confirmation: () => dialog.promise,
		preflight: () => check.promise,
	})
	try {
		await page.mount()
		await page.choose('s3')
		const applying = page.api.applySavedCors()
		await page.flush()
		await page.navigate('902')
		const checking = page.api.testConnection()
		await page.flush()
		assert.equal(page.api.safetyBusy.value, 'test')
		dialog.resolve(true)
		await applying
		assert.equal(page.calls.applyCors.length, 0)
		assert.equal(page.api.safetyBusy.value, 'test')
		check.resolve({ data: { passed: true, message: 'New target' } })
		await checking
		assert.equal(page.api.safetyNotice.value, 'New target')
	} finally {
		page.unmount()
	}
})

test('switching storage type aborts the old schema and ignores late fields and defaults from it', async () => {
	const old = deferred()
	const page = setupPage({
		params: ({ storageType }) =>
			storageType === 'local'
				? old.promise
				: Promise.resolve({ data: schema(storageType) }),
	})
	try {
		await page.choose('local')
		assert.equal(page.calls.params.length, 1)
		await page.choose('webdav')
		assert.equal(page.calls.params[0][1].signal.aborted, true)
		assert.deepEqual(
			plain(page.api.storageSourceParamList.value.map((item) => item.key)),
			['url', 'username']
		)
		old.resolve({ data: [field('filePath', '/old/default')] })
		await page.flush()
		assert.equal(page.api.storageItem.value.type, 'webdav')
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			undefined
		)
		assert.equal(page.api.paramsLoading.value, false)
	} finally {
		page.unmount()
	}
})

test('type changes replace an untouched suggested name but preserve a name edited by the user', async () => {
	const page = setupPage()
	try {
		await page.choose('local')
		assert.equal(page.api.storageItem.value.name, '本地云盘')
		await page.choose('webdav')
		assert.equal(page.api.storageItem.value.name, 'WebDAV 云盘')
		page.api.storageItem.value.name = '自己的课程资料'
		await page.choose('s3')
		assert.equal(page.api.storageItem.value.name, '自己的课程资料')
	} finally {
		page.unmount()
	}
})

test('server typed defaults initialize safe values and empty CORS array is not rejected as missing', async () => {
	const page = setupPage()
	try {
		await page.choose('s3')
		complete(page, 's3')
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.proxyEnabled,
			false
		)
		assert.deepEqual(
			plain(page.api.storageItem.value.storageSourceAllParam.corsConfigList),
			[]
		)
		page.api.checkSetup()
		assert.deepEqual(plain(page.api.setupIssues.value), [])
		assert.equal(page.api.advancedFieldCount.value, 2)
	} finally {
		page.unmount()
	}
})

test('a cleared mandatory advanced field produces an issue and expands advanced settings without a save', async () => {
	const page = setupPage()
	try {
		await page.choose('s3')
		complete(page, 's3')
		page.api.storageItem.value.storageSourceAllParam.corsConfigList = ''
		await page.api.submitForm()
		assert.ok(
			page.api.setupIssues.value.some((issue) => issue.key === 'corsConfigList')
		)
		assert.equal(page.api.showAdvanced.value, true)
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('double save while validation is pending issues exactly one POST with a captured payload', async () => {
	const validation = deferred(),
		response = deferred()
	const page = setupPage({
		validation: () => validation.promise,
		save: () => response.promise,
	})
	try {
		await page.choose('local')
		complete(page)
		const first = page.api.submitForm(),
			second = page.api.submitForm()
		assert.equal(page.api.loading.value, true)
		validation.resolve(true)
		await page.flush()
		assert.equal(page.calls.save.length, 1)
		assert.equal(
			page.calls.save[0][0].storageSourceAllParam.filePath,
			'/data/My Files'
		)
		page.api.storageItem.value.name = 'later edit'
		assert.equal(page.calls.save[0][0].name, '本人资料')
		response.resolve({ data: 12 })
		await Promise.all([first, second])
		assert.equal(page.calls.save.length, 1)
		assert.deepEqual(page.pushes, ['/admin/storage-list'])
	} finally {
		page.unmount()
	}
})

test('nonempty CORS save never applies remote rules or asks for remote-write confirmation', async () => {
	const page = setupPage()
	try {
		await page.choose('s3')
		complete(page, 's3')
		page.api.storageItem.value.storageSourceAllParam.corsConfigList = [
			{ allowedOrigins: ['https://example.test'], allowedMethods: ['GET'] },
		]
		await page.api.submitForm()
		assert.equal(page.calls.dialogs.length, 0)
		assert.equal(page.calls.applyCors.length, 0)
		assert.equal(page.calls.save.length, 1)
		assert.equal(page.api.loading.value, false)
	} finally {
		page.unmount()
	}
})

test('standalone CORS requires confirmation and unmount before acceptance prevents remote mutation', async () => {
	for (const dispose of [false, true]) {
		const dialog = deferred()
		const page = setupPage({
			storageId: '901',
			confirmation: () => dialog.promise,
		})
		try {
			await page.mount()
			await page.choose('s3')
			page.api.storageItem.value.storageSourceAllParam.corsConfigList =
				'[{"allowedOrigins":["https://example.test"],"allowedMethods":["GET"]}]'
			const applying = page.api.applySavedCors()
			await page.flush()
			assert.equal(page.calls.dialogs.length, 1)
			assert.equal(page.calls.applyCors.length, 0)
			if (dispose) page.unmount()
			dialog.resolve(true)
			await applying
			assert.equal(page.calls.applyCors.length, dispose ? 0 : 1)
			assert.equal(page.calls.save.length, 0)
		} finally {
			if (!dispose) page.unmount()
		}
	}
})

test('alias availability failure always settles its validation callback with an actionable error', async () => {
	const page = setupPage({
		alias: async () => {
			throw new Error('synthetic outage')
		},
	})
	try {
		let callbacks = 0
		const result = await new Promise((resolve) =>
			page.api.rules.value.key[0].validator({}, 'my-files', (error) => {
				callbacks++
				resolve(error)
			})
		)
		assert.equal(callbacks, 1)
		assert.match(result.message, /无法核验/)
		assert.equal(page.calls.aliases.length, 1)
		assert.equal(page.calls.aliases[0][1].showDefaultMsg, false)
	} finally {
		page.unmount()
	}
})

test('address recognition only previews suggestions until explicit apply, and never saves or queries remote storage', async () => {
	const page = setupPage()
	try {
		await page.choose('webdav')
		page.api.connectionAddress.value = 'https://files.example.com/dav/'
		page.api.recognizeAddress()
		assert.equal(page.api.storageItem.value.storageSourceAllParam.url, '')
		assert.equal(
			page.api.addressSuggestions.value.url,
			'https://files.example.com/dav/'
		)
		page.api.applyAddressSuggestions()
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.url,
			'https://files.example.com/dav/'
		)
		assert.equal(page.calls.save.length, 0)
		assert.equal(page.calls.buckets.length + page.calls.cors.length, 0)
	} finally {
		page.unmount()
	}
})

test('unmount aborts an in-flight save and its late success cannot navigate or show a success message', async () => {
	const response = deferred()
	const page = setupPage({ save: () => response.promise })
	await page.choose('local')
	complete(page)
	const saving = page.api.submitForm()
	await page.flush()
	assert.equal(page.calls.save.length, 1)
	page.unmount()
	assert.equal(page.calls.save[0][1].signal.aborted, true)
	response.resolve({ data: 12 })
	await saving
	assert.equal(page.api.storageItem.value.id, undefined)
	assert.equal(page.pushes.length, 0)
	assert.equal(page.notices.length, 0)
})

test('uncertain save failure never automatically retries and asks the user to inspect the list before resubmitting', async () => {
	const page = setupPage({
		save: async () => {
			throw Object.assign(new Error('synthetic timeout'), {
				code: 'ECONNABORTED',
			})
		},
	})
	try {
		await page.choose('local')
		complete(page)
		await page.api.submitForm()
		await page.flush()
		assert.equal(page.calls.save.length, 1)
		assert.equal(page.api.loading.value, false)
		assert.match(page.api.saveError.value, /列表刷新核实/)
		assert.match(page.api.saveError.value, /不要重复提交/)
		assert.equal(page.pushes.length, 0)
	} finally {
		page.unmount()
	}
})

test('cancelled storage schema response after unmount cannot apply data, start save or set an error', async () => {
	const response = deferred()
	const page = setupPage({ params: () => response.promise })
	await page.choose('local')
	page.unmount()
	assert.equal(page.calls.params[0][1].signal.aborted, true)
	response.resolve({ data: [field('filePath', '/late/data')] })
	await page.flush()
	assert.equal(page.api.storageItem.value.storageSourceAllParam.filePath, null)
	assert.equal(page.api.storageSourceParamList.value.length, 0)
	assert.equal(page.api.paramsError.value, '')
	await page.api.submitForm()
	assert.equal(page.calls.save.length, 0)
})

test('reusing the editor for 901 then 902 reloads its target and ignores a late response for 901', async () => {
	const old = deferred()
	const page = setupPage({
		storageId: '901',
		item: (id) =>
			String(id) === '901'
				? old.promise
				: Promise.resolve({ data: storageRecord(id) }),
	})
	try {
		const mounting = page.mount()
		await page.flush()
		assert.equal(page.api.initialLoading.value, true)
		assert.equal(page.calls.items[0][0], '901')
		await page.navigate('902')
		assert.equal(page.calls.items[0][1].signal.aborted, true)
		assert.deepEqual(
			page.calls.items.map(([id]) => id),
			['901', '902']
		)
		assert.equal(page.api.storageItem.value.id, 902)
		assert.equal(page.api.storageItem.value.key, 'storage-902')
		assert.equal(page.api.initialLoading.value, false)
		old.resolve({
			data: storageRecord('901', 'webdav', { url: 'https://old.test/dav/' }),
		})
		await mounting
		await page.flush()
		assert.equal(page.api.storageItem.value.id, 902)
		assert.equal(page.api.storageItem.value.type, 'local')
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			'/data/source-902'
		)
		assert.deepEqual(
			plain(page.api.storageSourceParamList.value.map(({ key }) => key)),
			['filePath']
		)
		assert.equal(page.api.initialError.value, '')
		assert.equal(page.api.paramsLoading.value, false)
	} finally {
		page.unmount()
	}
})

test('a model whose id does not match the current edit URL cannot be saved', async () => {
	const page = setupPage({ storageId: '901' })
	try {
		await page.mount()
		page.api.storageItem.value.id = 902
		await page.api.submitForm()
		assert.equal(page.calls.save.length, 0)
		assert.equal(page.calls.dialogs.length, 0)
		assert.equal(page.api.loading.value, false)
		assert.equal(page.pushes.length, 0)
	} finally {
		page.unmount()
	}
})

test('a mismatched item response is an initial-load failure, not an editable source', async () => {
	const page = setupPage({
		storageId: '901',
		item: async () => ({ data: storageRecord('902') }),
	})
	try {
		await page.mount()
		assert.ok(page.api.initialError.value)
		assert.equal(page.api.initialLoading.value, false)
		assert.equal(page.api.storageItem.value.id, undefined)
		await page.api.submitForm()
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

for (const phase of ['validation', 'post']) {
	test(`late ${phase} completion for the previous edit route cannot save, navigate, notify or clear the new save loading`, async () => {
		const old = deferred(),
			nextValidation = deferred()
		let validations = 0,
			saves = 0
		const params = {
			endPoint: 's3.example.test',
			accessKey: 'synthetic-id',
			secretKey: 'synthetic-secret',
			bucketName: 'course-files',
			region: 'test-region',
			proxyEnabled: false,
			corsConfigList:
				phase === 'dialog'
					? [
							{
								allowedOrigins: ['https://example.test'],
								allowedMethods: ['GET'],
							},
					  ]
					: [],
		}
		const page = setupPage({
			storageId: '901',
			item: async (id) => ({ data: storageRecord(id, 's3', params) }),
			validation: () => {
				validations++
				return validations === 1
					? phase === 'validation'
						? old.promise
						: Promise.resolve(true)
					: nextValidation.promise
			},
			confirmation: () =>
				phase === 'dialog' ? old.promise : Promise.resolve(true),
			save: () => {
				saves++
				return phase === 'post' && saves === 1
					? old.promise
					: Promise.resolve({ data: 902 })
			},
		})
		try {
			await page.mount()
			const oldSaving = page.api.submitForm()
			await page.flush()
			assert.equal(page.api.loading.value, true)
			assert.equal(page.calls.save.length, phase === 'post' ? 1 : 0)
			assert.equal(page.calls.dialogs.length, phase === 'dialog' ? 1 : 0)
			await page.navigate('902')
			if (phase === 'post')
				assert.equal(page.calls.save[0][1].signal.aborted, true)
			assert.equal(page.api.storageItem.value.id, 902)
			assert.equal(page.api.loading.value, false)
			page.api.storageItem.value.storageSourceAllParam.corsConfigList = []
			const nextSaving = page.api.submitForm()
			assert.equal(page.api.loading.value, true)
			old.resolve(phase === 'post' ? { data: 901 } : true)
			await oldSaving
			await page.flush()
			assert.equal(page.api.loading.value, true)
			assert.equal(page.api.storageItem.value.id, 902)
			assert.equal(page.api.storageItem.value.key, 'storage-902')
			assert.equal(page.api.saveError.value, '')
			assert.equal(page.pushes.length, 0)
			assert.equal(page.notices.length, 0)
			assert.equal(page.calls.save.length, phase === 'post' ? 1 : 0)
			nextValidation.resolve(true)
			await nextSaving
			assert.equal(page.api.loading.value, false)
			assert.equal(page.calls.save.at(-1)[0].id, 902)
			assert.equal(page.calls.save.length, phase === 'post' ? 2 : 1)
			assert.deepEqual(page.pushes, ['/admin/storage-list'])
			assert.equal(page.notices.length, 1)
		} finally {
			page.unmount()
		}
	})
}

test('malformed or empty storage schemas are recoverable errors and never appear to pass checks or permit a POST', async () => {
	for (const data of [null, {}, [], [null], [{ key: 'filePath' }]]) {
		const page = setupPage({ params: async () => ({ data }) })
		try {
			await page.choose('local')
			complete(page)
			assert.equal(page.api.paramsLoading.value, false)
			assert.ok(page.api.paramsError.value)
			assert.deepEqual(plain(page.api.storageSourceParamList.value), [])
			assert.equal(page.api.advancedFieldCount.value, 0)
			assert.doesNotThrow(() => page.api.checkSetup())
			assert.equal(page.api.setupChecked.value, false)
			await page.api.submitForm()
			assert.equal(page.calls.save.length, 0)
			assert.equal(page.api.loading.value, false)
		} finally {
			page.unmount()
		}
	}
})

test('reloading a damaged schema replaces the error and permits checks only after valid fields arrive', async () => {
	let reads = 0
	const page = setupPage({
		params: async () => ({ data: ++reads === 1 ? null : schema('local') }),
	})
	try {
		await page.choose('local')
		page.api.checkSetup()
		assert.equal(page.api.setupChecked.value, false)
		assert.ok(page.api.paramsError.value)
		await page.api.reloadParams()
		await page.flush()
		complete(page)
		page.api.checkSetup()
		assert.equal(page.api.paramsError.value, '')
		assert.equal(page.api.setupChecked.value, true)
		assert.deepEqual(plain(page.api.setupIssues.value), [])
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('entering S3 credentials does not probe; explicit bucket and CORS reads retain the saved HTTP or HTTPS protocol', async () => {
	for (const protocol of ['http', 'https']) {
		const remote = [
			{
				allowedOrigins: ['https://readonly.example.test'],
				allowedMethods: ['GET'],
			},
		]
		const page = setupPage({
			params: async ({ storageType }) => ({
				data: [...schema(storageType), field('endPointScheme', 'https')],
			}),
			buckets: async () => ({ data: [{ name: 'course-files' }] }),
			cors: async () => ({ data: remote }),
		})
		try {
			await page.choose('s3')
			complete(page, 's3')
			page.api.connectionAddress.value = `${protocol}://s3.example.test:9000/`
			page.api.recognizeAddress()
			page.api.applyAddressSuggestions()
			await page.flush()
			assert.equal(page.calls.buckets.length + page.calls.cors.length, 0)
			assert.equal(page.api.canReadBuckets.value, true)
			assert.equal(page.api.canReadCors.value, true)
			assert.equal(
				page.api.storageItem.value.storageSourceAllParam.endPoint,
				's3.example.test:9000'
			)
			assert.equal(
				page.api.storageItem.value.storageSourceAllParam.endPointScheme,
				protocol
			)
			await page.api.readBuckets()
			await page.api.readCors()
			for (const [credentials] of [...page.calls.buckets, ...page.calls.cors]) {
				assert.equal(credentials.endPoint, `${protocol}://s3.example.test:9000`)
				assert.equal(Object.hasOwn(credentials, 'endPointScheme'), false)
			}
			assert.deepEqual(
				plain(page.api.storageItem.value.storageSourceAllParam.corsConfigList),
				[]
			)
			assert.deepEqual(JSON.parse(page.api.remoteCors.value), remote)
			assert.match(page.api.discoveryMessage.value, /仅展示/)
			assert.equal(page.calls.save.length, 0)
			await page.api.submitForm()
			const saved = page.calls.save[0][0].storageSourceAllParam
			assert.equal(saved.endPoint, 's3.example.test:9000')
			assert.equal(saved.endPointScheme, protocol)
			assert.equal(
				`${saved.endPointScheme}://${saved.endPoint}`,
				page.calls.buckets[0][0].endPoint
			)
			assert.deepEqual(plain(saved.corsConfigList), [])
			assert.equal(page.calls.dialogs.length, 0)
		} finally {
			page.unmount()
		}
	}
})

test('changing S3 connection fields aborts manual discovery and ignores a late read without automatic re-probing', async () => {
	const response = deferred()
	const page = setupPage({ buckets: () => response.promise })
	try {
		await page.choose('s3')
		complete(page, 's3')
		await page.flush()
		const reading = page.api.readBuckets()
		assert.equal(page.api.discoveryBusy.value, 'buckets')
		page.api.storageItem.value.storageSourceAllParam.secretKey =
			'new-synthetic-secret'
		await page.flush()
		assert.equal(page.calls.buckets[0][1].signal.aborted, true)
		assert.equal(page.api.discoveryBusy.value, '')
		response.resolve({ data: [{ name: 'old-bucket' }] })
		await reading
		assert.deepEqual(plain(page.api.bucketList.value), [])
		assert.equal(page.api.discoveryMessage.value, '')
		assert.equal(page.calls.buckets.length, 1)
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('a failed optional bucket read leaves manually entered bucket values usable and never retries or saves automatically', async () => {
	const page = setupPage({
		buckets: async () => {
			throw new Error('synthetic read failure')
		},
	})
	try {
		await page.choose('s3')
		complete(page, 's3')
		await page.flush()
		await page.api.readBuckets()
		assert.equal(page.api.discoveryBusy.value, '')
		assert.match(page.api.discoveryMessage.value, /可保留手动桶名/)
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.bucketName,
			'course-files'
		)
		assert.equal(page.calls.buckets.length, 1)
		assert.equal(page.calls.save.length, 0)
		await page.api.submitForm()
		assert.equal(page.calls.save.length, 1)
		assert.equal(
			page.calls.save[0][0].storageSourceAllParam.bucketName,
			'course-files'
		)
	} finally {
		page.unmount()
	}
})

test('recognition works before selecting a storage type and is entirely offline until a suggestion is explicitly adopted', async () => {
	const page = setupPage()
	try {
		await page.mount()
		page.api.sourceDetectionInput.value = '/data/My Files'
		page.api.recognizeStorageSource()
		assert.equal(page.api.storageItem.value.type, '')
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			null
		)
		assert.equal(page.api.sourceDetectionCandidates.value.length, 1)
		assert.equal(page.api.sourceDetectionCandidates.value[0].type, 'local')
		assert.equal(
			page.api.sourceDetectionCandidates.value[0].suggestions.filePath,
			'/data/My Files'
		)
		assert.match(page.api.sourceDetectionNotice.value, /仅生成建议/)
		for (const calls of Object.values(page.calls)) assert.equal(calls.length, 0)
		const candidate = page.api.sourceDetectionCandidates.value[0]
		await page.api.applyDetectedSource(candidate)
		assert.equal(page.api.storageItem.value.type, 'local')
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			'/data/My Files'
		)
		assert.equal(page.calls.params.length, 1)
		assert.equal(
			page.calls.save.length +
				page.calls.buckets.length +
				page.calls.cors.length,
			0
		)
		assert.equal(page.api.isApplyingDetection.value, false)
	} finally {
		page.unmount()
	}
})

test('an OSS bucket domain produces a supported candidate and adopts bucket, endpoint and protocol without guessing region or credentials', async () => {
	const page = setupPage({
		supported: ['local', 'aliyun'],
		params: async () => ({
			data: [...schema('s3'), field('endPointScheme', 'https')],
		}),
	})
	try {
		await page.mount()
		page.api.storageItem.value.name = '手工课程资料名称'
		page.api.storageItem.value.key = 'course-resources'
		page.api.sourceDetectionInput.value =
			'https://course-notes.oss-cn-hangzhou.aliyuncs.com'
		page.api.recognizeStorageSource()
		const candidate = page.api.sourceDetectionCandidates.value[0]
		assert.equal(candidate.type, 'aliyun')
		assert.equal(page.calls.params.length, 0)
		await page.api.applyDetectedSource(candidate)
		const values = page.api.storageItem.value.storageSourceAllParam
		assert.equal(page.api.storageItem.value.type, 'aliyun')
		assert.equal(page.api.storageItem.value.name, '手工课程资料名称')
		assert.equal(page.api.storageItem.value.key, 'course-resources')
		assert.equal(values.endPoint, 'oss-cn-hangzhou.aliyuncs.com')
		assert.equal(values.bucketName, 'course-notes')
		assert.equal(values.endPointScheme, 'https')
		assert.equal(values.region, '')
		assert.equal(values.accessKey, '')
		assert.equal(values.secretKey, '')
		assert.equal(
			page.calls.save.length +
				page.calls.buckets.length +
				page.calls.cors.length,
			0
		)
	} finally {
		page.unmount()
	}
})

test('an ambiguous HTTP endpoint requires choosing one of the supported alternatives rather than automatically assigning MinIO', async () => {
	const page = setupPage({ supported: ['minio', 's3', 'webdav'] })
	try {
		await page.mount()
		page.api.sourceDetectionInput.value = 'http://files.example.test:9000'
		page.api.recognizeStorageSource()
		assert.deepEqual(
			plain(page.api.sourceDetectionCandidates.value.map(({ type }) => type)),
			['minio', 's3', 'webdav']
		)
		assert.ok(
			page.api.sourceDetectionCandidates.value.every(
				({ confidence }) => confidence === 'possible'
			)
		)
		assert.equal(page.api.storageItem.value.type, '')
		assert.equal(page.calls.params.length, 0)
		assert.match(page.api.sourceDetectionNotice.value, /无法仅凭地址确定/)
		const selected = page.api.sourceDetectionCandidates.value.find(
			({ type }) => type === 'webdav'
		)
		await page.api.applyDetectedSource(selected)
		assert.equal(page.api.storageItem.value.type, 'webdav')
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.url,
			'http://files.example.test:9000/'
		)
		assert.equal(
			page.calls.save.length +
				page.calls.buckets.length +
				page.calls.cors.length,
			0
		)
	} finally {
		page.unmount()
	}
})

test('adopting the already selected storage type reuses its schema and preserves unrelated connection fields', async () => {
	const page = setupPage()
	try {
		await page.mount()
		await page.choose('webdav')
		page.api.storageItem.value.storageSourceAllParam.username = 'retained-user'
		page.api.sourceDetectionInput.value =
			'webdavs://files.example.test/new-dav/'
		page.api.recognizeStorageSource()
		await page.api.applyDetectedSource(
			page.api.sourceDetectionCandidates.value[0]
		)
		assert.equal(page.calls.params.length, 1)
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.username,
			'retained-user'
		)
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.url,
			'https://files.example.test/new-dav/'
		)
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('adoption waits for its schema, prevents duplicate adoption and blocks check or save while connection fields are still loading', async () => {
	const response = deferred()
	const page = setupPage({ params: () => response.promise })
	try {
		await page.mount()
		page.api.sourceDetectionInput.value = '/data/expected-files'
		page.api.recognizeStorageSource()
		const candidate = page.api.sourceDetectionCandidates.value[0]
		const adopting = page.api.applyDetectedSource(candidate)
		await page.flush()
		assert.equal(page.api.isApplyingDetection.value, true)
		assert.equal(page.api.paramsLoading.value, true)
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			null
		)
		await page.api.applyDetectedSource(candidate)
		page.api.checkSetup()
		await page.api.submitForm()
		assert.equal(page.api.setupChecked.value, false)
		assert.equal(page.calls.save.length, 0)
		assert.equal(page.calls.params.length, 1)
		response.resolve({ data: schema('local') })
		await adopting
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			'/data/expected-files'
		)
		assert.equal(page.api.isApplyingDetection.value, false)
		assert.equal(page.api.paramsLoading.value, false)
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('a failed schema does not apply a candidate and a second explicit adoption can retry after recovery', async () => {
	let reads = 0
	const page = setupPage({
		params: async () => ({ data: ++reads === 1 ? null : schema('local') }),
	})
	try {
		await page.mount()
		page.api.sourceDetectionInput.value = '/data/recovered'
		page.api.recognizeStorageSource()
		const candidate = page.api.sourceDetectionCandidates.value[0]
		await page.api.applyDetectedSource(candidate)
		assert.equal(reads, 1)
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			null
		)
		assert.ok(page.api.paramsError.value)
		assert.match(page.api.sourceDetectionNotice.value, /未填入识别结果/)
		assert.equal(page.api.isApplyingDetection.value, false)
		assert.equal(page.calls.save.length, 0)
		await page.api.applyDetectedSource(candidate)
		assert.equal(reads, 2)
		assert.equal(page.api.paramsError.value, '')
		assert.equal(
			page.api.storageItem.value.storageSourceAllParam.filePath,
			'/data/recovered'
		)
		assert.equal(page.api.sourceDetectionCandidates.value.length, 0)
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

for (const invalidate of ['route', 'input', 'account', 'unmount']) {
	test(`changing ${invalidate} while adoption awaits a schema invalidates suggestions and prevents late field fill`, async () => {
		const response = deferred()
		const page = setupPage({ params: () => response.promise })
		let disposed = false
		try {
			await page.mount()
			page.api.sourceDetectionInput.value = '/data/old-suggestion'
			page.api.recognizeStorageSource()
			const adopting = page.api.applyDetectedSource(
				page.api.sourceDetectionCandidates.value[0]
			)
			await page.flush()
			if (invalidate === 'route') await page.navigate('902')
			else if (invalidate === 'input')
				page.api.sourceDetectionInput.value = '/data/new-suggestion'
			else if (invalidate === 'account')
				page.storageStore.loginInfo.username = 'different-account'
			else {
				page.unmount()
				disposed = true
			}
			await page.flush()
			assert.equal(page.api.isApplyingDetection.value, false)
			assert.equal(page.api.sourceDetectionCandidates.value.length, 0)
			response.resolve({ data: schema('local') })
			await adopting
			await page.flush()
			assert.notEqual(
				page.api.storageItem.value.storageSourceAllParam.filePath,
				'/data/old-suggestion'
			)
			if (invalidate === 'route')
				assert.equal(page.api.storageItem.value.id, 902)
			assert.equal(
				page.calls.save.length +
					page.calls.buckets.length +
					page.calls.cors.length,
				0
			)
		} finally {
			if (!disposed) page.unmount()
		}
	})
}

test('adoption never copies unknown, hidden or secret suggestions even when a candidate object is tampered with', async () => {
	const page = setupPage({
		params: async () => ({
			data: [
				field('filePath'),
				field('secretKey', '', false),
				field('hiddenOption', 'retained-hidden', false, { hidden: true }),
			],
		}),
	})
	try {
		await page.mount()
		page.api.sourceDetectionInput.value = '/data/safe-path'
		page.api.recognizeStorageSource()
		const candidate = page.api.sourceDetectionCandidates.value[0]
		Object.assign(candidate.suggestions, {
			unknownOption: 'unapproved',
			hiddenOption: 'must-not-copy',
			secretKey: 'must-not-copy',
			id: 999,
			key: 'must-not-copy',
		})
		await page.api.applyDetectedSource(candidate)
		const values = page.api.storageItem.value.storageSourceAllParam
		assert.equal(values.filePath, '/data/safe-path')
		assert.equal(values.secretKey, '')
		assert.equal(values.hiddenOption, 'retained-hidden')
		assert.equal(values.unknownOption, undefined)
		assert.equal(values.id, undefined)
		assert.equal(values.key, undefined)
		assert.equal(page.api.storageItem.value.id, undefined)
		assert.equal(page.api.storageItem.value.key, undefined)
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('only a current candidate from the server-supported list may be adopted; stale or forged objects are ignored', async () => {
	const page = setupPage({ supported: ['local'] })
	try {
		await page.mount()
		page.api.sourceDetectionInput.value = '/data/original'
		page.api.recognizeStorageSource()
		const old = page.api.sourceDetectionCandidates.value[0]
		await page.api.applyDetectedSource({ ...plain(old) })
		assert.equal(page.calls.params.length, 0)
		page.api.sourceDetectionInput.value = '/data/replacement'
		await page.api.applyDetectedSource(old)
		assert.equal(page.calls.params.length, 0)
		page.api.recognizeStorageSource()
		const current = page.api.sourceDetectionCandidates.value[0]
		page.api.supportStorageType.value = []
		await page.api.applyDetectedSource(current)
		assert.equal(page.calls.params.length, 0)
		assert.equal(page.api.storageItem.value.type, '')
		assert.equal(page.calls.save.length, 0)
	} finally {
		page.unmount()
	}
})

test('valid JSON text CORS from an edited source is normalized into a list for the real editor without clearing saved rules', async () => {
	const rules = [
		{
			allowedOrigins: ['https://example.test'],
			allowedMethods: ['GET'],
			allowedHeaders: ['*'],
			exposeHeaders: [],
		},
	]
	const page = setupPage({
		storageId: '901',
		item: async (id) => ({
			data: storageRecord(id, 's3', {
				endPoint: 's3.example.test',
				accessKey: 'synthetic-id',
				secretKey: 'synthetic-secret',
				bucketName: 'course-files',
				region: 'test-region',
				proxyEnabled: false,
				corsConfigList: JSON.stringify(rules),
			}),
		}),
	})
	try {
		await page.mount()
		assert.equal(page.api.initialError.value, '')
		assert.deepEqual(
			plain(page.api.storageItem.value.storageSourceAllParam.corsConfigList),
			rules
		)
		await page.api.submitForm()
		assert.equal(page.calls.dialogs.length, 0)
		assert.equal(page.calls.save.length, 1)
		assert.deepEqual(
			plain(page.calls.save[0][0].storageSourceAllParam.corsConfigList),
			rules
		)
	} finally {
		page.unmount()
	}
})

test('damaged or incorrectly shaped CORS data is an initial-load error and cannot silently clear rules or save', async () => {
	for (const corsConfigList of [
		'broken-json',
		'{}',
		'[null]',
		'[true]',
		'[{"allowedOrigins":["*"]}]',
		[
			{
				allowedOrigins: ['*'],
				allowedMethods: ['GET'],
				allowedHeaders: 'not-a-list',
			},
		],
	]) {
		const page = setupPage({
			storageId: '901',
			item: async (id) => ({
				data: storageRecord(id, 's3', { corsConfigList }),
			}),
		})
		try {
			await page.mount()
			assert.ok(page.api.initialError.value)
			assert.equal(page.api.storageItem.value.id, undefined)
			assert.equal(page.api.initialLoading.value, false)
			assert.doesNotThrow(() => page.api.checkSetup())
			assert.equal(page.api.setupChecked.value, false)
			await page.api.submitForm()
			assert.equal(page.calls.dialogs.length, 0)
			assert.equal(page.calls.save.length, 0)
		} finally {
			page.unmount()
		}
	}
})

test('manual discovery button disabled bindings evaluate to booleans and cannot leave buttons disabled by an empty-string attribute', () => {
	const findButton = (node, action) => {
		if (
			node.tag === 'el-button' &&
			node.props?.some(
				(prop) =>
					prop.name === 'on' &&
					prop.arg?.content === 'click' &&
					prop.exp?.content === action
			)
		)
			return node
		for (const child of node.children || []) {
			const found = findButton(child, action)
			if (found) return found
		}
	}
	for (const action of ['readBuckets', 'readCors']) {
		const button = findButton(descriptor.template.ast, action)
		assert.ok(button)
		const expression = button.props.find(
			(prop) => prop.name === 'bind' && prop.arg?.content === 'disabled'
		).exp.content
		for (const [busy, expected] of [
			['', false],
			['buckets', true],
			['cors', true],
		]) {
			const value = vm.runInNewContext(expression, {
				canReadBuckets: true,
				canReadCors: true,
				discoveryBusy: busy,
				loading: false,
			})
			assert.equal(typeof value, 'boolean')
			assert.equal(value, expected)
		}
	}
})
