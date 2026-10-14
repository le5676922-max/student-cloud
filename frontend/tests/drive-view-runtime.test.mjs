import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import * as vue from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import * as driveView from '../src/utils/drive-view.mjs'
import * as presentation from '../src/utils/drive-presentation.mjs'

const sources = await Promise.all(
	[
		'DriveEmptyState',
		'view/TableFileView',
		'view/CardFileView',
		'ZContextmenu',
	].map(async (name) => [
		name,
		await readFile(
			new URL(`../src/components/file/${name}.vue`, import.meta.url),
			'utf8'
		),
	])
)
const compiled = Object.fromEntries(
	sources.map(([name, source]) => {
		const { descriptor } = parse(source)
		return [
			name,
			compileScript(descriptor, { id: name, inlineTemplate: true }).content,
		]
	})
)
const icon = { render: () => vue.h('svg') }
const icons = new Proxy({}, { get: () => icon })
const element = (type) => ({
	type,
	children: [],
	props: {},
	style: {},
	parent: null,
	text: '',
})
const renderer = vue.createRenderer({
	createElement: element,
	createText: (text) => ({ ...element('#text'), text }),
	createComment: (text) => ({ ...element('#comment'), text }),
	insert(node, parent, anchor) {
		if (node.parent)
			node.parent.children.splice(node.parent.children.indexOf(node), 1)
		const index = anchor ? parent.children.indexOf(anchor) : -1
		parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
		node.parent = parent
	},
	remove(node) {
		node.parent.children.splice(node.parent.children.indexOf(node), 1)
		node.parent = null
	},
	setElementText(node, text) {
		node.text = text
		node.children = []
	},
	setText: (node, text) => (node.text = text),
	setComment: (node, text) => (node.text = text),
	parentNode: (node) => node.parent,
	nextSibling: (node) =>
		node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
	patchProp: (node, key, _previous, value) => (node.props[key] = value),
})
const text = (node) => node.text + node.children.map(text).join('')
const find = (node, predicate) =>
	predicate(node)
		? node
		: node.children.map((child) => find(child, predicate)).find(Boolean)
const click = (node, detail = 0) =>
	node.props.onClick({ detail, stopPropagation() {} })

