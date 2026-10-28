import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from 'vue/compiler-sfc'
import { shouldMountAssistant } from '../src/utils/assistant-loading.mjs'
import { isPublicRoute } from '../src/utils/auth-navigation.mjs'
import { navigationCredentialIdentity } from '../src/utils/navigation-credential.mjs'
import { createSiteConfigBootstrap } from '../src/utils/site-config-bootstrap.mjs'

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
after(() => dom.window.close())
const source = await readFile(
	new URL('../src/App.vue', import.meta.url),
	'utf8'
)
const compiled = compileScript(parse(source).descriptor, {
	id: 'app-site-bootstrap',
	inlineTemplate: true,
}).content
const transform = (code) =>
	code
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_m, names, path) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					path
				)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_m, name, path) => `const ${name} = modules[${JSON.stringify(path)}]`
		)
		.replace('export default', 'return')
const flush = async () => {
	for (let n = 0; n < 12; n++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}

async function mount(path = '/workspace-admin', request) {
	const route = vue.reactive({ path, fullPath: path, params: {} })
	const credential = vue.ref('fixture-token')
	const store = vue.reactive({
		globalConfig: { accountModel: '' },
		loginInfo: {
			isLogin: true,
			role: 'TENANT_ADMIN',
			username: 'manager',
			tenantId: 3,
		},
	})
	const sourceStore = vue.reactive({ studentCloudConfig: { baseUrl: '' } })
	const state = vue.reactive({
		ready: false,
		loading: true,
		error: '',
		needsInstall: false,
	})
	const calls = [],
		redirects = [],
		pages = [],
		assistants = []
	const bootstrap = request
		? createSiteConfigBootstrap({
				state,
				load: (config) => {
					calls.push(config)
					return request(config)
				},
				apply: (data) => {
					store.globalConfig = data
				},
		  })
		: {
				state,
				ensure: (key) => {
					calls.push(key)
					return Promise.resolve(null)
				},
				reset() {},
				retry() {
					state.error = ''
					state.loading = true
				},
				cancel() {},
		  }
	const modules = {
		vue,
		'@vueuse/core': { useStorage: () => credential },
		'element-plus/es/locale/lang/zh-cn': {},
		'~/stores/storage-config': () => store,
		'~/stores/global-config': () => sourceStore,
		'~/composables/useSiteConfigBootstrap': () => bootstrap,
		'~/utils/auth-navigation.mjs': { isPublicRoute },
		'~/utils/navigation-credential.mjs': { navigationCredentialIdentity },
		'~/utils/assistant-loading.mjs': { shouldMountAssistant },
		'~/composables/useRouterData': () => {},
		'ua-browser': () => ({}),
	}
	const auto = {
		...vue,
		useRoute: () => route,
		useRouter: () => ({ replace: (target) => redirects.push(target) }),
		defineAsyncComponent: () => ({
			setup() {
				assistants.push(store.globalConfig.accountModel)
				return () => vue.h('aside', { class: 'assistant' }, '小芽')
			},
		}),
	}
	const App = new Function(
		'modules',
		'auto',
		'console',
		`const {ref,computed,watch,nextTick,provide,defineAsyncComponent,useRoute,useRouter} = auto; ${transform(
			compiled
		)}`
	)(modules, auto, { log() {} })
	const host = document.createElement('div')
	document.body.append(host)
	const app = vue.createApp(App)
	app.component('el-config-provider', {
		props: ['locale'],
		setup:
			(_p, { slots }) =>
			() =>
				slots.default?.(),
	})
	app.component('router-view', {
		setup() {
			pages.push(store.globalConfig.accountModel)
			return () => vue.h('main', { class: 'business-page' }, route.path)
		},
	})
	app.component('el-button', {
		props: ['disabled', 'loading'],
		setup:
			(p, { attrs, slots }) =>
			() =>
				vue.h(
					'button',
					{ ...attrs, disabled: p.disabled || p.loading },
					slots.default?.()
				),
	})
	app.mount(host)
	await flush()
	return {
		host,
		state,
		store,
		route,
		credential,
		sourceStore,
		calls,
		redirects,
		pages,
		assistants,
		dispose() {
			app.unmount()
			host.remove()
		},
	}
}

test('cold protected default routes and the global assistant wait for confirmed public configuration', async () => {
	for (const path of [
		'/workspace-admin',
		'/courses',
		'/learning',
		'/agent-tasks',
		'/admin/overview',
		'/',
	]) {
		const view = await mount(path)
		try {
			assert.deepEqual(view.pages, [], path)
			assert.deepEqual(view.assistants, [], path)
			assert.equal(view.calls.length, 1)
			view.store.globalConfig = { accountModel: 'independent' }
			view.state.loading = false
			view.state.ready = true
			await flush()
			assert.deepEqual(view.pages, ['independent'])
			assert.deepEqual(view.assistants, ['independent'])
		} finally {
			view.dispose()
		}
	}
})

test('public login, registration and installation entry routes remain usable without site configuration', async () => {
	for (const path of ['/login', '/login/custom-entry', '/install', '/sso']) {
		const view = await mount(path)
		try {
			assert.equal(view.pages.length, 1)
			assert.equal(view.calls.length, 0)
			assert.deepEqual(view.assistants, [])
		} finally {
			view.dispose()
		}
	}
})

test('root failure recovery is visible and never mounts a protected page or assistant before retry succeeds', async () => {
	const view = await mount()
	try {
		view.state.loading = false
		view.state.error = '站点配置读取失败，请重试。'
		await flush()
		assert.match(view.host.textContent, /站点配置读取失败/)
		assert.deepEqual(view.pages, [])
		assert.deepEqual(view.assistants, [])
		const retry = view.host.querySelector('button')
		assert.ok(retry)
		retry.click()
		await flush()
		assert.equal(view.state.loading, true)
		assert.deepEqual(view.pages, [])
	} finally {
		view.dispose()
	}
})

test('root scopes bootstrap to credentials and backend origin and sends an uninstalled site to the public installer', async () => {
	const view = await mount()
	try {
		view.credential.value = 'replacement-token'
		await flush()
		assert.equal(view.calls.length, 2)
		assert.notEqual(view.calls[0], view.calls[1])
		view.sourceStore.studentCloudConfig.baseUrl = 'http://another.local'
		await flush()
		assert.equal(view.calls.length, 3)
		assert.notEqual(view.calls[1], view.calls[2])
		view.state.needsInstall = true
		await flush()
		assert.deepEqual(view.redirects, ['/install'])
	} finally {
		view.dispose()
	}
})

test('the real root and shared loader apply one response before page and assistant setup across protected navigation', async () => {
	let finish
	const pending = new Promise((resolve) => {
		finish = resolve
	})
	const view = await mount('/workspace-admin', () => pending)
	try {
		assert.deepEqual(view.pages, [])
		assert.deepEqual(view.assistants, [])
		assert.equal(view.calls.length, 1)
		finish({ data: { installed: true, accountModel: 'independent' } })
		await flush()
		assert.deepEqual(view.pages, ['independent'])
		assert.deepEqual(view.assistants, ['independent'])
		view.route.path = '/admin/storage-list'
		await flush()
		assert.equal(view.calls.length, 1)
		view.credential.value = 'changed-token'
		await flush()
		assert.equal(view.calls.length, 2)
	} finally {
		view.dispose()
	}
})
