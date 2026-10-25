import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import AsyncValidator from 'async-validator'

const Schema = AsyncValidator.default || AsyncValidator
const source = await readFile(
	new URL('../src/pages/admin/site-setting.vue', import.meta.url),
	'utf8'
)
const sharedSource = await readFile(
	new URL('../src/composables/admin/useAdminSetting.js', import.meta.url),
	'utf8'
)
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'site-settings-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'site-setting.vue',
	id: 'site-settings-runtime',
	compilerOptions: { bindingMetadata: script.bindings },
})
assert.deepEqual(template.errors, [])
const flush = async () => {
	for (let index = 0; index < 6; index++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
const plain = (value) => JSON.parse(JSON.stringify(value))
const settings = () => ({
	siteName: '学生云盘',
	siteHomeName: '首页',
	icp: '',
	maxFileUploads: 3,
	faviconUrl: '',
	siteHomeLogo: '',
	siteHomeLogoLink: '',
	siteHomeLogoTargetMode: '_blank',
	forceBackendAddress: '',
	frontDomain: '',
	siteAdminLogoTargetMode: '_self',
	siteAdminVersionOpenChangeLog: false,
})
function execute(code, modules, globals) {
	const executable = code
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
		.replace('export function render', 'return function render')
	return vm.runInNewContext(`(function(){${executable}\n})()`, {
		...vue,
		modules,
		...globals,
	})
}

/** Real page setup/render and shared save flow; replace only transport/UI boundaries. */
function setupPage(initial = settings()) {
	const calls = {
		config: 0,
		storage: 0,
		users: 0,
		addresses: 0,
		headers: 0,
		saves: [],
		validations: 0,
		scrolls: [],
	}
	const mounted = [],
		modules = {}
	const globals = {
		onMounted: (callback) => mounted.push(callback),
		useRouter: () => ({ push() {} }),
		window: { location: { origin: 'http://example.test' } },
		ElMessage() {},
	}
	modules.vue = {
		...vue,
		resolveComponent: (name) => ({ name }),
		resolveDirective: () => null,
		withDirectives: (node) => node,
	}
	modules['@heroicons/vue/24/solid'] = Object.fromEntries(
		[
			'DocumentTextIcon',
			'LinkIcon',
			'CheckBadgeIcon',
			'ShieldCheckIcon',
			'HomeIcon',
			'PhotoIcon',
			'ExclamationTriangleIcon',
		].map((name) => [name, { name }])
	)
	modules['@heroicons/vue/24/outline'] = {
		CloudArrowUpIcon: { name: 'CloudArrowUpIcon' },
	}
	modules['@soerenmartius/vue3-clipboard'] = { toClipboard: async () => {} }
	modules['~/utils'] = {
		fileToBase64: async () => 'data:image/png;base64,draft',
	}
	modules['~/stores/global-config'] = {
		default: () => ({
			adminForm: {
				labelWidth: '150px',
				labelPosition: 'left',
				size: 'default',
			},
			studentCloudConfig: { baseUrl: '' },
			serverAddress: 'http://example.test',
		}),
	}
	modules['~/api/admin/admin-setting'] = {
		loadConfigReq: async () => {
			calls.config++
			return { data: initial }
		},
		updateSiteSettingReq: async (data) => {
			calls.saves.push(plain(data))
			return {}
		},
		getServerAddressReq: async () => {
			calls.addresses++
			return { data: 'http://example.test' }
		},
		getServerHeadersReq: async () => {
			calls.headers++
			return { data: '' }
		},
	}
	modules['~/api/admin/admin-storage'] = {
		loadStorageListReq: async () => {
			calls.storage++
			return { data: [] }
		},
	}
	modules['~/api/admin/admin-user'] = {
		getUserListReq: async () => {
			calls.users++
			return { data: [] }
		},
	}
	modules['vue-request'] = {
		useRequest(request, options) {
			const loading = vue.ref(!options.manual)
			const run = (...args) =>
				Promise.resolve()
					.then(() => request(...args))
					.then((data) => {
						options.onSuccess?.(data)
						return data
					})
					.finally(() => {
						loading.value = false
					})
			if (!options.manual) run()
			return { loading, run, refresh: run }
		},
	}
	modules['~/composables/admin/useAdminSetting'] = {
		default: execute(sharedSource, modules, globals),
	}
	const Page = execute(script.content, modules, globals),
		render = execute(template.code, modules, globals)
	const scope = vue.effectScope()
	const bindings = scope.run(() => Page.setup({}, { expose() {} }))
	const setup = vue.proxyRefs(bindings)
	let validating = Promise.resolve()
	const page = {
		bindings,
		calls,
		render: () => render({}, [], {}, setup, {}, {}),
		async mount() {
			mounted.forEach((callback) => callback())
			await flush()
		},
		async finishValidation() {
			await validating
			await flush()
		},
		dispose() {
			scope.stop()
		},
	}
	bindings.settingFormRef.value = {
		validate(callback) {
			calls.validations++
			const validator = new Schema(plain(bindings.rules.value))
			validating = validator.validate(plain(bindings.siteSetting.value)).then(
				() => callback(true),
				({ fields }) => {
					const form = page.render()
					for (const [prop, errors] of Object.entries(fields))
						form.props.onValidate(prop, false, errors[0].message)
					callback(false, fields)
				}
			)
		},
		scrollToField(prop) {
			calls.scrolls.push({
				prop,
				brandingOpen: bindings.expandedSections.value.branding,
				connectionOpen: bindings.expandedSections.value.connection,
			})
		},
	}
	return page
}

function flatten(node, result = [], closed = false) {
	if (Array.isArray(node)) {
		node.forEach((child) => flatten(child, result, closed))
		return result
	}
	if (!vue.isVNode(node)) return result
	result.push({ node, closed })
	const childrenClosed = closed || (node.type === 'details' && !node.props.open)
	if (Array.isArray(node.children))
		flatten(node.children, result, childrenClosed)
	else if (node.children && typeof node.children === 'object')
		for (const slot of ['default', 'append', 'trigger', 'reference'])
			if (node.children[slot])
				flatten(node.children[slot](), result, childrenClosed)
	return result
}
const tree = (page) => flatten(page.render())
const text = (node) => {
	if (Array.isArray(node)) return node.map(text).join('')
	if (!vue.isVNode(node)) return typeof node === 'string' ? node : ''
	if (typeof node.children === 'string') return node.children
	if (Array.isArray(node.children)) return text(node.children)
	return node.children?.default ? text(node.children.default()) : ''
}
const toggle = (node, open) => node.props.onToggle({ currentTarget: { open } })
const saveButton = (page) =>
	tree(page).find(
		({ node }) =>
			node.type?.name === 'el-button' && text(node).trim() === '保存设置'
	)

test('site settings show four common fields and keep both advanced groups mounted but closed', async () => {
	const page = setupPage()
	try {
		await page.mount()
		const nodes = tree(page)
		const visible = nodes.filter(
			({ node, closed }) =>
				node.type?.name === 'el-form-item' && node.props.label && !closed
		)
		assert.deepEqual(
			visible.map(({ node }) => node.props.label),
			['站点名称', '根路径名称', '备案号', '最大同时上传文件数']
		)
		assert.equal(
			nodes.filter(
				({ node }) => node.type?.name === 'el-form-item' && node.props.label
			).length,
			12
		)
		assert.ok(
			nodes
				.filter(({ node }) => node.type === 'details')
				.every(({ node }) => node.props.open === false)
		)
		assert.equal(saveButton(page).closed, false)
		assert.equal(page.calls.config, 1)
		assert.deepEqual(
			[page.calls.storage, page.calls.addresses, page.calls.headers],
			[1, 1, 1]
		)
	} finally {
		page.dispose()
	}
})

test('closing and reopening branding retains actual v-model drafts and preview state without fetching settings', async () => {
	const page = setupPage()
	try {
		await page.mount()
		let nodes = tree(page)
		toggle(nodes.find(({ node }) => node.type === 'details').node, true)
		nodes = tree(page)
		const favicon = nodes.find(
			({ node }) => node.props?.id === 'faviconUrl'
		).node
		favicon.props['onUpdate:modelValue']('https://example.test/draft.ico')
		page.bindings.siteSetting.value.siteHomeLogo = 'data:image/png;base64,draft'
		page.bindings.siteHomeLogoShow.value = true
		toggle(tree(page).find(({ node }) => node.type === 'details').node, false)
		assert.equal(
			tree(page).find(({ node }) => node.props?.id === 'faviconUrl').closed,
			true
		)
		assert.equal(page.bindings.siteHomeLogoShow.value, true)
		toggle(tree(page).find(({ node }) => node.type === 'details').node, true)
		assert.equal(
			tree(page).find(({ node }) => node.props?.id === 'faviconUrl').node.props
				.modelValue,
			'https://example.test/draft.ico'
		)
		assert.equal(
			page.bindings.siteSetting.value.siteHomeLogo,
			'data:image/png;base64,draft'
		)
		assert.equal(page.calls.config, 1)
		assert.equal(page.calls.saves.length, 0)
	} finally {
		page.dispose()
	}
})

test('invalid favicon is validated by the existing save path and opens its hidden group before scrolling', async () => {
	const page = setupPage({ ...settings(), faviconUrl: 'not-a-url' })
	try {
		await page.mount()
		const save = saveButton(page).node
		assert.equal(save.props.onClick, page.bindings.saveData)
		save.props.onClick()
		await page.finishValidation()
		assert.equal(page.calls.validations, 1)
		assert.equal(page.calls.saves.length, 0)
		assert.equal(page.bindings.expandedSections.value.branding, true)
		assert.equal(
			tree(page).find(({ node }) => node.props?.id === 'faviconUrl').closed,
			false
		)
		assert.deepEqual(plain(page.calls.scrolls), [
			{ prop: 'faviconUrl', brandingOpen: true, connectionOpen: false },
		])
		page.bindings.siteSetting.value.faviconUrl =
			'https://example.test/favicon.ico'
		saveButton(page).node.props.onClick()
		await page.finishValidation()
		assert.equal(page.calls.saves.length, 1)
		assert.equal(
			page.calls.saves[0].faviconUrl,
			'https://example.test/favicon.ico'
		)
	} finally {
		page.dispose()
	}
})

test('valid save while groups stay closed submits every existing draft field exactly once', async () => {
	const data = settings()
	data.forceBackendAddress = 'https://files.example.test'
	data.siteHomeLogoLink = 'https://example.test/help'
	const page = setupPage(data)
	try {
		await page.mount()
		saveButton(page).node.props.onClick()
		await page.finishValidation()
		assert.equal(page.calls.saves.length, 1)
		assert.deepEqual(page.calls.saves[0], data)
		assert.equal(page.bindings.expandedSections.value.branding, false)
		assert.equal(page.bindings.expandedSections.value.connection, false)
		assert.equal(page.calls.validations, 1)
	} finally {
		page.dispose()
	}
})

test('compatibility errors reveal only their group, while successful validation does not open panels', async () => {
	const page = setupPage()
	try {
		await page.mount()
		await page.render().props.onValidate('faviconUrl', true)
		assert.equal(page.bindings.expandedSections.value.branding, false)
		await page.render().props.onValidate('frontDomain', false)
		assert.equal(page.bindings.expandedSections.value.connection, true)
		assert.equal(page.bindings.expandedSections.value.branding, false)
		assert.deepEqual(plain(page.calls.scrolls), [
			{ prop: 'frontDomain', brandingOpen: false, connectionOpen: true },
		])
		assert.equal(page.calls.saves.length, 0)
	} finally {
		page.dispose()
	}
})
