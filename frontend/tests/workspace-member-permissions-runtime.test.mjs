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
		new URL(
			'../src/components/auth/WorkspaceMemberPermissions.vue',
			import.meta.url
		),
		'utf8'
	)
).descriptor
const compiled = compileScript(descriptor, {
	id: 'workspace-permissions-runtime',
})
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'WorkspaceMemberPermissions.vue',
	id: 'workspace-permissions-runtime',
	compilerOptions: { bindingMetadata: compiled.bindings },
})
assert.deepEqual(template.errors, [])
const transform = (code) =>
	code
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
	for (let count = 0; count < 12; count++) {
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
const record = (id = 21) => ({
	userId: id,
	defaultPermissions: ['preview'],
	allowedDefaultPermissions: ['preview', 'download'],
	permissionOptions: [
		{ value: 'preview', name: '预览' },
		{ value: 'download', name: '下载' },
		{ value: 'delete', name: '删除' },
	],
	storages: [
		{
			storageSourceId: 31,
			storageSourceName: '课程资料',
			rootPath: '/team/course',
			enable: true,
			permissions: ['preview'],
			allowedRootPath: '/team',
			allowedPermissions: ['preview', 'download'],
		},
	],
})

async function mount({
	role = 'TENANT_ADMIN',
	membershipStatus = 'ACTIVE',
	read = async (id) => ({ data: record(id) }),
	write = async () => ({ data: null }),
} = {}) {
	const store = vue.reactive({
		loginInfo: { isLogin: true, role, tenantId: 9, username: 'manager' },
	})
	const member = vue.reactive({
		id: 21,
		username: 'member',
		nickname: '同学',
		membershipStatus,
	})
	const reads = [],
		writes = [],
		events = [],
		busy = [],
		closes = []
	const modules = {
		vue,
		'~/stores/storage-config': () => store,
		'~/utils/latest-request.mjs': { createLatestRequest },
		'~/api/home/workspace-admin': {
			getWorkspaceMemberPermissionsReq: (...args) => {
				reads.push(args)
				return read(...args)
			},
			saveWorkspaceMemberPermissionsReq: (...args) => {
				writes.push(args)
				return write(...args)
			},
		},
	}
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
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(Component, {
		member,
		onSaved: (id) => events.push(id),
		onBusy: (value) => busy.push(value),
		onClose: () => closes.push(true),
	})
	const vm = app.mount(host)
	await flush()
	return {
		host,
		member,
		store,
		reads,
		writes,
		events,
		busy,
		closes,
		api: vm.$.setupState,
		dispose: () => {
			app.unmount()
			host.remove()
		},
	}
}

test('workspace administrator sees only delegable choices and saves only the selected member grant fields', async () => {
	const app = await mount()
	try {
		assert.equal(app.reads.length, 1)
		assert.match(app.host.textContent, /同学的权限/)
		assert.equal(app.host.textContent.includes('删除'), false)
		app.api.draft.storages[0].permissions = ['download']
		await app.api.save()
		assert.equal(app.writes.length, 1)
		assert.equal(app.writes[0][0], 21)
		assert.deepEqual(app.writes[0][1], {
			defaultPermissions: ['preview'],
			storages: [
				{
					storageSourceId: 31,
					rootPath: '/team/course',
					enable: true,
					permissions: ['download'],
					quotaBytes: 5368709120,
				},
			],
		})
		assert.deepEqual(app.events, [21])
	} finally {
		app.dispose()
	}
})

test('workspace manager capacity field is validated and submitted in exact bytes', async () => {
	const app = await mount()
	try {
		const capacity = app.host.querySelector('input[type="number"]')
		assert.ok(capacity)
		for (const value of ['-1', '102401']) {
			capacity.value = value
			capacity.dispatchEvent(new Event('input', { bubbles: true }))
			await flush()
			await app.api.save()
			assert.equal(app.writes.length, 0)
		}
		capacity.value = '2.5'
		capacity.dispatchEvent(new Event('input', { bubbles: true }))
		await flush()
		await app.api.save()
		assert.equal(app.writes[0][1].storages[0].quotaBytes, 2684354560)
	} finally {
		app.dispose()
	}
})

test('global administrators, members and pending applicants have no permission read or save path', async () => {
	for (const options of [
		{ role: 'ADMIN' },
		{ role: 'USER' },
		{ membershipStatus: 'PENDING' },
		{ membershipStatus: 'REJECTED' },
	]) {
		const app = await mount(options)
		try {
			assert.equal(app.reads.length, 0)
			await app.api.save()
			assert.equal(app.writes.length, 0)
			assert.match(app.host.textContent, /仅工作空间管理员/)
		} finally {
			app.dispose()
		}
	}
})

test('late permissions for the previous member cannot replace the newly selected member', async () => {
	const first = deferred()
	const app = await mount({
		read: (id) =>
			id === 21 ? first.promise : Promise.resolve({ data: record(id) }),
	})
	try {
		app.member.id = 22
		await flush()
		assert.equal(app.reads[0][1].signal.aborted, true)
		first.resolve({ data: record(21) })
		await flush()
		assert.equal(app.api.draft.userId, 22)
		await app.api.save()
		assert.equal(app.writes[0][0], 22)
	} finally {
		app.dispose()
	}
})

test('losing administrator role immediately clears grants and discards outstanding reads', async () => {
	const response = deferred()
	const app = await mount({ read: () => response.promise })
	try {
		app.store.loginInfo.role = 'ADMIN'
		assert.equal(app.api.draft, null)
		response.resolve({ data: record() })
		await flush()
		assert.equal(app.api.draft, null)
		await app.api.save()
		assert.equal(app.writes.length, 0)
	} finally {
		app.dispose()
	}
})

test('unknown save retains the draft, does not auto-retry and offers an explicit server reread', async () => {
	const app = await mount({
		write: async () => {
			throw new Error('unknown acknowledgement')
		},
	})
	try {
		app.api.draft.storages[0].permissions = ['download']
		await app.api.save()
		assert.equal(app.writes.length, 1)
		assert.deepEqual(app.api.draft.storages[0].permissions, ['download'])
		assert.match(app.host.textContent, /保存结果未确认/)
		assert.ok(
			[...app.host.querySelectorAll('button')].some(
				(button) => button.textContent.trim() === '重新读取'
			)
		)
		await app.api.load()
		assert.deepEqual(app.api.draft.storages[0].permissions, ['preview'])
	} finally {
		app.dispose()
	}
})

test('unreadable or mismatched target responses cannot become editable permissions', async () => {
	for (const read of [
		async () => {
			throw new Error('offline')
		},
		async () => ({ data: record(99) }),
	]) {
		const app = await mount({ read })
		try {
			assert.equal(app.api.draft, null)
			await app.api.save()
			assert.equal(app.writes.length, 0)
		} finally {
			app.dispose()
		}
	}
})

test('drawer keeps current storage first, advanced defaults collapsed, and actions outside the scroll area', async () => {
	const app = await mount()
	try {
		const body = app.host.querySelector('.permission-body')
		const storage = body.querySelector('.storage-permission')
		const advanced = body.querySelector('.permission-advanced')
		assert.equal(storage.open, true)
		assert.equal(advanced.open, false)
		assert.ok(
			body.textContent.indexOf('当前存储源') <
				body.textContent.indexOf('云盘申请权限预设')
		)
		assert.equal(body.querySelector('.permission-footer'), null)
		assert.ok(app.host.querySelector('.permission-footer .primary'))
		assert.equal(
			app.host.querySelector('.permission-warning'),
			null,
			'ordinary valid permissions do not show an unexplained repair action'
		)
		for (const style of descriptor.styles)
			assert.deepEqual(
				compileStyle({
					source: style.content,
					filename: 'WorkspaceMemberPermissions.vue',
					id: 'workspace-permissions-runtime',
					scoped: true,
				}).errors,
				[]
			)
	} finally {
		app.dispose()
	}
})

test('no delegable source or default actions gives a clear empty state and blocks a useless save', async () => {
	const empty = {
		...record(),
		storages: [],
		defaultPermissions: [],
		allowedDefaultPermissions: [],
	}
	const app = await mount({ read: async () => ({ data: empty }) })
	try {
		assert.match(app.host.textContent, /暂无可委派的存储源/)
		assert.equal(app.host.querySelector('input[type="checkbox"]'), null)
		assert.equal(app.api.canSave, false)
		await app.api.save()
		assert.equal(app.writes.length, 0)
		assert.equal(
			app.host.querySelector('.permission-footer .primary').disabled,
			true
		)
	} finally {
		app.dispose()
	}
	const defaultsOnly = await mount({
		read: async () => ({ data: { ...record(), storages: [] } }),
	})
	try {
		assert.equal(defaultsOnly.api.canSave, true)
		assert.match(defaultsOnly.host.textContent, /审批云盘申请时的权限预设/)
		await defaultsOnly.api.save()
		assert.equal(defaultsOnly.writes.length, 1)
	} finally {
		defaultsOnly.dispose()
	}
})

test('legacy out-of-scope grants require explicit narrowing and cannot silently be submitted', async () => {
	for (const parent of ['/team', '/']) {
		const legacy = record()
		legacy.defaultPermissions.push('delete')
		legacy.storages[0].permissions.push('delete')
		legacy.storages[0].allowedRootPath = parent
		legacy.storages[0].rootPath = parent === '/' ? null : '/team-other'
		const app = await mount({ read: async () => ({ data: legacy }) })
		try {
			assert.equal(app.api.hasScopeConflict, true)
			assert.ok(app.host.querySelector('.permission-warning'))
			await app.api.save()
			assert.equal(app.writes.length, 0)
			assert.deepEqual(app.api.draft.defaultPermissions, ['preview', 'delete'])
			app.api.narrowToAllowed()
			await flush()
			assert.equal(app.api.hasScopeConflict, false)
			assert.equal(app.api.draft.storages[0].rootPath, parent)
			assert.equal(app.host.querySelector('.permission-warning'), null)
			await app.api.save()
			assert.equal(app.writes.length, 1)
			assert.deepEqual(app.writes[0][1].defaultPermissions, ['preview'])
			assert.deepEqual(app.writes[0][1].storages[0].permissions, ['preview'])
		} finally {
			app.dispose()
		}
	}
})

test('busy events keep the parent drawer open until a save settles and cancellation never writes', async () => {
	const response = deferred()
	const app = await mount({ write: () => response.promise })
	try {
		const saving = app.api.save()
		assert.equal(app.busy.at(-1), true)
		app.api.requestClose()
		assert.equal(app.closes.length, 0)
		response.resolve({ data: null })
		await saving
		assert.equal(app.busy.at(-1), false)
		app.api.requestClose()
		assert.equal(app.closes.length, 1)
		assert.equal(app.writes.length, 1)
	} finally {
		app.dispose()
	}
})

test('a failed reload clears old checkbox state and never lets the previous draft be saved', async () => {
	let reads = 0
	const app = await mount({
		read: async () => {
			if (reads++ === 0) return { data: record() }
			throw { response: { data: { msg: '没有权限' } } }
		},
	})
	try {
		assert.ok(app.host.querySelector('input[type="checkbox"]'))
		await app.api.load()
		await flush()
		assert.equal(app.api.draft, null)
		assert.equal(app.host.querySelector('input[type="checkbox"]'), null)
		assert.match(app.host.textContent, /没有权限/)
		await app.api.save()
		assert.equal(app.writes.length, 0)
	} finally {
		app.dispose()
	}
})
