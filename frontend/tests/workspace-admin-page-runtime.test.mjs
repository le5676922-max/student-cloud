import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import {
	parse,
	compileScript,
	compileTemplate,
	compileStyle,
} from 'vue/compiler-sfc'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

// Render the real page and its scoped member table/drawer events in a local DOM.
// HTTP, permission editing and Element Plus are controlled test doubles; no browser page is visited.
const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of [
	'window',
	'document',
	'Document',
	'Node',
	'Element',
	'HTMLElement',
	'HTMLInputElement',
	'SVGElement',
	'Event',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
after(() => dom.window.close())
const descriptor = parse(
	await readFile(
		new URL('../src/pages/workspace-admin.vue', import.meta.url),
		'utf8'
	)
).descriptor
const compiled = compileScript(descriptor, {
	id: 'workspace-admin-page-runtime',
})
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'workspace-admin.vue',
	id: 'workspace-admin-page-runtime',
	compilerOptions: { bindingMetadata: compiled.bindings },
})
assert.deepEqual(template.errors, [])
const registrationDescriptor = parse(
	await readFile(
		new URL(
			'../src/components/auth/WorkspaceRegistrationAdmin.vue',
			import.meta.url
		),
		'utf8'
	)
).descriptor
const registrationScript = compileScript(registrationDescriptor, {
	id: 'workspace-registration-page-runtime',
})
const registrationTemplate = compileTemplate({
	source: registrationDescriptor.template.content,
	filename: 'WorkspaceRegistrationAdmin.vue',
	id: 'workspace-registration-page-runtime',
	compilerOptions: { bindingMetadata: registrationScript.bindings },
})
assert.deepEqual(registrationTemplate.errors, [])
const transform = (code) =>
	code
		// Vite owns env replacement; enabled new features have their own runtime tests.
		.replace(/import\.meta\.env/g, '({})')
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_match, names, module) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					module
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_match, name, module) =>
				`const ${name} = modules[${JSON.stringify(module)}]`
		)
