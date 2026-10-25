import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import * as invitations from '../src/utils/course-invitations.mjs'
import {
	parse,
	compileScript,
	compileTemplate,
	compileStyle,
} from 'vue/compiler-sfc'

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
	url: 'http://127.0.0.1:5173/',
})
for (const key of [
	'window',
	'document',
	'Document',
	'Node',
	'Element',
	'HTMLElement',
	'SVGElement',
	'Event',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
after(() => dom.window.close())
const store = vue.reactive({
	loginInfo: { username: 'member-a', role: 'USER', tenantId: 9, isLogin: true },
})
const route = vue.reactive({ query: {} })
const id = 'a'.repeat(32),
	joinCode = 'b'.repeat(32)
const course = (creator = false) => ({
	id,
	name: '程序设计',
	description: '测试课程',
	enabled: true,
	creator,
	quotaBytes: 104857600,
	usedBytes: 3,
	uploadStatus: creator ? 'OWNER' : 'NONE',
	materialCount: 1,
	memberCount: 1,
})
let calls = [],
	serverCourses = [],
	serverMembers = [],
	serverInvitations = [],
	generatedCount = 0,
	clipboardWrites = [],
	handler,
	alerts = []
Object.defineProperty(window.navigator, 'clipboard', {
	configurable: true,
	value: {
		writeText: async (value) => {
			clipboardWrites.push(value)
		},
	},
})
const messages = { success() {} }
const box = {
	confirm: async () => true,
	alert: async (value) => {
		alerts.push(value)
	},
}
const request = async (path = '', method = 'get', body, config) => {
	calls.push({ path, method, body, config })
	if (handler) {
		const handled = handler(path, method, body, config)
		if (handled !== undefined) return handled
	}
	if (path === '' && method === 'get')
		return { data: structuredClone(serverCourses) }
	if (path.endsWith('/materials'))
		return {
			data: [{ id: 'c'.repeat(32), filename: '课程讲义.txt', sizeBytes: 3 }],
		}
	if (path.endsWith('/members')) return { data: structuredClone(serverMembers) }
	if (path.endsWith('/code') && method === 'post') return { data: joinCode }
	if (path === '/invitation-preview')
		return {
			data: {
				id,
				name: '程序设计',
				description: '来自创建者的课程邀请',
				joined: serverCourses.some((c) => c.id === id),
				creator: serverCourses.some((c) => c.id === id && c.creator),
				expiresAt: null,
			},
		}
	if (path.includes('/invitations?') && method === 'get') {
		const query = new URL(path, 'http://test.local').searchParams
		return {
			data: structuredClone(
				serverInvitations.slice(
					Number(query.get('offset')),
					Number(query.get('offset')) + Number(query.get('limit'))
				)
			),
		}
	}
	if (path.endsWith('/invitations') && method === 'post') {
		generatedCount++
		const value = {
			id: String(generatedCount).padStart(32, 'd'),
			code: String(generatedCount).padStart(32, 'e'),
			createdAt: Date.now(),
			expiresAt: body.expiresAt,
			maxMembers: body.maxMembers,
			revokedAt: 0,
			usedCount: 0,
		}
		serverInvitations.unshift(value)
		return { data: structuredClone(value) }
	}
	if (path.includes('/invitations/') && method === 'delete') {
		serverInvitations.find(
			(entry) => entry.id === path.split('/').at(-1)
		).revokedAt = Date.now()
		return { data: null }
	}
	if (path === '/join') {
		serverCourses = [course()]
		return { data: course() }
	}
	if (path.endsWith('/upload-request'))
		serverCourses[0].uploadStatus = 'PENDING'
	if (path.endsWith('/upload'))
		serverMembers[0].uploadStatus = body.approve ? 'APPROVED' : 'REJECTED'
	if (path === '' && method === 'post') {
		serverCourses = [course(true)]
		return { data: { course: course(true), joinCode } }
	}
	return { data: null }
}
const descriptor = parse(
	await readFile(new URL('../src/pages/courses.vue', import.meta.url), 'utf8')
).descriptor
const compiled = compileScript(descriptor, { id: 'shared-courses-test' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'courses.vue',
	id: 'shared-courses-test',
	compilerOptions: { bindingMetadata: compiled.bindings },
})
assert.deepEqual(template.errors, [])
assert.deepEqual(
	compileStyle({
		source: descriptor.styles[0].content,
		filename: 'courses.vue',
		id: 'course-style',
		scoped: true,
	}).errors,
	[]
)
const modules = {
	vue,
	'element-plus': { ElMessage: messages, ElMessageBox: box },
	'~/stores/storage-config': () => store,
	'vue-router': {
		useRoute: () => route,
		useRouter: () => ({
			replace: async (value) => {
				route.query = value.query
			},
		}),
	},
	'~/api/home/courses': {
		courseRequest: request,
		downloadCourseMaterial: async () => new Blob(['abc']),
		uploadCourseMaterial: async (...args) => {
			calls.push({ upload: args })
			return { data: {} }
		},
	},
	'~/utils/course-invitations.mjs': invitations,
}
const transform = (code) =>
	code
		.replace(
			/import\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"];?/g,
			(_all, names, path) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					path
				)}];`
		)
		.replace(
			/import\s+(\w+)\s+from\s*['"]([^'"]+)['"];?/g,
			(_all, name, path) => `const ${name}=modules[${JSON.stringify(path)}];`
		)
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
const Button = {
	props: ['disabled', 'nativeType'],
	setup:
		(props, { slots, attrs }) =>
		() =>
			vue.h(
				'button',
				{
					...attrs,
					disabled: props.disabled,
					type: props.nativeType || 'button',
				},
				slots.default?.()
			),
}
const Dialog = {
	props: ['modelValue'],
	setup:
		(props, { slots }) =>
		() =>
			props.modelValue
				? vue.h('section', { class: 'test-dialog' }, [
						slots.default?.(),
						slots.footer?.(),
				  ])
				: null,
}
const Link = {
	props: ['to'],
	setup:
		(props, { slots }) =>
		() =>
			vue.h('a', { href: props.to }, slots.default?.()),
}
const settle = async () => {
	for (let i = 0; i < 10; i++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
function mount(role = 'USER', items = [], options = {}) {
	calls = []
	alerts = []
	handler = options.handler
	serverMembers = []
	serverInvitations = []
	generatedCount = 0
	clipboardWrites = []
	serverCourses = items
	route.query = options.query || {}
	store.loginInfo = { username: 'member-a', role, tenantId: 9, isLogin: true }
	const root = document.createElement('div')
	document.body.append(root)
	const app = vue.createApp(Component)
	app.component('el-button', Button)
	app.component('el-dialog', Dialog)
	app.component('router-link', Link)
	app.mount(root)
	return {
		root,
		close: () => {
			app.unmount()
			root.remove()
		},
	}
}
const button = (root, text) =>
	[...root.querySelectorAll('button')].find(
		(node) => node.textContent.trim() === text
	)

test('actual member page joins a course then requests persistent upload permission without exposing creator approvals', async () => {
	const view = mount()
	try {
		await settle()
		assert.equal(button(view.root, '创建课程'), undefined)
		const code = view.root.querySelector('#course-code')
		code.value = joinCode
		code.dispatchEvent(new Event('input', { bubbles: true }))
		await vue.nextTick()
		view.root
			.querySelector('.course-toolbar form')
			.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
		await settle()
		assert.ok(
			calls.some((call) => call.path === '/join' && call.body.code === joinCode)
		)
		assert.match(view.root.textContent, /课程讲义.txt/)
		assert.equal(view.root.querySelector('details'), null)
		assert.equal(button(view.root, '上传资料'), undefined)
		button(view.root, '申请上传权限').click()
		await settle()
		assert.equal(button(view.root, '上传权限待审批').disabled, true)
	} finally {
		view.close()
	}
})

test('a tenant administrator joining another creators course has no creator controls or self approval', async () => {
	const view = mount('TENANT_ADMIN', [course(false)])
	try {
		await settle()
		assert.ok(button(view.root, '创建课程'))
		assert.equal(button(view.root, '设置'), undefined)
		assert.equal(button(view.root, '同意上传'), undefined)
		assert.equal(view.root.querySelector('details'), null)
		assert.ok(button(view.root, '申请上传权限'))
	} finally {
		view.close()
	}
})

test('actual creator controls approve once then revoke ongoing uploads', async () => {
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		serverMembers = [
			{
				userId: 20,
				nickname: '异空间管理员',
				uploadStatus: 'PENDING',
				joinedAt: 1,
			},
		]
		await settle()
		const details = view.root.querySelector('details')
		details.open = true
		details.dispatchEvent(new Event('toggle'))
		await settle()
		assert.match(view.root.textContent, /异空间管理员/)
		button(view.root, '同意上传').click()
		await settle()
		assert.ok(
			calls.some(
				(call) =>
					call.path.endsWith('/members/20/upload') && call.body.approve === true
			)
		)
		button(view.root, '撤销上传').click()
		await settle()
		assert.ok(
			calls.some(
				(call) =>
					call.path.endsWith('/members/20/upload') &&
					call.body.approve === false
			)
		)
		assert.equal(button(view.root, '同意上传'), undefined)
	} finally {
		view.close()
	}
})

test('account switch clears prior course content and cancels outstanding requests before late responses arrive', async () => {
	const view = mount('USER', [course()])
	try {
		await settle()
		assert.match(view.root.textContent, /程序设计/)
		let resolveOld
		handler = () =>
			new Promise((resolve) => {
				resolveOld = resolve
			})
		button(view.root, '刷新').click()
		await settle()
		const oldSignal = calls.at(-1).config.signal
		store.loginInfo = {
			username: 'member-b',
			role: 'USER',
			tenantId: 8,
			isLogin: false,
		}
		await settle()
		assert.equal(oldSignal.aborted, true)
		assert.doesNotMatch(view.root.textContent, /程序设计|课程讲义.txt/)
		resolveOld({ data: [course()] })
		await settle()
		assert.doesNotMatch(view.root.textContent, /程序设计|课程讲义.txt/)
	} finally {
		view.close()
	}
})

test('creator submits a real course draft and proceeds to configurable invitations after successful creation', async () => {
	const view = mount('TENANT_ADMIN')
	try {
		await settle()
		button(view.root, '创建课程').click()
		await vue.nextTick()
		const name = view.root.querySelector('.course-form input')
		name.value = '离散数学'
		name.dispatchEvent(new Event('input', { bubbles: true }))
		await vue.nextTick()
		button(view.root, '保存').click()
		await settle()
		const creation = calls.find(
			(call) => call.path === '' && call.method === 'post'
		)
		assert.equal(creation.body.name, '离散数学')
		assert.equal(creation.body.quotaBytes, 104857600)
		assert.deepEqual(alerts, [])
		assert.ok(view.root.querySelector('.course-invitation-manager'))
		assert.ok(button(view.root, '生成新邀请链接'))
		assert.ok(
			!calls.some(
				(call) => call.path.endsWith('/invitations') && call.method === 'post'
			)
		)
	} finally {
		view.close()
	}
})

test('oversized uploads are rejected before issuing a multipart request', async () => {
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		await settle()
		const input = view.root.querySelector('input[type=file]')
		Object.defineProperty(input, 'files', {
			configurable: true,
			value: [{ name: 'huge.txt', size: 51 * 1048576 }],
		})
		input.dispatchEvent(new Event('change', { bubbles: true }))
		await settle()
		assert.match(view.root.textContent, /50 MB/)
		assert.ok(!calls.some((call) => call.upload))
	} finally {
		view.close()
	}
})

test('creator explicitly creates reusable permanent invitations, copies both forms, and revokes only the selected invitation', async () => {
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		assert.ok(
			calls.some((call) => call.path.endsWith('/invitations?offset=0&limit=20'))
		)
		assert.ok(
			!calls.some(
				(call) => call.path.endsWith('/invitations') && call.method === 'post'
			)
		)
		assert.match(view.root.textContent, /长期有效/)
		button(view.root, '生成新邀请链接').click()
		await settle()
		const first = serverInvitations[0]
		assert.equal(
			calls.find(
				(call) => call.path.endsWith('/invitations') && call.method === 'post'
			).body.expiresAt,
			null
		)
		assert.equal(
			calls.find(
				(call) => call.path.endsWith('/invitations') && call.method === 'post'
			).body.maxMembers,
			null
		)
		const link = view.root.querySelector('input[aria-label="邀请链接"]').value
		assert.equal(link, `http://127.0.0.1:5173/courses?invite=${first.code}`)
		button(view.root, '复制邀请链接').click()
		await settle()
		button(view.root, '复制邀请码').click()
		await settle()
		assert.deepEqual(clipboardWrites, [link, first.code])
		button(view.root, '生成新邀请链接').click()
		await settle()
		assert.equal(serverInvitations.length, 2)
		assert.equal(first.revokedAt, 0)
		assert.notEqual(serverInvitations[0].code, first.code)
		const second = serverInvitations[0]
		button(view.root, '撤销邀请').click()
		await settle()
		assert.ok(second.revokedAt > 0)
		assert.equal(first.revokedAt, 0)
		assert.equal(view.root.querySelector('input[aria-label="邀请链接"]'), null)
		assert.match(view.root.textContent, /已撤销/)
		button(view.root, '关闭邀请窗口').click()
		await settle()
		assert.doesNotMatch(view.root.textContent, new RegExp(first.code))
	} finally {
		view.close()
	}
})

