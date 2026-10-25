import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { JSDOM } from 'jsdom'

// Local component rendering only: no browser tab, live account or network request.
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
	url: 'https://cloud.invalid/login',
	pretendToBeVisual: true,
})
for (const key of [
	'window',
	'document',
	'Element',
	'HTMLElement',
	'HTMLInputElement',
	'SVGElement',
	'Node',
	'MutationObserver',
	'Event',
	'MouseEvent',
	'KeyboardEvent',
	'FocusEvent',
]) {
	Object.defineProperty(globalThis, key, {
		configurable: true,
		value: key === 'window' ? dom.window : dom.window[key],
	})
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(
	dom.window
)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(
	dom.window
)
globalThis.ResizeObserver = class {
	observe() {}
	unobserve() {}
	disconnect() {}
}
dom.window.matchMedia = () => ({
	matches: false,
	addEventListener() {},
	removeEventListener() {},
})
const Vue = await import('vue')
const { ElForm, ElFormItem, ElInput, ElCheckbox } = await import('element-plus')
const { parse, compileScript } = await import('vue/compiler-sfc')
const navigation = await import('../src/utils/auth-navigation.mjs')
const preference = await import('../src/utils/login-preference.mjs')
const routerTools = await import('vue-router')
const { descriptor } = parse(
	await readFile(new URL('../src/pages/login.vue', import.meta.url), 'utf8')
)
const compiled = compileScript(descriptor, {
	id: 'login-real-form',
	inlineTemplate: true,
})
after(() => dom.window.close())

function deferred() {
	let resolve
	const promise = new Promise((done) => (resolve = done))
	return { promise, resolve }
}
async function settle() {
	for (let index = 0; index < 8; index++) {
		await Vue.nextTick()
		await new Promise((done) => setTimeout(done, 0))
	}
}

function mountLogin({ rejectLogin = false, navigationFailure } = {}) {
	const navigationPending = deferred()
	const calls = { login: 0, routes: [], messages: [] }
	const empty = () => null
	const modules = {
		vue: Vue,
		'vue-router': routerTools,
		'~/api/home/install': { installStatusReq: async () => ({ data: true }) },
		'~/api/home/user': {
			checkLoginReq: async () => ({ data: { isLogin: false } }),
			loginVerifyImgReq: async () => ({ data: {} }),
			loginVerifyModeReq: async () => ({ data: 'off' }),
			validateLoginEntryReq: async () => ({ data: null }),
			loginReq: async () => {
				calls.login++
				if (rejectLogin)
					throw { response: { status: 200, data: { msg: '账号或密码错误' } } }
				return { data: { token: 'synthetic-token', role: 'USER' } }
			},
		},
		'~/utils/auth-navigation.mjs': navigation,
		'~/utils/login-preference.mjs': preference,
		'@heroicons/vue/24/outline': {
			ArrowRightIcon: empty,
			CloudArrowUpIcon: empty,
			EyeIcon: empty,
			EyeSlashIcon: empty,
		},
		'~/components/auth/LoginCharacters.vue': { default: empty },
		'~/components/auth/RegistrationDialog.vue': { default: empty },
		'~/components/auth/PasswordResetDialog.vue': { default: empty },
	}
	const executable = compiled.content
		.replace(/^import\s+['"][^'"]+['"]\s*;?$/gm, '')
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
		.replace('export default', 'globalThis.LoginPage =')
	const sandbox = vm.createContext({
		...Vue,
		modules,
		window: dom.window,
		document: dom.window.document,
		AbortController,
		useTitle() {},
		useRoute: () => Vue.reactive({ params: {}, query: { redirect: '/' } }),
		useRouter: () => ({
			replace: async (target) => {
				calls.routes.push(target)
				if (navigationFailure) throw navigationFailure
				return navigationPending.promise
			},
		}),
		useDebounceFn: (callback) => callback,
		ElMessage: { success: (message) => calls.messages.push(message) },
	})
	vm.runInContext(executable, sandbox)
	const root = document.createElement('div')
	document.body.append(root)
	const app = Vue.createApp(sandbox.LoginPage)
	for (const [name, component] of Object.entries({
		ElForm,
		ElFormItem,
		ElInput,
		ElCheckbox,
	}))
		app.component(name, component)
	app.component('RouterLink', {
		setup:
			(_props, { slots }) =>
			() =>
				Vue.h('a', slots.default?.()),
	})
	app.mount(root)
	return {
		root,
		calls,
		navigationPending,
		dispose() {
			app.unmount()
			root.remove()
			dom.window.localStorage.clear()
		},
	}
}

async function submit(view) {
	await settle()
	for (const [id, value] of [
		['career-login-username', 'student@example.test'],
		['career-login-password', 'Test-password-123'],
	]) {
		const input = view.root.querySelector(`#${id}`)
		input.value = value
		input.dispatchEvent(new dom.window.Event('input', { bubbles: true }))
		await settle()
	}
	view.root
		.querySelector('form')
		.dispatchEvent(
			new dom.window.Event('submit', { bubbles: true, cancelable: true })
		)
	await settle()
}

test('successful login clears the password without flashing a real Element Plus required-field error while navigation waits', async () => {
	const view = mountLogin()
	try {
		await submit(view)
		// Element Plus debounces inline error visibility by 100 ms.
		await new Promise((done) => setTimeout(done, 150))
		await settle()
		assert.equal(view.calls.login, 1)
		assert.deepEqual(view.calls.routes, ['/'])
		assert.equal(view.root.querySelector('#career-login-password').value, '')
		assert.equal(view.root.querySelectorAll('.el-form-item.is-error').length, 0)
		assert.doesNotMatch(
			view.root.textContent,
			/密码不能为空|请检查标出的登录信息|登录失败/
		)
	} finally {
		view.navigationPending.resolve()
		view.dispose()
	}
})

test('real form still displays an actual rejected password and never navigates', async () => {
	const view = mountLogin({ rejectLogin: true })
	try {
		await submit(view)
		assert.equal(view.calls.login, 1)
		assert.deepEqual(view.calls.routes, [])
		assert.match(view.root.textContent, /账号或密码错误/)
	} finally {
		view.dispose()
	}
})

test('a page-navigation error after a valid login is never presented as a rejected password', async () => {
	const view = mountLogin({
		navigationFailure: new Error('Synthetic route chunk failure'),
	})
	try {
		await submit(view)
		assert.equal(view.calls.login, 1)
		assert.equal(
			dom.window.localStorage.getItem('tongligong-student-cloud-token'),
			'synthetic-token'
		)
		assert.doesNotMatch(
			view.root.textContent,
			/登录失败|检查账号密码|密码不能为空/
		)
		assert.match(view.root.textContent, /登录成功.*页面|已登录.*页面/)
	} finally {
		view.dispose()
	}
})