function mountView(name, { rows = [], query = '', shareMode = false } = {}) {
	const files = vue.reactive({
		fileList: rows,
		imgMode: false,
		directoryPage: null,
	})
	const storage = vue.reactive({
		permission: { upload: true },
		globalConfig: { tableSize: 'default' },
	})
	const searchParam = vue.reactive({ nameQuery: query })
	const loading = vue.ref(false)
	const directoryLoadError = vue.ref('')
	const directoryLoadState = vue.ref('ready')
	const selected = vue.ref([])
	const opened = [],
		pointerClicks = [],
		requests = []
	const fileData = {
		searchParam,
		directoryNameQuery: vue.ref(query),
		loading,
		skeletonData: [],
		loadFile: (...args) =>
			requests.push({ args, query: searchParam.nameQuery }),
		openRow() {},
		sortChangeMethod() {},
	}
	const operator = (mode) => ({
		tableDbClickRow: (row) => opened.push({ mode, row }),
		tableClickRow: (row) => pointerClicks.push({ mode, row }),
		tableHoverRow() {},
		tableLeaveRow() {},
	})
	const modules = {
		vue,
		'@heroicons/vue/24/outline': icons,
		'~/utils': {
			isNotMobile: vue.ref(true),
			fileSizeFilter: () => '',
			removeDuplicateSeparator: (value) => value,
		},
		'~/utils/drive-view.mjs': driveView,
		'~/utils/drive-presentation.mjs': presentation,
		'~/stores/file-data': { default: () => files },
		'~/stores/storage-config': { default: () => storage },
		'~/composables/file/useFileData': { default: () => fileData },
		'~/composables/file/useFileLoading': {
			default: () => ({
				loading,
				basicLoading: loading,
				skeletonLoading: vue.ref(false),
				directoryLoadError,
				directoryLoadState,
			}),
		},
		'~/composables/file/useFileUpload': {
			default: () => ({ openUploadDialog() {}, openUploadFolderDialog() {} }),
		},
		'~/composables/file/useFileSelect': {
			default: () => ({
				initSelectFun() {},
				selectRowsChange() {},
				selectRows: selected,
				checkSelectable: (row) => ['FILE', 'FOLDER'].includes(row.type),
				tableRowClassName: () => '',
				selectRow: vue.ref(null),
				selectStatistics: {},
			}),
		},
		'~/composables/file/useTableOperator': { default: () => operator('file') },
		'~/composables/file/useShareTableOperator': {
			default: () => operator('share'),
		},
		'~/composables/share/useShareData': {
			default: () => ({ sortShareChangeMethod() {} }),
		},
		'~/composables/file/useFileContextMenu': {
			default: () => ({
				showFileMenu() {},
				initContextMenu() {},
				contextMenuTargetFile: false,
			}),
		},
		'~/composables/file/useFileOperator': { default: () => ({}) },
		'~/composables/file/useFileLink': { default: () => ({}) },
		'~/composables/file/useFileShare': { default: () => ({}) },
		'@soerenmartius/vue3-clipboard': { toClipboard() {} },
	}
	const passthrough = (tag) => ({
		setup(_props, { attrs, slots }) {
			return () => vue.h(tag, attrs, slots.default?.())
		},
	})
	modules['v-contextmenu'] = {
		Contextmenu: passthrough('nav'),
		ContextmenuItem: passthrough('button'),
	}
	function component(componentName) {
		const executable = compiled[componentName]
			.replace(
				/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
				(_whole, bindings, module) => {
					const key = JSON.stringify(module)
					return bindings.trim().startsWith('{')
						? `const ${bindings.replace(/\s+as\s+/g, ': ')} = modules[${key}];`
						: `const ${bindings.trim()} = modules[${key}].default;`
				}
			)
			.replace('export default', 'return')
		return new Function('modules', 'auto', `with (auto) { ${executable} }`)(
			modules,
			{
				...vue,
				useRoute: () => ({ params: { storageKey: 'local' } }),
				useFilePreview: () => ({}),
			}
		)
	}
	modules['~/components/file/DriveEmptyState.vue'] = {
		default: component('DriveEmptyState'),
	}
	const root = element('root')
	const app = renderer.createApp(component(name), { shareMode })
	app.directive('loading', {})
	app.component('ElButton', passthrough('button'))
	app.component('ElSkeleton', passthrough('div'))
	app.component('ElSkeletonItem', passthrough('span'))
	app.component('SvgIcon', icon)
	for (const name of [
		'IMdiFolderOpenOutline',
		'IMdiEyeOutline',
		'IMdiDownloadOutline',
		'IMdiLinkPlus',
		'IMdiShareVariant',
		'IMdiRenameOutline',
		'IMdiFolderMoveOutline',
		'IMdiContentCopy',
		'ISolarTrashBinMinimalisticBold',
		'ILucideFolderPlus',
		'ICiFileUpload',
		'ICiFolderUpload',
		'IMdiReload',
	])
		app.component(name, icon)
	app.component('LoadMoreFile', { render: () => vue.h('footer') })
	const tableData = Symbol('tableData')
	app.component('ElTable', {
		props: ['data'],
		setup(props, { slots, expose }) {
			vue.provide(
				tableData,
				vue.computed(() => props.data)
			)
			expose({
				clearSelection() {},
				toggleRowSelection() {},
				toggleAllSelection() {},
			})
			return () =>
				vue.h('section', [
					slots.default?.(),
					props.data.length ? null : slots.empty?.(),
					slots.append?.(),
				])
		},
	})
	app.component('ElTableColumn', {
		setup(_props, { slots }) {
			const data = vue.inject(tableData)
			return () =>
				vue.h(
					'div',
					data.value.map((row) => slots.default?.({ row }))
				)
		},
	})
	app.mount(root)
	return {
		app,
		root,
		files,
		loading,
		directoryLoadError,
		directoryLoadState,
		opened,
		pointerClicks,
		requests,
		searchParam,
		find: (predicate) => find(root, predicate),
		button: (label) =>
			find(
				root,
				(node) => node.type === 'button' && text(node).includes(label)
			),
		text: () => text(root),
	}
}

test('directory entries ignore only synthetic parent rows, retaining roots, files and folders', () => {
	for (const rows of [undefined, [], [null], [{ type: 'BACK' }]])
		assert.equal(driveView.directoryHasEntries(rows), false)
	for (const type of ['ROOT', 'FOLDER', 'FILE'])
		assert.equal(
			driveView.directoryHasEntries([{ type: 'BACK' }, { type }]),
			true
		)
})