test('creator chooses a bounded expiry or custom future time and cannot generate a past invitation', async () => {
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		const expiry = view.root.querySelector('select[aria-label="邀请有效期"]')
		expiry.value = '7'
		expiry.dispatchEvent(new Event('change', { bubbles: true }))
		await settle()
		const started = Date.now()
		button(view.root, '生成新邀请链接').click()
		await settle()
		assert.ok(serverInvitations[0].expiresAt >= started + 7 * 86400000)
		assert.ok(serverInvitations[0].expiresAt <= Date.now() + 7 * 86400000)
		expiry.value = 'custom'
		expiry.dispatchEvent(new Event('change', { bubbles: true }))
		await settle()
		const input = view.root.querySelector('input[aria-label="邀请失效时间"]')
		input.value = '2000-01-01T12:00'
		input.dispatchEvent(new Event('input', { bubbles: true }))
		await settle()
		assert.equal(button(view.root, '生成新邀请链接').disabled, true)
		const future = new Date(Date.now() + 2 * 86400000)
		const local = `${future.getFullYear()}-${String(
			future.getMonth() + 1
		).padStart(2, '0')}-${String(future.getDate()).padStart(2, '0')}T12:00`
		input.value = local
		input.dispatchEvent(new Event('input', { bubbles: true }))
		await settle()
		assert.equal(button(view.root, '生成新邀请链接').disabled, false)
		button(view.root, '生成新邀请链接').click()
		await settle()
		assert.equal(serverInvitations[0].expiresAt, new Date(local).getTime())
	} finally {
		view.close()
	}
})

