import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as navigation from '../src/utils/admin-navigation.mjs'
import { canAccessAdministration } from '../src/utils/roles.mjs'

// Local component DOM only. No page, network, browser process or authentication storage is accessed.
const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of [
	'window',
	'document',
	'Node',
	'Element',
	'HTMLElement',
	'SVGElement',
	'Event',
	'KeyboardEvent',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
const passthrough = {
	props: ['name', 'persisted'],
	inheritAttrs: false,
	setup:
		(_props, { slots }) =>
		() =>
			slots.default?.(),
}
const icon = { render: () => vue.h('svg', { 'aria-hidden': 'true' }) }

function evaluateSfc(source, modules, auto = {}, inlineTemplate = false) {
	const compiled = compileScript(parse(source).descriptor, {
		id: 'admin-navigation-runtime',
		inlineTemplate,
	}).content
	const executable = compiled
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
		.replace('export default', 'return')
	const autoNames = [
		'ref',
		'computed',
		'watch',
		'useRoute',
		'useRouter',
	].filter((key) => Object.hasOwn(auto, key))
	const inject = autoNames.length
		? `const {${autoNames.join(',')}} = auto;`
		: ''
	return new Function('modules', 'auto', `${inject} ${executable}`)(
		modules,
		auto
	)
}
const navigationSource = await readFile(
	new URL('../src/components/admin/AdminNavigation.vue', import.meta.url),
	'utf8'
)
const layoutSource = await readFile(
	new URL('../src/layouts/admin.vue', import.meta.url),
	'utf8'
)
const Navigation = evaluateSfc(
	navigationSource,
	{
		vue: { ...vue, Transition: passthrough },
		'@heroicons/vue/24/outline': new Proxy({}, { get: () => icon }),
		'~/utils/admin-navigation.mjs': navigation,
	},
	{},
	true
)

function mountNavigation(
	path = '/admin/overview',
	instanceId = 'runtime-admin'
) {
	const state = vue.reactive({
		path,
		expanded: navigation.adminNavigationExpansionForPath(path),
		loggingOut: false,
	})
	const actions = []
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp({
		render: () =>
			vue.h(Navigation, {
				...state,
				instanceId,
				onToggle(id) {
					state.expanded = navigation.toggleAdminNavigationGroup(
						state.expanded,
						id
					)
				},
				onAction(action) {
					actions.push(action)
				},
			}),
	})
	app.component('router-link', {
		props: ['to'],
		setup:
			(props, { attrs, slots }) =>
			() =>
				vue.h('a', { ...attrs, href: props.to }, slots.default?.()),
	})
	app.mount(host)
	return {
		state,
		host,
		actions,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}

test('real navigation starts with daily links including support and one compact advanced entry', () => {
	const view = mountNavigation()
	try {
		assert.deepEqual(
			[...view.host.querySelectorAll('nav > ul > li > a')].map((node) =>
				node.textContent.trim()
			),
			['总览', '工作空间', '空间人员', '存储源', '人工客服']
		)
		const more = view.host.querySelector('.more-toggle')
		assert.equal(more.tagName, 'BUTTON')
		assert.equal(more.type, 'button')
		assert.equal(more.getAttribute('aria-expanded'), 'false')
		const panel = document.getElementById(more.getAttribute('aria-controls'))
		assert.equal(panel.style.display, 'none')
		assert.equal(panel.hasAttribute('inert'), true)
		assert.equal(view.host.querySelector('.navigation-caption'), null)
		assert.equal(
			view.host.querySelector('[aria-current="page"]').getAttribute('href'),
			'/admin/overview'
		)
	} finally {
		view.dispose()
	}
})

test('real navigation opens only the current low-frequency section and manual toggles stay an accordion', async () => {
	const view = mountNavigation('/admin/security-setting')
	try {
		assert.deepEqual([...view.state.expanded], ['more', 'security'])
		assert.equal(
			view.host.querySelector('[aria-current="page"]').getAttribute('href'),
			'/admin/security-setting'
		)
		const control = (id) =>
			view.host.querySelector(`[aria-controls="runtime-admin-${id}"]`)
		assert.equal(control('security').getAttribute('aria-expanded'), 'true')
		assert.equal(control('logs').getAttribute('aria-expanded'), 'false')
		control('logs').click()
		await vue.nextTick()
		assert.deepEqual([...view.state.expanded], ['more', 'logs'])
		assert.equal(control('security').getAttribute('aria-expanded'), 'false')
		assert.equal(
			document.getElementById('runtime-admin-security').hasAttribute('inert'),
			true
		)
		control('logs').click()
		await vue.nextTick()
		assert.deepEqual([...view.state.expanded], ['more'])
		control('more').click()
		await vue.nextTick()
		assert.deepEqual([...view.state.expanded], [])
	} finally {
		view.dispose()
	}
})

test('Escape closes advanced navigation and restores focus to its native button; actions remain explicit', async () => {
	const view = mountNavigation('/admin/login-log')
	try {
		const systemLog = [...view.host.querySelectorAll('button')].find(
			(node) => node.textContent.trim() === '下载系统日志'
		)
		systemLog.click()
		assert.deepEqual(view.actions, ['download-log'])
		const logLink = view.host.querySelector('a[href="/admin/login-log"]')
		logLink.focus()
		const escape = new KeyboardEvent('keydown', {
			key: 'Escape',
			bubbles: true,
			cancelable: true,
		})
		logLink.dispatchEvent(escape)
		await vue.nextTick()
		assert.equal(escape.defaultPrevented, true)
		assert.deepEqual([...view.state.expanded], [])
		assert.equal(
			document.activeElement,
			view.host.querySelector('.more-toggle')
		)
		view.state.loggingOut = true
		await vue.nextTick()
		const logout = [...view.host.querySelectorAll('button')].find((node) =>
			node.textContent.includes('正在退出')
		)
		assert.equal(logout.disabled, true)
		logout.click()
		assert.deepEqual(view.actions, ['download-log'])
	} finally {
		view.dispose()
	}
})

test('desktop and mobile navigation controls have distinct targets and preserve every old destination', () => {
	const desktop = mountNavigation('/admin/overview', 'desktop-runtime')
	const mobile = mountNavigation('/admin/overview', 'mobile-runtime')
	try {
		const controls = [
			...desktop.host.querySelectorAll('[aria-controls]'),
			...mobile.host.querySelectorAll('[aria-controls]'),
		]
		assert.equal(
			new Set(controls.map((node) => node.getAttribute('aria-controls'))).size,
			controls.length
		)
		for (const control of controls)
			assert.ok(document.getElementById(control.getAttribute('aria-controls')))
		for (const item of [
			...navigation.adminPrimaryNavigation,
			...navigation.adminNavigationGroups.flatMap((group) => group.items),
		]) {
			if (item.href)
				assert.ok(desktop.host.querySelector(`a[href="${item.href}"]`))
		}
	} finally {
		desktop.dispose()
		mobile.dispose()
	}
})

test('actual layout watcher resets to the route section, preserves manual collapse until navigation, and keeps role guard', async () => {
	const route = vue.reactive({ path: '/admin/security-setting', meta: {} })
	const redirects = []
	const store = vue.reactive({
		loginInfo: { isLogin: true, isAdmin: true, role: 'ADMIN' },
	})
	const empty = new Proxy({}, { get: () => ({}) })
	const Layout = evaluateSfc(
		layoutSource,
		{
			'@headlessui/vue': empty,
			'@heroicons/vue/24/outline': empty,
			'~/components/BrandLogo.vue': {},
			'~/components/admin/AdminNavigation.vue': Navigation,
			'~/composables/admin/layout/admin-layout': () => ({
				rebuildTitle() {},
				logDownload() {},
			}),
			'~/stores/storage-config': () => store,
			'~/api/home/user': {
				logoutReq() {
					assert.fail('navigation must not log out')
				},
			},
			'~/api/admin/admin-setting': {
				loadConfigReq() {
					assert.fail('navigation must not request all settings')
				},
			},
			'~/utils/roles.mjs': { canAccessAdministration },
			'~/utils/auth-navigation.mjs': { isWorkspaceStorageRoute: (path) => path === '/admin/storage-list' || /^\/admin\/storage-edit(?:\/\d+)?\/?$/.test(path) },
			'~/utils/admin-navigation.mjs': navigation,
		},
		{
			...vue,
			useRoute: () => route,
			useRouter: () => ({ replace: (path) => redirects.push(path) }),
		}
	)
	const scope = vue.effectScope()
	const bindings = scope.run(() => Layout.setup({}, { expose() {} }))
	try {
		assert.deepEqual(bindings.expandedGroups.value, ['more', 'security'])
		bindings.toggleGroup('security')
		await vue.nextTick()
		assert.deepEqual(bindings.expandedGroups.value, ['more'])
		route.path = '/admin/download-link/log'
		await vue.nextTick()
		assert.deepEqual(bindings.expandedGroups.value, ['more', 'logs'])
		bindings.sidebarOpen.value = true
		route.path = '/admin/storage-edit/9'
		await vue.nextTick()
		assert.deepEqual(bindings.expandedGroups.value, [])
		assert.equal(bindings.sidebarOpen.value, false)
		assert.deepEqual(redirects, [])
		store.loginInfo = { isLogin: true, isAdmin: false, role: 'USER' }
		route.path = '/admin/overview'
		await vue.nextTick()
		assert.deepEqual(redirects, ['/'])
	} finally {
		scope.stop()
	}
})