const flush = async () => {
	for (let count = 0; count < 14; count++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
const deferred = () => {
	let resolve, reject
	const promise = new Promise((done, fail) => {
		resolve = done
		reject = fail
	})
	return { resolve, reject, promise }
}
const member = (id = 21, extra = {}) => ({
	id,
	username: `member${id}`,
	nickname: `同学${id}`,
	accountId: `SC${String(id).padStart(10, '0')}`,
	email: '',
	enabled: true,
	membershipStatus: 'ACTIVE',
	...extra,
})
const click = async (button) => {
	assert.ok(button, 'expected operation button')
	button.click()
	await flush()
}
const buttonText = (root, label) =>
	[...root.querySelectorAll('button')].find(
		(node) => node.textContent.trim() === label
	)
const inputValue = async (input, value) => {
	input.value = value
	input.dispatchEvent(new Event('input', { bubbles: true }))
	await flush()
}

async function mount({
	rows = [member()],
	summaryData = {},
	initialPage = 1,
	readMembers,
	create = async () => ({ data: { id: 30 } }),
	createInvitation = async () => ({
		data: { id: 40, code: 'INVITE-LOCAL-ONLY' },
	}),
	resetPassword = async () => ({ data: {} }),
} = {}) {
	const calls = {
			summary: [],
			members: [],
			create: [],
			invitations: [],
			joinRequests: [],
			invitationCreate: [],
			enabled: [],
			password: [],
		},
		notices = []
	const store = vue.reactive({
		loginInfo: {
			isLogin: true,
			role: 'TENANT_ADMIN',
			tenantId: 9,
			username: 'manager',
		},
	})
	const PermissionEditor = {
		props: ['member'],
		emits: ['close', 'busy', 'saved'],
		setup:
			(props, { emit }) =>
			() =>
				vue.h('section', { 'data-permission-editor': props.member.id }, [
					vue.h(
						'button',
						{ type: 'button', onClick: () => emit('busy', true) },
						'开始保存'
					),
					vue.h(
						'button',
						{ type: 'button', onClick: () => emit('close') },
						'取消权限'
					),
					vue.h(
						'button',
						{ type: 'button', onClick: () => emit('saved', props.member.id) },
						'完成保存'
					),
				]),
	}
	const modules = {
		vue,
		'@vueuse/core': { useClipboard: () => ({ copy: async () => {} }) },
		'~/stores/storage-config': () => store,
		'~/components/auth/OwnershipTransfer.vue': { render: () => null },
		'~/utils/latest-request.mjs': { createLatestRequest },
		'element-plus': {
			ElMessage: {
				success: (message) => notices.push(message),
				error: (message) => notices.push(message),
			},
			ElMessageBox: { confirm: async () => true },
		},
		'~/components/auth/WorkspaceMemberPermissions.vue': PermissionEditor,
		'~/components/auth/WorkspaceCloudAccess.vue': {
			render: () => vue.h('div', { 'data-cloud-access': true }),
		},
		'~/api/home/workspace-admin': {
			getWorkspaceInvitationsReq: async () => {
				calls.invitations.push(true)
				return { data: [] }
			},
			getWorkspaceJoinRequestsReq: async () => {
				calls.joinRequests.push(true)
				return { data: [] }
			},
			createWorkspaceInvitationReq: (data) => {
				calls.invitationCreate.push(data)
				return createInvitation(data)
			},
			sendWorkspaceMemberPasswordResetEmailReq: (...args) => {
				calls.password.push(args)
				return resetPassword(...args)
			},
			getWorkspaceSummaryReq: async (config) => {
				calls.summary.push(config)
				return {
					data: {
						tenantName: '课程空间',
						memberCount: rows.length,
						enabledMemberCount: rows.length,
						storageCount: 1,
						...summaryData,
					},
				}
			},
			getWorkspaceMembersReq: (page, search, config) => {
				calls.members.push({ page, search, config })
				return readMembers
					? readMembers(page, search, config)
					: Promise.resolve({
							data: {
								page: calls.members.length === 1 ? initialPage : page,
								total: 60,
								items: rows,
							},
					  })
			},
			createWorkspaceMemberReq: (data) => {
				calls.create.push(data)
				return create(data)
			},
			setWorkspaceMemberEnabledReq: async (...args) => {
				calls.enabled.push(args)
				return { data: {} }
			},
		},
	}
	const RegistrationComponent = new Function(
		'modules',
		transform(registrationScript.content).replace('export default', 'return')
	)(modules)
	RegistrationComponent.render = new Function(
		'modules',
		transform(registrationTemplate.code).replace(
			'export function render',
			'return function render'
		)
	)(modules)
	modules['~/components/auth/WorkspaceRegistrationAdmin.vue'] =
		RegistrationComponent
	const Component = new Function(
		'modules',
		transform(compiled.content).replace('export default', 'return')
	)(modules)
	Component.render = new Function(
		'modules',
		transform(template.code).replace(
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
	app.component('el-button', {
		props: ['disabled', 'loading', 'nativeType'],
		setup:
			(props, { slots }) =>
			() =>
				vue.h(
					'button',
					{
						type: props.nativeType || 'button',
						disabled: !!props.disabled || !!props.loading,
					},
					slots.default?.()
				),
	})
	app.component('el-input', {
		props: ['modelValue', 'type', 'placeholder', 'maxlength'],
		emits: ['update:modelValue', 'clear'],
		setup:
			(props, { emit }) =>
			() =>
				vue.h('input', {
					value: props.modelValue,
					type: props.type || 'text',
					placeholder: props.placeholder,
					maxlength: props.maxlength,
					onInput: (event) => emit('update:modelValue', event.target.value),
				}),
	})
	app.component('el-input-number', {
		props: ['modelValue'],
		emits: ['update:modelValue'],
		setup:
			(props, { emit }) =>
			() =>
				vue.h('input', {
					type: 'number',
					value: props.modelValue,
					onInput: (event) =>
						emit('update:modelValue', Number(event.target.value)),
				}),
	})
	app.component('el-alert', {
		props: ['title'],
		setup: (props) => () => vue.h('aside', { role: 'alert' }, props.title),
	})
	app.component('el-tag', {
		setup:
			(_props, { slots }) =>
			() =>
				vue.h('span', slots.default?.()),
	})
	app.component('el-form', {
		setup:
			(_props, { slots }) =>
			() =>
				vue.h('form', slots.default?.()),
	})
	app.component('el-form-item', {
		props: ['label'],
		setup:
			(props, { slots }) =>
			() =>
				vue.h('label', { 'data-label': props.label }, [
					props.label,
					...(slots.default?.() || []),
				]),
	})
	app.component('el-table', {
		props: ['data', 'rowKey', 'maxHeight'],
		setup(props, { slots }) {
			vue.provide(
				'member-rows',
				vue.computed(() => props.data)
			)
			return () =>
				vue.h(
					'div',
					{
						'data-member-table': true,
						'data-row-key': props.rowKey,
						'data-max-height': props.maxHeight,
					},
					slots.default?.()
				)
		},
	})
	app.component('el-table-column', {
		props: ['label', 'fixed', 'width', 'minWidth'],
		setup(props, { slots }) {
			const rows = vue.inject('member-rows')
			return () =>
				vue.h(
					'div',
					{
						'data-column': props.label,
						'data-fixed': props.fixed,
						'data-width': props.width,
					},
					rows.value.map((row) =>
						vue.h('div', { 'data-row-id': row.id }, slots.default?.({ row }))
					)
				)
		},
	})
	app.component('el-pagination', {
		props: ['currentPage', 'disabled'],
		emits: ['current-change'],
		setup:
			(props, { emit }) =>
			() =>
				vue.h(
					'nav',
					{ 'data-pagination': true },
					vue.h(
						'button',
						{
							type: 'button',
							disabled: props.disabled,
							onClick: () => emit('current-change', props.currentPage + 1),
						},
						'下一页'
					)
				),
	})
	app.component('el-dialog', {
		props: ['modelValue'],
		setup:
			(props, { slots }) =>
			() =>
				props.modelValue
					? vue.h('section', { 'data-create-dialog': true }, [
							...(slots.default?.() || []),
							...(slots.footer?.() || []),
					  ])
					: null,
	})
	app.component('el-drawer', {
		inheritAttrs: false,
		props: {
			modelValue: Boolean,
			appendToBody: Boolean,
			beforeClose: Function,
			showClose: Boolean,
			closeOnClickModal: Boolean,
			closeOnPressEscape: Boolean,
		},
		setup(props, { slots }) {
			return () => {
				const content = props.modelValue
					? vue.h(
							'section',
							{
								'data-drawer': true,
								'data-modal-close': String(props.closeOnClickModal),
								'data-escape-close': String(props.closeOnPressEscape),
							},
							[
								props.showClose
									? vue.h(
											'button',
											{
												type: 'button',
												onClick: () => props.beforeClose(() => {}),
											},
											'关闭抽屉'
									  )
									: null,
								...(slots.default?.() || []),
							]
					  )
					: null
				return props.appendToBody
					? vue.h(vue.Teleport, { to: 'body' }, content)
					: content
			}
		},
	})
	app.mount(host)
	await flush()
	return {
		host,
		bindings,
		store,
		calls,
		notices,
		drawer: () => document.body.querySelector('[data-drawer]'),
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}

test('unavailable storage statistics do not present a stale zero as an empty workspace', async () => {
	const app = await mount({
		summaryData: { storageCount: 0, storageCountAvailable: false },
	})
	try {
		const storageStat = app.host.querySelectorAll('.stats > div')[2]
		assert.equal(storageStat.querySelector('strong').textContent, '—')
		assert.match(storageStat.querySelector('small').textContent, /统计暂不可用/)
		assert.equal(app.host.querySelector('[title^="本空间暂未配置云盘"]'), null)
	} finally {
		app.dispose()
	}
})

test('only admitted actual members can open a body-mounted drawer instead of extending the member page', async () => {
	const rows = [
		member(0),
		member(2),
		member(21),
		member(22, { membershipStatus: 'PENDING' }),
		member(23, { membershipStatus: 'REJECTED' }),
	]
	const app = await mount({ rows })
	try {
		const operations = app.host.querySelector('[data-column="操作"]')
		for (const id of [0, 2, 22, 23]) {
			assert.equal(
				buttonText(
					operations.querySelector(`[data-row-id="${id}"]`),
					'设置权限'
				).disabled,
				true
			)
			app.bindings.openMemberPermissions(
				member(
					id,
					id === 22
						? { membershipStatus: 'PENDING' }
						: id === 23
						? { membershipStatus: 'REJECTED' }
						: {}
				)
			)
			assert.equal(app.bindings.permissionMember.value, null)
		}
		await click(
			buttonText(operations.querySelector('[data-row-id="21"]'), '设置权限')
		)
		assert.equal(app.bindings.permissionMember.value.id, 21)
		assert.ok(app.drawer())
		assert.equal(app.drawer().parentElement, document.body)
		assert.equal(app.host.querySelector('[data-permission-editor]'), null)
		assert.equal(
			app.drawer().querySelector('[data-permission-editor]').dataset
				.permissionEditor,
			'21'
		)
	} finally {
		app.dispose()
	}
})

test('saving blocks drawer dismissal from both parent and child, while a successful save closes and resets busy', async () => {
	const app = await mount()
	try {
		await click(
			buttonText(app.host.querySelector('[data-column="操作"]'), '设置权限')
		)
		await click(buttonText(app.drawer(), '开始保存'))
		assert.equal(app.bindings.permissionSaving.value, true)
		assert.equal(app.drawer().dataset.modalClose, 'false')
		assert.equal(app.drawer().dataset.escapeClose, 'false')
		assert.equal(buttonText(app.drawer(), '关闭抽屉'), undefined)
		let accepted = 0
		app.bindings.closePermissionDrawer(() => accepted++)
		await click(buttonText(app.drawer(), '取消权限'))
		assert.equal(accepted, 0)
		assert.equal(app.bindings.permissionMember.value.id, 21)
		await click(buttonText(app.drawer(), '完成保存'))
		assert.equal(app.bindings.permissionSaving.value, false)
		assert.equal(app.bindings.permissionMember.value, null)
		assert.equal(app.drawer(), null)
		assert.ok(app.notices.includes('成员权限已更新'))
	} finally {
		app.dispose()
	}
})

test('inviting members lazily opens the invitation panel and requires explicit confirmation to create an invitation without an administrator-chosen password', async () => {
	const app = await mount()
	try {
		const panel = app.host.querySelector('details.registration-panel')
		assert.equal(panel.open, false)
		assert.equal(app.calls.invitations.length, 0)
		assert.equal(app.calls.joinRequests.length, 0)
		const invite = buttonText(
			app.host.querySelector('.member-toolbar-actions'),
			'邀请成员'
		)
		await click(invite)
		assert.equal(panel.open, true)
		assert.ok(panel.id)
		assert.equal(invite.getAttribute('aria-controls'), panel.id)
		assert.equal(invite.getAttribute('aria-expanded'), 'true')
		assert.equal(document.activeElement, panel.querySelector('summary'))
		assert.equal(app.calls.invitations.length, 1)
		assert.equal(app.calls.joinRequests.length, 1)
		assert.ok(buttonText(panel, '创建邀请码'))
		assert.equal(app.host.querySelector('[data-create-dialog]'), null)
		assert.equal(app.host.querySelector('input[type="password"]'), null)
		assert.equal(buttonText(app.host, '新增成员'), undefined)
		assert.equal(app.calls.invitationCreate.length, 0)
		assert.equal(app.calls.create.length, 0)
		await click(buttonText(panel, '创建邀请码'))
		const dialog = app.host.querySelector('[data-create-dialog]')
		assert.equal(dialog.querySelector('input[type="password"]'), null)
		assert.equal(app.calls.invitationCreate.length, 0)
		await click(buttonText(dialog, '取消'))
		assert.equal(app.host.querySelector('[data-create-dialog]'), null)
		assert.equal(app.calls.invitationCreate.length, 0)
		await click(buttonText(panel, '创建邀请码'))
		await click(
			buttonText(app.host.querySelector('[data-create-dialog]'), '创建')
		)
		assert.deepEqual(app.calls.invitationCreate, [
			{ expiresInDays: 7, maxUses: 20 },
		])
		assert.equal(
			app.host.querySelector('#workspace-created-invitation').value,
			'INVITE-LOCAL-ONLY'
		)
		assert.equal(app.calls.create.length, 0)
	} finally {
		app.dispose()
	}
})

test('changing identity closes the invitation panel, clears the old result, and ignores a late invitation response', async () => {
	const pending = deferred()
	let created = 0
	const app = await mount({
		createInvitation: () =>
			++created === 1
				? Promise.resolve({ data: { id: 40, code: 'OLD-IDENTITY-CODE' } })
				: pending.promise,
	})
	try {
		const panel = app.host.querySelector('details.registration-panel')
		await click(buttonText(app.host, '邀请成员'))
		await click(buttonText(panel, '创建邀请码'))
		await click(
			buttonText(app.host.querySelector('[data-create-dialog]'), '创建')
		)
		assert.equal(
			app.host.querySelector('#workspace-created-invitation').value,
			'OLD-IDENTITY-CODE'
		)
		app.store.loginInfo.tenantId = 10
		await flush()
		assert.equal(panel.open, false)
		assert.equal(app.bindings.registrationLoaded.value, false)
		assert.equal(app.host.querySelector('.workspace-registration-panels'), null)
		assert.equal(app.host.querySelector('[data-create-dialog]'), null)
		assert.equal(
			buttonText(app.host, '邀请成员').getAttribute('aria-expanded'),
			'false'
		)
		await click(buttonText(app.host, '邀请成员'))
		assert.equal(app.host.querySelector('#workspace-created-invitation'), null)
		await click(buttonText(panel, '创建邀请码'))
		await click(
			buttonText(app.host.querySelector('[data-create-dialog]'), '创建')
		)
		app.store.loginInfo.tenantId = 11
		await flush()
		pending.resolve({ data: { id: 41, code: 'LATE-OLD-IDENTITY-CODE' } })
		await flush()
		assert.equal(panel.open, false)
		assert.equal(app.host.querySelector('[data-create-dialog]'), null)
		await click(buttonText(app.host, '邀请成员'))
		assert.equal(app.host.querySelector('#workspace-created-invitation'), null)
		assert.deepEqual(app.notices, [])
	} finally {
		app.dispose()
	}
})

test('a new search cancels the old request and late rows cannot overwrite the newest result', async () => {
	const old = deferred(),
		current = deferred()
	const app = await mount({
		readMembers: (page, search) =>
			search === 'old'
				? old.promise
				: search === 'new'
				? current.promise
				: Promise.resolve({ data: { page, total: 1, items: [member()] } }),
	})
	try {
		const form = app.host.querySelector('.member-search')
		await inputValue(form.querySelector('input'), 'old')
		form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
		await flush()
		const previous = app.calls.members.at(-1)
		await inputValue(form.querySelector('input'), '  new  ')
		form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
		await flush()
		assert.equal(previous.config.signal.aborted, true)
		assert.equal(app.calls.members.at(-1).search, 'new')
		assert.equal(app.calls.members.at(-1).page, 1)
		current.resolve({ data: { page: 1, total: 1, items: [member(25)] } })
		await flush()
		old.resolve({ data: { page: 1, total: 1, items: [member(26)] } })
		await flush()
		assert.equal(app.bindings.members.value.items[0].id, 25)
		assert.equal(
			app.host
				.querySelector('[data-member-table]')
				.textContent.includes('同学26'),
			false
		)
	} finally {
		app.dispose()
	}
})

test('refresh and pagination preserve the committed query and current page instead of adopting an input draft', async () => {
	const app = await mount({ initialPage: 3 })
	try {
		const search = app.host.querySelector('.member-search input')
		await inputValue(search, 'not-submitted')
		await click(buttonText(app.host, '刷新'))
		assert.equal(app.calls.members.at(-1).page, 3)
		assert.equal(app.calls.members.at(-1).search, '')
		await click(
			buttonText(app.host.querySelector('[data-pagination]'), '下一页')
		)
		assert.equal(app.calls.members.at(-1).page, 4)
		assert.equal(app.calls.members.at(-1).search, '')
		await inputValue(search, '  applied  ')
		app.host
			.querySelector('.member-search')
			.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
		await flush()
		assert.equal(app.calls.members.at(-1).page, 1)
		assert.equal(app.calls.members.at(-1).search, 'applied')
		await click(
			buttonText(app.host.querySelector('[data-pagination]'), '下一页')
		)
		await inputValue(search, 'different-draft')
		await click(buttonText(app.host, '刷新'))
		assert.equal(app.calls.members.at(-1).page, 2)
		assert.equal(app.calls.members.at(-1).search, 'applied')
	} finally {
		app.dispose()
	}
})

test('leaving the page aborts outstanding metadata and member requests', async () => {
	const pending = deferred()
	const app = await mount({ readMembers: () => pending.promise })
	const signal = app.calls.members[0].config.signal
	app.dispose()
	assert.equal(signal.aborted, true)
	assert.equal(app.calls.summary[0].signal.aborted, true)
	pending.resolve({ data: { page: 1, total: 1, items: [member(99)] } })
	await flush()
	assert.equal(app.bindings.members.value.items.length, 0)
})

test('member reset sends email after confirmation without collecting an administrator-chosen password', async () => {
	const response = deferred()
	const app = await mount({
		rows: [member(10, { email: 'member@example.invalid' })],
		resetPassword: () => response.promise,
	})
	try {
		await click(buttonText(app.host, '发送重置邮件'))
		assert.equal(app.calls.password.length, 0)
		const dialog = app.host.querySelector('[data-create-dialog]')
		assert.equal(dialog.querySelector('input[type="password"]'), null)
		assert.match(dialog.textContent, /member@example\.invalid/)
		assert.match(dialog.textContent, /成员自行/)
		await click(buttonText(dialog, '确认发送'))
		assert.equal(app.calls.password.length, 1)
		assert.equal(app.calls.password[0].length, 2)
		assert.equal(app.calls.password[0][0], 10)
		assert.equal(buttonText(dialog, '取消').disabled, true)
		await app.bindings.submitPasswordReset()
		assert.equal(app.calls.password.length, 1)
		response.resolve({ data: {} })
		await flush()
		assert.equal(app.host.querySelector('[data-create-dialog]'), null)
		assert.deepEqual(app.notices, ['重置邮件已发送，新密码由成员自行设置'])
	} finally {
		app.dispose()
	}
})

test('canceling or switching identity closes email confirmation without sending a reset', async () => {
	const app = await mount({
		rows: [member(10, { email: 'member@example.invalid' })],
	})
	try {
		await click(buttonText(app.host, '发送重置邮件'))
		await click(
			buttonText(app.host.querySelector('[data-create-dialog]'), '取消')
		)
		assert.equal(app.calls.password.length, 0)
		await click(buttonText(app.host, '发送重置邮件'))
		app.store.loginInfo.isLogin = false
		await flush()
		assert.equal(app.bindings.members.value.items.length, 0)
		assert.equal(app.bindings.passwordMember.value, null)
		assert.equal(app.host.querySelector('[data-create-dialog]'), null)
	} finally {
		app.dispose()
	}
})

test('losing identity during reset aborts request and ignores a late success message', async () => {
	const response = deferred(),
		app = await mount({
			rows: [member(10, { email: 'member@example.invalid' })],
			resetPassword: () => response.promise,
		})
	try {
		await click(buttonText(app.host, '发送重置邮件'))
		await click(
			buttonText(app.host.querySelector('[data-create-dialog]'), '确认发送')
		)
		app.store.loginInfo.role = 'USER'
		assert.equal(app.calls.password[0][1].signal.aborted, true)
		response.resolve({ data: {} })
		await flush()
		assert.deepEqual(app.notices, [])
		assert.equal(app.bindings.passwordMember.value, null)
	} finally {
		app.dispose()
	}
})

test('members without a bound mailbox cannot trigger reset email or fall back to setting a password', async () => {
	const app = await mount({ rows: [member(10, { email: '' })] })
	try {
		const button = buttonText(app.host, '发送重置邮件')
		assert.equal(button.disabled, true)
		await click(button)
		assert.equal(app.calls.password.length, 0)
		assert.equal(app.host.querySelector('[data-create-dialog]'), null)
	} finally {
		app.dispose()
	}
})

test('SMTP unavailability stays visible and does not claim the member password was changed', async () => {
	const app = await mount({
		rows: [member(10, { email: 'member@example.invalid' })],
		resetPassword: async () => {
			throw {
				response: {
					data: {
						code: 'MAIL_UNAVAILABLE',
						msg: '邮箱服务尚未启用，请联系全局管理员配置SMTP',
					},
				},
			}
		},
	})
	try {
		await click(buttonText(app.host, '发送重置邮件'))
		await click(
			buttonText(app.host.querySelector('[data-create-dialog]'), '确认发送')
		)
		assert.match(app.host.querySelector('[role="alert"]').textContent, /SMTP/)
		assert.equal(app.calls.password.length, 1)
		assert.deepEqual(app.notices, [])
		assert.ok(app.host.querySelector('[data-create-dialog]'))
	} finally {
		app.dispose()
	}
})

test('drawer and table layout keeps an independent scroll area, compact identity columns and mobile theme', () => {
	assert.deepEqual(
		compileStyle({
			source: descriptor.styles[0].content,
			filename: 'workspace-admin.vue',
			id: 'workspace-admin-page-runtime',
			scoped: true,
		}).errors,
		[]
	)
	const style = descriptor.styles[0].content
	assert.match(
		style,
		/\.workspace-permission-drawer \.el-drawer__body[\s\S]*?display: flex;[\s\S]*?min-height: 0;[\s\S]*?padding: 0;/
	)
	assert.match(style, /height: calc\(100dvh - 32px\)/)
	assert.match(style, /@media \(max-width: 640px\)/)
	assert.match(style, /var\(--cloud-canvas/)
	assert.match(style, /prefers-reduced-motion: reduce/)
	assert.match(
		descriptor.template.content,
		/label="操作"[^>]*width="280"[^>]*fixed="right"/
	)
	assert.match(descriptor.template.content, /row-key="id"/)
	assert.doesNotMatch(
		descriptor.template.content,
		/prop="nickname"|prop="username"/
	)
})