test('legacy invitation action retires the old code without displaying an unrestricted replacement', async () => {
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		view.root.querySelector('.legacy-invitation').open = true
		button(view.root, '停用旧版邀请码').click()
		await settle()
		assert.ok(
			calls.some(
				(call) => call.path === `/${id}/code` && call.method === 'post'
			)
		)
		assert.deepEqual(alerts, [])
		assert.doesNotMatch(view.root.textContent, new RegExp(joinCode))
		assert.equal(view.root.querySelector('input[aria-label="邀请链接"]'), null)
	} finally {
		view.close()
	}
})

test('invitation history paginates so earlier invitations remain revocable', async () => {
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		serverInvitations = Array.from({ length: 21 }, (_, n) => ({
			id: String(n).padStart(32, 'a'),
			createdAt: Date.now() - n * 1000,
			expiresAt: null,
			revokedAt: 0,
			usedCount: n,
		}))
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		assert.equal(
			view.root.querySelectorAll('.course-invitations li').length,
			20
		)
		button(view.root, '加载更多邀请').click()
		await settle()
		assert.ok(
			calls.some((call) =>
				call.path.endsWith('/invitations?offset=20&limit=20')
			)
		)
		assert.equal(
			view.root.querySelectorAll('.course-invitations li').length,
			21
		)
		assert.equal(button(view.root, '加载更多邀请'), undefined)
		const buttons = [
			...view.root.querySelectorAll('.course-invitations button'),
		]
		buttons.at(-1).click()
		await settle()
		assert.ok(serverInvitations[20].revokedAt > 0)
	} finally {
		view.close()
	}
})