test('both views show an empty nested directory and filtering state while preserving parent navigation', async (t) => {
	for (const name of ['view/TableFileView', 'view/CardFileView']) {
		for (const query of ['', 'notes']) {
			const view = mountView(name, {
				rows: [{ type: 'BACK', name: '/', path: '/' }],
				query,
			})
			t.after(() => view.app.unmount())
			assert.ok(
				view.find((node) => node.props['aria-label'] === '返回上一级：/')
			)
			assert.match(
				view.text(),
				query ? /没有找到匹配的文件/ : /这个目录还是空的/
			)
			if (query) {
				click(view.button('清空筛选'))
				assert.equal(view.searchParam.nameQuery, '')
				assert.equal(view.requests.length, 1)
				assert.equal(view.requests[0].query, '')
			}
			view.loading.value = true
			await vue.nextTick()
			assert.equal(
				view.find((node) => node.type === 'h2'),
				undefined
			)
			view.loading.value = false
			view.files.fileList.push({ type: 'FILE', name: 'notes.txt' })
			await vue.nextTick()
			assert.equal(
				view.find((node) => node.type === 'h2'),
				undefined
			)
		}
	}
})

test('share empty directories retain share guidance and never expose the main directory draft filter', (t) => {
	for (const name of ['view/TableFileView', 'view/CardFileView']) {
		const view = mountView(name, {
			rows: [{ type: 'BACK', name: '/' }],
			query: 'private draft',
			shareMode: true,
		})
		t.after(() => view.app.unmount())
		assert.match(view.text(), /分享者/)
		assert.equal(view.button('清空筛选'), undefined)
		assert.equal(view.button('上传第一个文件'), undefined)
		assert.ok(view.find((node) => node.props['aria-label'] === '返回上一级：/'))
	}
})

test('empty native pages preserve parent navigation and next-page guidance without offering first-file upload', async (t) => {
	for (const name of ['view/TableFileView', 'view/CardFileView']) {
		const view = mountView(name, {
			rows: [{ type: 'BACK', name: '/' }],
		})
		t.after(() => view.app.unmount())
		view.files.directoryPage = {
			nativePagination: true,
			count: 0,
			nextCursor: 'next',
		}
		await vue.nextTick()
		assert.match(view.text(), /本页没有可显示的文件/)
		assert.match(view.text(), /下一页/)
		assert.equal(view.button('上传第一个文件'), undefined)
		assert.ok(view.find((node) => node.props['aria-label'] === '返回上一级：/'))
	}
})

test('directory failures and password prompts suppress ordinary empty/upload state but do not affect share mode', async (t) => {
	for (const name of ['view/TableFileView', 'view/CardFileView']) {
		for (const shareMode of [false, true]) {
			for (const rows of [[], [{ type: 'BACK', name: '/' }]]) {
				const view = mountView(name, { rows, shareMode })
				t.after(() => view.app.unmount())
				view.directoryLoadError.value = 'network failure'
				view.directoryLoadState.value = 'error'
				await vue.nextTick()
				assert.equal(
					Boolean(view.find((node) => node.type === 'h2')),
					shareMode
				)
				assert.equal(view.button('上传第一个文件'), undefined)
				view.directoryLoadError.value = ''
				view.directoryLoadState.value = 'password'
				await vue.nextTick()
				assert.equal(
					Boolean(view.find((node) => node.type === 'h2')),
					shareMode
				)
				assert.equal(view.button('上传第一个文件'), undefined)
			}
		}
	}
})

test('table filename buttons open by keyboard using the active file/share operator and preserve pointer behavior', async (t) => {
	for (const shareMode of [false, true]) {
		const view = mountView('view/TableFileView', {
			rows: [{ type: 'FILE', name: 'notes.txt' }],
			shareMode,
		})
		t.after(() => view.app.unmount())
		const button = view.button('notes.txt')
		assert.equal(button.props.type, 'button')
		assert.equal(button.props['aria-label'], '打开文件：notes.txt')
		assert.equal(button.props.title, 'notes.txt')
		click(button, 0)
		assert.equal(view.opened.length, 1)
		assert.equal(view.opened[0].mode, shareMode ? 'share' : 'file')
		assert.equal(view.pointerClicks.length, 0)
		click(button, 1)
		assert.equal(view.pointerClicks.length, 1)
		assert.equal(view.opened.length, 1)
		view.loading.value = true
		await vue.nextTick()
		assert.equal(button.props.disabled, true)
		click(button)
		assert.equal(view.opened.length, 1)
	}
})

test('context-menu refresh requests the current listing without forwarding an event or reloading the page', (t) => {
	const view = mountView('ZContextmenu')
	t.after(() => view.app.unmount())
	click(view.button('刷新'))
	assert.equal(view.requests.length, 1)
	assert.deepEqual(view.requests[0].args, [])
	view.loading.value = true
	click(view.button('刷新'))
	assert.equal(view.requests.length, 1)
})
