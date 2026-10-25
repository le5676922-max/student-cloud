import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as roles from '../src/utils/roles.mjs'
import * as navigation from '../src/utils/admin-navigation.mjs'

// Render the real SFC against local test doubles; no HTTP, database or browser page is opened.
const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of [
	'window',
	'document',
	'Node',
	'Element',
	'HTMLElement',
	'SVGElement',
	'Event',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
const source = await readFile(
	new URL('../src/pages/admin/user-edit/index.vue', import.meta.url),
	'utf8'
)
const descriptor = parse(source).descriptor
const compiled = compileScript(descriptor, { id: 'account-editor-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'index.vue',
	id: 'account-editor-runtime',
	compilerOptions: { bindingMetadata: compiled.bindings },
})
assert.deepEqual(template.errors, [])
const imports = (code) =>
	code
		.replace(
			/import \{([\s\S]*?)\} from ['"]([^'"]+)['"]/g,
			(_match, names, module) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					module
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from ['"]([^'"]+)['"]/g,
			(_match, name, module) =>
				`const ${name} = modules[${JSON.stringify(module)}]`
		)
const icon = { render: () => vue.h('svg', { 'aria-hidden': 'true' }) }
const account = (role = 'USER', id = 12) => ({
	id,
	role,
	tenantId: role === 'ADMIN' ? null : 9,
	username: `user${id}`,
	nickname: '原昵称',
	enable: true,
	defaultPermissions: ['upload'],
	userStorageSourceList: [
		{
			storageSourceId: 31,
			storageSourceName: '本空间资料',
			rootPath: role === 'ADMIN' ? null : '/',
			enable: true,
			permissions: ['available', 'upload'],
		},
	],
})
const flush = async () => {
	for (let step = 0; step < 6; step++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}

async function mountEditor({
	userId = '12',
	record = account(),
	query = {},
	storageRequest,
	loginInfo = { isLogin: true, isAdmin: true, role: 'ADMIN' },
} = {}) {
	const calls = [],
		messages = [],
		saved = [],
		pushes = []
	const route = { params: { userId }, query }
	const store = vue.reactive({
		loginInfo,
	})
	const modules = {
		vue,
		'~/stores/global-config': () => ({
			adminForm: {
				size: 'default',
				labelWidth: '180px',
				labelPosition: 'left',
			},
			adminTable: { size: 'default' },
		}),
		'~/stores/storage-config': () => store,
		'~/utils/roles.mjs': roles,
		'~/utils/admin-navigation.mjs': navigation,
		'@heroicons/vue/24/solid': new Proxy({}, { get: () => icon }),
		'~/api/admin/admin-user': {
			getUserInfoReq: async (id) => {
				calls.push(['account', id])
				return { data: structuredClone(record) }
			},
			checkUsernameDuplicateReq: async () => ({ data: false }),
			saveOrUpdateUserReq: async (payload) => {
				saved.push(JSON.parse(JSON.stringify(payload)))
				return { data: { id: record?.id || 23 } }
			},
		},
		'~/api/admin/admin-storage': {
			loadStorageListReq: async () => {
				calls.push(['storages'])
				return storageRequest
					? storageRequest()
					: {
							data: [
								{ id: 31, name: '本空间资料', tenantId: 9 },
								{ id: 32, name: '另一空间', tenantId: 10 },
							],
					  }
			},
		},
		'~/api/admin/admin-permission': {
			getPermissionListReq: async () => {
				calls.push(['permissions'])
				return {
					data: [
						{ name: '上传', value: 'upload' },
						{ name: '下载', value: 'download' },
					],
				}
			},
		},
		'~/api/admin/admin-tenant': {
			getTenantListReq: async () => {
				calls.push(['tenants'])
				return {
					data: [
						{ id: 9, name: '空间一', enable: true },
						{ id: 10, name: '空间二', enable: true },
					],
				}
			},
		},
		'~/utils': { getSize: (list) => list?.length || 0, isMobile: false },
	}
	const auto = {
		ref: vue.ref,
		computed: vue.computed,
		onMounted: vue.onMounted,
		useRoute: () => route,
		useRouter: () => ({ push: (path) => pushes.push(path) }),
		ElMessage: {
			error: (message) => messages.push(['error', message]),
			success: (message) => messages.push(['success', message]),
		},
	}
	const Component = new Function(
		'modules',
		'auto',
		`const {${Object.keys(auto).join(',')}} = auto; ${imports(
			compiled.content
		).replace('export default', 'return')}`
	)(modules, auto)
	Component.render = new Function(
		'modules',
		imports(template.code).replace(
			'export function render',
			'return function render'
		)
	)(modules)
	let bindings
	const setup = Component.setup
	Component.setup = (props, context) => {
		bindings = setup(props, context)
		return bindings
	}
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(Component)
	app.directive('loading', {})
	app.component('router-link', {
		props: ['to'],
		setup:
			(props, { slots }) =>
			() =>
				vue.h('a', { href: props.to }, slots.default?.()),
	})
	app.component('el-form', {
		props: ['model', 'rules'],
		setup(_props, { slots, expose }) {
			expose({ validate: (callback) => callback(true) })
			return () => vue.h('form', slots.default?.())
		},
	})
	app.component('el-form-item', {
		props: ['label'],
		setup:
			(props, { slots }) =>
			() =>
				vue.h('div', { 'data-label': props.label }, slots.default?.()),
	})
	app.component('el-alert', {
		props: ['title'],
		setup: (props) => () => vue.h('aside', props.title),
	})
	app.component('el-input', {
		props: ['modelValue', 'type'],
		setup: (props) => () =>
			vue.h('input', { value: props.modelValue, type: props.type }),
	})
	app.component('el-dialog', {
		props: ['modelValue'],
		setup:
			(props, { slots }) =>
			() =>
				props.modelValue
					? vue.h('div', { 'data-dialog': true }, slots.default?.())
					: null,
	})
	app.component('el-table', {
		props: ['data'],
		setup(props, { slots, expose }) {
			expose({ getSelectionRows: () => props.data })
			return () => vue.h('div', slots.default?.())
		},
	})
	app.component('el-table-column', { setup: () => () => null })
	for (const name of [
		'cancel',
		'el-button',
		'el-button-group',
		'el-select',
		'el-option',
		'el-checkbox',
		'el-checkbox-group',
		'el-tag',
		'el-switch',
		'el-popover',
		'el-tooltip',
		'i-mdi-check-circle',
		'i-mdi-close-circle',
		'i-mdi-shuffle-variant',
	])
		app.component(name, {
			setup:
				(_props, { slots }) =>
				() =>
					vue.h('div', { 'data-component': name }, slots.default?.()),
		})
	app.mount(host)
	await flush()
	return {
		bindings,
		host,
		calls,
		messages,
		saved,
		pushes,
		store,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}

test('global administrator keeps editable profile and password but no permission widgets or grant requests', async () => {
	const view = await mountEditor({ userId: '1', record: account('ADMIN', 1) })
	try {
		assert.equal(view.bindings.editorReady.value, true)
		assert.equal(view.bindings.isGlobalAccount.value, true)
		assert.equal(view.bindings.canEditPermissions.value, false)
		assert.deepEqual(view.calls, [['account', '1']])
		const labels = [...view.host.querySelectorAll('[data-label]')].map(
			(node) => node.dataset.label
		)
		for (const label of ['用户名', '用户昵称', '密码', '启用状态'])
			assert.ok(labels.includes(label))
		for (const label of [
			'新增存储源默认权限',
			'当前账号存储授权',
			'所属工作空间',
		])
			assert.equal(labels.includes(label), false)
		assert.match(view.host.textContent, /固定启用|固定拥有最高管理权限/)
		view.bindings.openSetPermissionDialog(null)
		assert.equal(view.bindings.showSetPermissionDialog.value, false)
		assert.equal(view.host.querySelector('[data-dialog]'), null)
	} finally {
		view.dispose()
	}
})

test('saving administrator submits profile fields only even if legacy grant data is invalid', async () => {
	const view = await mountEditor({ userId: '1', record: account('ADMIN', 1) })
	try {
		view.bindings.data.value.nickname = '新昵称'
		view.bindings.data.value.password = 'replacement-password'
		view.bindings.data.value.enable = false
		view.bindings.data.value.defaultPermissions = []
		view.bindings.saveAction()
		await flush()
		assert.equal(view.saved.length, 1)
		assert.deepEqual(view.saved[0], {
			id: 1,
			username: 'user1',
			nickname: '新昵称',
			password: 'replacement-password',
			role: 'ADMIN',
			enable: true,
		})
		for (const field of [
			'defaultPermissions',
			'userStorageSourceList',
			'tenantId',
			'salt',
		])
			assert.equal(Object.hasOwn(view.saved[0], field), false)
	} finally {
		view.dispose()
	}
})

test('global profile edit excludes member grants, identity and membership fields', async () => {
	const record = {
		...account(),
		accountId: 'SC0000000012',
		email: 'member@example.invalid',
		salt: 'legacy-secret-metadata',
		membershipStatus: 'ACTIVE',
	}
	record.userStorageSourceList[0].id = 411
	record.userStorageSourceList[0].userId = 12
	record.userStorageSourceList.push({
		storageSourceId: 32,
		rootPath: '/restricted',
		enable: true,
		permissions: ['download'],
	})
	const view = await mountEditor({ record })
	try {
		view.bindings.saveAction()
		await flush()
		assert.equal(view.saved.length, 1)
		assert.deepEqual(Object.keys(view.saved[0]).sort(), [
			'enable',
			'id',
			'nickname',
			'password',
			'role',
			'tenantId',
			'username',
		])
		assert.equal(Object.hasOwn(view.saved[0], 'defaultPermissions'), false)
		assert.equal(Object.hasOwn(view.saved[0], 'userStorageSourceList'), false)
	} finally {
		view.dispose()
	}
})

test('changing workspace in global profile editing does not load or write authorizations', async () => {
	const view = await mountEditor()
	try {
		view.bindings.data.value.tenantId = 10
		await view.bindings.handleTenantChange()
		assert.deepEqual(
			view.bindings.data.value.userStorageSourceList.map((row) => ({
				id: row.storageSourceId,
				enable: row.enable,
				permissions: [...row.permissions],
			})),
			[]
		)
		assert.equal(
			view.calls.some(
				([kind]) => kind === 'storages' || kind === 'permissions'
			),
			false
		)
	} finally {
		view.dispose()
	}
})

test('non-global logins cannot read or save the global account editor', async () => {
	for (const loginInfo of [
		{ isLogin: false, isAdmin: true, role: 'ADMIN' },
		{ isLogin: true, isAdmin: false, role: 'ADMIN' },
		{ isLogin: true, isAdmin: true, role: 'TENANT_ADMIN' },
		{ isLogin: true, isAdmin: true, role: 'USER' },
	]) {
		const view = await mountEditor({ loginInfo })
		try {
			assert.ok(view.bindings.editorError.value)
			assert.equal(view.bindings.canEditPermissions.value, false)
			assert.deepEqual(view.calls, [])
			view.bindings.saveAction()
			assert.deepEqual(view.saved, [])
		} finally {
			view.dispose()
		}
	}
})

test('mismatched account responses cannot become a different editable target', async () => {
	const view = await mountEditor({ userId: '12', record: account('USER', 13) })
	try {
		assert.ok(view.bindings.editorError.value)
		assert.equal(view.bindings.editorReady.value, false)
		assert.equal(view.bindings.canEditPermissions.value, false)
		assert.deepEqual(view.calls, [['account', '12']])
		view.bindings.saveAction()
		assert.deepEqual(view.saved, [])
	} finally {
		view.dispose()
	}
})

for (const role of ['USER', 'TENANT_ADMIN'])
	test(`global editing of a ${role} account exposes only profile and no grant save`, async () => {
		const view = await mountEditor({ record: account(role) })
		try {
			assert.equal(view.bindings.canEditPermissions.value, false)
			assert.equal(
				view.host.querySelector('[data-label="新增存储源默认权限"]'),
				null
			)
			assert.equal(
				view.host.querySelector('[data-label="当前账号存储授权"]'),
				null
			)
			assert.deepEqual(view.calls, [['account', '12'], ['tenants']])
			view.bindings.openSetPermissionDialog(
				view.bindings.data.value.userStorageSourceList[0]
			)
			view.bindings.currentOpenPermissions.value = ['download']
			assert.equal(view.bindings.showSetPermissionDialog.value, false)
			view.bindings.enterSetPermissionDialog()
			view.bindings.saveAction()
			await flush()
			assert.equal(view.saved.length, 1, JSON.stringify(view.messages))
			assert.equal(view.saved[0].role, role)
			assert.equal(view.saved[0].tenantId, 9)
			assert.equal(Object.hasOwn(view.saved[0], 'defaultPermissions'), false)
			assert.equal(Object.hasOwn(view.saved[0], 'userStorageSourceList'), false)
		} finally {
			view.dispose()
		}
	})

test('retired template and reserved or malformed account targets are rejected before any API read', async () => {
	for (const userId of ['0', '2', '00', '-1', '2147483648']) {
		const view = await mountEditor({ userId })
		try {
			assert.ok(view.bindings.editorError.value)
			assert.ok(view.host.querySelector('[role="alert"]'))
			assert.equal(view.host.querySelector('form'), null)
			assert.deepEqual(view.calls, [])
			view.bindings.saveAction()
			assert.deepEqual(view.saved, [])
		} finally {
			view.dispose()
		}
	}
})

test('new accounts require a selected workspace but never load permissions in the global editor', async () => {
	const view = await mountEditor({ userId: null })
	try {
		assert.equal(view.bindings.data.value.id, null)
		assert.equal(view.bindings.data.value.tenantId, null)
		assert.equal(view.bindings.canEditPermissions.value, false)
		assert.deepEqual(view.calls, [['tenants']])
		view.bindings.saveAction()
		assert.equal(view.saved.length, 0)
		view.bindings.data.value.tenantId = 9
		await view.bindings.handleTenantChange()
		await flush()
		assert.equal(view.bindings.canEditPermissions.value, false)
		assert.deepEqual(
			view.bindings.data.value.userStorageSourceList.map(
				(row) => row.storageSourceId
			),
			[]
		)
	} finally {
		view.dispose()
	}
})

test('global account workspace changes never request grant data at all', async () => {
	let storageReads = 0
	const view = await mountEditor({
		userId: null,
		storageRequest: async () => {
			storageReads++
			return { data: [] }
		},
	})
	try {
		view.bindings.data.value.tenantId = 9
		const loading = view.bindings.handleTenantChange()
		await flush()
		view.bindings.data.value.tenantId = null
		await view.bindings.handleTenantChange()
		await loading
		assert.equal(storageReads, 0)
		assert.deepEqual(view.bindings.data.value.userStorageSourceList, [])
		assert.equal(view.bindings.canEditPermissions.value, false)
		assert.equal(view.bindings.accessLoading.value, false)
	} finally {
		view.dispose()
	}
})

test('SSO help removes public template editing while keeping provider configuration and internal compatibility', async () => {
	const sso = await readFile(
		new URL('../src/pages/admin/sso/index.vue', import.meta.url),
		'utf8'
	)
	const parsed = parse(sso).descriptor
	assert.doesNotMatch(parsed.template.content, /\/admin\/user-edit\/0/)
	assert.match(parsed.template.content, /内部兼容默认规则仍由系统保留/)
	assert.match(parsed.template.content, /选定具体账号/)
	assert.match(parsed.scriptSetup.content, /useSsoConfig/)
	assert.match(parsed.template.content, /beforeSaveOrUpdate\(item\)/)
	assert.deepEqual(
		compileTemplate({
			source: parsed.template.content,
			filename: 'sso.vue',
			id: 'sso-permission-help',
		}).errors,
		[]
	)
})