test('creator sets an invitation-specific member limit and full invitations remain visible for revocation', async () => {
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		const mode = view.root.querySelector('select[aria-label="邀请人数限制"]')
		mode.value = 'limited'
		mode.dispatchEvent(new Event('change', { bubbles: true }))
		await settle()
		const maximum = view.root.querySelector(
			'input[aria-label="邀请码人数上限"]'
		)
		for (const invalid of ['0', '501', '1.5', '']) {
			maximum.value = invalid
			maximum.dispatchEvent(new Event('input', { bubbles: true }))
			await settle()
			assert.equal(button(view.root, '生成新邀请链接').disabled, true)
		}
		maximum.value = '2'
		maximum.dispatchEvent(new Event('input', { bubbles: true }))
		await settle()
		button(view.root, '生成新邀请链接').click()
		await settle()
		assert.equal(serverInvitations[0].maxMembers, 2)
		assert.equal(
			calls.find(
				(call) => call.path.endsWith('/invitations') && call.method === 'post'
			).body.maxMembers,
			2
		)
		serverInvitations[0].usedCount = 2
		button(view.root, '关闭邀请窗口').click()
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		assert.match(view.root.textContent, /人数已满/)
		assert.match(view.root.textContent, /已邀请 2 人 \/ 上限 2 人/)
		assert.ok(button(view.root, '撤销邀请'))
	} finally {
		view.close()
	}
})

test('opening an invitation previews its course and joins only after explicit confirmation', async () => {
	const view = mount('USER', [], { query: { invite: joinCode } })
	try {
		await settle()
		assert.ok(
			calls.some(
				(call) =>
					call.path === '/invitation-preview' && call.body.code === joinCode
			)
		)
		assert.ok(!calls.some((call) => call.path === '/join'))
		assert.match(view.root.textContent, /来自创建者的课程邀请/)
		assert.match(view.root.textContent, /上传资料仍需课程创建者批准/)
		button(view.root, '确认加入课程').click()
		await settle()
		assert.equal(calls.filter((call) => call.path === '/join').length, 1)
		assert.equal(route.query.invite, undefined)
		assert.equal(route.query.course, id)
		assert.match(view.root.textContent, /课程讲义.txt/)
		assert.ok(button(view.root, '申请上传权限'))
	} finally {
		view.close()
	}
})

test('a member can dismiss a pending invitation preview without joining or displaying a late response', async () => {
	let resolvePreview
	const view = mount('USER', [], {
		query: { invite: joinCode },
		handler: (path) =>
			path === '/invitation-preview'
				? new Promise((resolve) => {
						resolvePreview = resolve
				  })
				: undefined,
	})
	try {
		await settle()
		const signal = calls.find((call) => call.path === '/invitation-preview')
			.config.signal
		button(view.root, '取消邀请').click()
		await settle()
		assert.equal(signal.aborted, true)
		assert.equal(route.query.invite, undefined)
		resolvePreview({ data: { id, name: '迟到的课程邀请', joined: false } })
		await settle()
		assert.doesNotMatch(view.root.textContent, /迟到的课程邀请/)
		assert.ok(!calls.some((call) => call.path === '/join'))
	} finally {
		view.close()
	}
})

test('expired invitation shows a recovery action and never posts a join request', async () => {
	const view = mount('USER', [], {
		query: { invite: joinCode },
		handler: (path) =>
			path === '/invitation-preview'
				? Promise.reject({
						response: { data: { msg: '课程邀请已失效，请联系创建者' } },
				  })
				: undefined,
	})
	try {
		await settle()
		assert.match(view.root.textContent, /课程邀请已失效/)
		assert.ok(button(view.root, '重新检查邀请'))
		assert.equal(button(view.root, '确认加入课程'), undefined)
		assert.ok(!calls.some((call) => call.path === '/join'))
	} finally {
		view.close()
	}
})

test('an already joined member opens an invited course without duplicate enrollment', async () => {
	const view = mount('TENANT_ADMIN', [course(false)], {
		query: { invite: joinCode },
	})
	try {
		await settle()
		assert.ok(button(view.root, '打开课程'))
		assert.equal(button(view.root, '邀请成员'), undefined)
		button(view.root, '打开课程').click()
		await settle()
		assert.ok(!calls.some((call) => call.path === '/join'))
		assert.equal(route.query.invite, undefined)
		assert.equal(route.query.course, id)
	} finally {
		view.close()
	}
})

test('account switch clears generated invitation and discards a late creation response', async () => {
	let resolveCreation
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		handler = (path, method) =>
			path.endsWith('/invitations') && method === 'post'
				? new Promise((resolve) => {
						resolveCreation = resolve
				  })
				: undefined
		button(view.root, '生成新邀请链接').click()
		await settle()
		const signal = calls.at(-1).config.signal
		store.loginInfo = {
			username: 'other-member',
			role: 'USER',
			tenantId: 88,
			isLogin: false,
		}
		await settle()
		assert.equal(signal.aborted, true)
		assert.equal(view.root.querySelector('.course-invitation-manager'), null)
		resolveCreation({
			data: {
				id: 'c'.repeat(32),
				code: joinCode,
				createdAt: Date.now(),
				expiresAt: null,
				usedCount: 0,
			},
		})
		await settle()
		assert.doesNotMatch(view.root.textContent, new RegExp(joinCode))
		assert.equal(view.root.querySelector('input[aria-label="邀请链接"]'), null)
		assert.equal(
			calls.filter((call) => call.path.includes('/invitations?')).length,
			1
		)
	} finally {
		view.close()
	}
})

test('changing invitation query aborts the previous preview and cannot render its late result', async () => {
	let resolveFirst
	const secondCode = 'f'.repeat(32)
	const view = mount('USER', [], {
		query: { invite: joinCode },
		handler: (path, _method, body) =>
			path === '/invitation-preview' && body.code === joinCode
				? new Promise((resolve) => {
						resolveFirst = resolve
				  })
				: undefined,
	})
	try {
		await settle()
		const signal = calls.find((call) => call.path === '/invitation-preview')
			.config.signal
		route.query = { invite: secondCode }
		await settle()
		assert.equal(signal.aborted, true)
		resolveFirst({ data: { id, name: '旧邀请的课程', joined: false } })
		await settle()
		assert.doesNotMatch(view.root.textContent, /旧邀请的课程/)
		assert.match(view.root.textContent, /来自创建者的课程邀请/)
		assert.ok(!calls.some((call) => call.path === '/join'))
	} finally {
		view.close()
	}
})

test('clipboard denial leaves the generated invitation available for manual copying', async () => {
	const originalWrite = window.navigator.clipboard.writeText
	const view = mount('TENANT_ADMIN', [course(true)])
	try {
		await settle()
		button(view.root, '邀请成员').click()
		await settle()
		button(view.root, '生成新邀请链接').click()
		await settle()
		window.navigator.clipboard.writeText = async () => {
			throw new Error('Permission denied')
		}
		button(view.root, '复制邀请链接').click()
		await settle()
		assert.match(view.root.textContent, /自动复制未成功.*手动复制/)
		assert.ok(
			view.root
				.querySelector('input[aria-label="邀请链接"]')
				.value.includes('/courses?invite=')
		)
		assert.doesNotMatch(view.root.textContent, /Permission denied/)
	} finally {
		window.navigator.clipboard.writeText = originalWrite
		view.close()
	}
})

test('a failed invitation-history read does not misreport successful course creation as failed', async () => {
	const view = mount('TENANT_ADMIN', [], {
		handler: (path) =>
			path.includes('/invitations?')
				? Promise.reject(new Error('邀请记录暂时不可用'))
				: undefined,
	})
	try {
		await settle()
		button(view.root, '创建课程').click()
		await settle()
		const name = view.root.querySelector('.course-form input')
		name.value = '操作系统'
		name.dispatchEvent(new Event('input', { bubbles: true }))
		await settle()
		button(view.root, '保存').click()
		await settle()
		assert.equal(
			calls.filter((call) => call.path === '' && call.method === 'post').length,
			1
		)
		assert.match(
			view.root.querySelector('.course-invitation-manager').textContent,
			/邀请记录暂时不可用/
		)
		assert.equal(view.root.querySelector('.course-form'), null)
		assert.ok(button(view.root, '生成新邀请链接'))
	} finally {
		view.close()
	}
})
