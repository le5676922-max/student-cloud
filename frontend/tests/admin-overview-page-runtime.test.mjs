import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as overviewHelpers from '../src/utils/admin-overview.mjs'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

const source = await readFile(
	new URL('../src/pages/admin/overview.vue', import.meta.url),
	'utf8'
)
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'overview-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'overview.vue',
	id: 'overview-runtime',
	compilerOptions: { bindingMetadata: script.bindings },
})
assert.deepEqual(template.errors, [])
const plain = (value) => JSON.parse(JSON.stringify(value))
const flush = async () => {
	for (let index = 0; index < 5; index++) {
		await Promise.resolve()
		await vue.nextTick()
	}
}
const sampleOverview = () => ({
	generatedAt: 1792702800000,
	timeZone: 'Asia/Shanghai',
	workspaces: 2,
	members: 6,
	workspaceAdmins: 2,
	storageSources: 3,
	shares: 0,
	pendingAgentTasks: 0,
	loginLoggingEnabled: true,
	loginSummary: { successes24h: 0, failures24h: 0 },
	dailyLogins: [{ date: '2026-10-23', successes: 0, failures: 0 }],
	recentLogins: [],
	statuses: {
		enabledWorkspaces: 2,
		disabledWorkspaces: 0,
		activeMembers: 6,
		disabledMembers: 0,
		enabledStorages: 3,
		expiredShares: 0,
		pendingMemberships: 0,
		failedAgentTasks: 0,
		failedSchedules: 0,
		runningAgentTasks: 0,
		enabledSchedules: 0,
	},
	runtime: {
		uptimeMs: 60000,
		databaseType: 'MySQL',
		databaseLatencyMs: 2,
		usedHeapBytes: 1024,
		maxHeapBytes: 2048,
	},
})
const sampleWorkspaces = () => ({
	records: [],
	total: 0,
	page: 1,
	pageSize: 10,
})
const contentOverview = () => ({
	scope: 'GLOBAL',
	workspaceId: null,
	dataComplete: true,
	metrics: Object.fromEntries(
		Object.entries({
			mounts: 5,
			enabledMounts: 4,
			shares: 9,
			activeShares: 2,
		}).map(([key, value]) => [key, { available: true, value }])
	),
})
function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => {
		resolve = yes
		reject = no
	})
	return { promise, resolve, reject }
}
function execute(code, modules) {
	const executable = code
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"];?/g,
			(_whole, names, name) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					name
				)}];`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"];?/g,
			(_whole, name, path) =>
				`const ${name} = modules[${JSON.stringify(path)}];`
		)
		.replace('export default', 'return')
		.replace('export function render', 'return function render')
	return vm.runInNewContext(`(function(){${executable}\n})()`, {
		modules,
		AbortController,
	})
}

// Run the real SFC setup and compiled render. Only HTTP and UI-library boundaries
// are replaced; no browser/network page navigation is used for these unit tests.
function setupPage({
	overviewRequest,
	workspacesRequest,
	fileBusinessRequest,
	accountModel = '',
} = {}) {
	const calls = { overview: [], workspaces: [], fileBusiness: [] },
		mounted = [],
		cleanups = []
	const store = vue.reactive({
		globalConfig: { accountModel },
		loginInfo: { isLogin: true, role: 'ADMIN', id: 1, tenantId: null },
	})
	const credential = vue.ref('fixture-token')
	const icons = Object.fromEntries(
		[
			'ArrowPathIcon',
			'BuildingOffice2Icon',
			'UsersIcon',
			'CircleStackIcon',
			'LinkIcon',
		].map((name) => [name, { name }])
	)
	const modules = {
		vue: {
			...vue,
			onMounted: (callback) => mounted.push(callback),
			onBeforeUnmount: (callback) => cleanups.push(callback),
			resolveComponent: (name) => ({ name }),
			resolveDynamicComponent: (value) =>
				typeof value === 'string' && value !== 'article'
					? { name: value }
					: value,
			resolveDirective: () => null,
			withDirectives: (node) => node,
		},
		'@heroicons/vue/24/outline': icons,
		'~/utils/admin-overview.mjs': overviewHelpers,
		'~/stores/storage-config': () => store,
		'@vueuse/core': { useStorage: () => credential },
		'~/utils/latest-request.mjs': { createLatestRequest },
		'~/api/admin/file-business': {
			getFileBusinessOverviewReq: (...args) => {
				calls.fileBusiness.push(args)
				return (
					fileBusinessRequest?.(...args) ??
					Promise.resolve({ data: contentOverview() })
				)
			},
		},
		'~/api/admin/admin-overview': {
			getGlobalOverviewReq: (config) => {
				calls.overview.push(config)
				return (
					overviewRequest?.(config) ??
					Promise.resolve({ data: sampleOverview() })
				)
			},
			getGlobalWorkspacesReq: (query, config) => {
				calls.workspaces.push({ query: plain(query), config })
				return (
					workspacesRequest?.(query, config) ??
					Promise.resolve({ data: sampleWorkspaces() })
				)
			},
		},
	}
	const Page = execute(script.content, modules),
		render = execute(template.code, modules)
	const scope = vue.effectScope()
	const bindings = scope.run(() => Page.setup({}, { expose() {} }))
	const setup = vue.proxyRefs(bindings)
	return {
		bindings,
		calls,
		store,
		credential,
		async mount() {
			await Promise.all(mounted.map((callback) => callback()))
			await flush()
		},
		render: () => render({}, [], {}, setup, {}, {}),
		dispose() {
			cleanups.forEach((cleanup) => cleanup())
			scope.stop()
		},
	}
}

function flatten(node, result = []) {
	if (Array.isArray(node)) {
		node.forEach((child) => flatten(child, result))
		return result
	}
	if (!vue.isVNode(node)) return result
	result.push(node)
	if (Array.isArray(node.children)) flatten(node.children, result)
	else if (node.children?.default && node.type?.name !== 'el-table-column')
		flatten(node.children.default(), result)
	return result
}
const hasClass = (node, name) =>
	String(node.props?.class || '')
		.split(/\s+/)
		.includes(name)
const text = (node) => {
	if (Array.isArray(node)) return node.map(text).join('')
	if (!vue.isVNode(node)) return typeof node === 'string' ? node : ''
	if (typeof node.children === 'string') return node.children
	if (Array.isArray(node.children)) return text(node.children)
	if (node.children?.default && node.type?.name !== 'el-table-column')
		return text(node.children.default())
	return ''
}
const view = (page) => flatten(page.render())
const disclosures = (nodes) => nodes.filter((node) => node.type === 'details')
function toggle(node, open) {
	node.props.onToggle({ currentTarget: { open } })
}

test('default overview renders four actionable metrics and reminders, without secondary tables or charts', async () => {
	const page = setupPage()
	try {
		await page.mount()
		const nodes = view(page)
		const cards = nodes.filter((node) => hasClass(node, 'metric'))
		assert.equal(cards.length, 4)
		assert.deepEqual(
			cards.map((node) => node.props.to),
			[
				'/admin/tenant-list',
				'/admin/user-list',
				'/admin/storage-list',
				'/admin/share-link',
			]
		)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'attention-panel')).length,
			1
		)
		assert.equal(
			nodes.filter((node) => node.type?.name === 'el-table').length,
			0
		)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-chart')).length,
			0
		)
		assert.equal(disclosures(nodes).length, 2)
		assert.ok(disclosures(nodes).every((node) => node.props.open === false))
		assert.equal(page.calls.overview.length, 1)
		assert.equal(page.calls.workspaces.length, 1)
	} finally {
		page.dispose()
	}
})

test('native disclosure events lazily mount and unmount diagnostics and space tables without new requests', async () => {
	const page = setupPage()
	try {
		await page.mount()
		toggle(disclosures(view(page))[0], true)
		let nodes = view(page)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-chart')).length,
			1
		)
		assert.equal(
			nodes.filter((node) => node.type?.name === 'el-table').length,
			1
		)
		toggle(disclosures(nodes)[1], true)
		nodes = view(page)
		assert.equal(
			nodes.filter((node) => node.type?.name === 'el-table').length,
			2
		)
		toggle(disclosures(nodes)[0], false)
		toggle(disclosures(view(page))[1], false)
		nodes = view(page)
		assert.equal(
			nodes.filter((node) => node.type?.name === 'el-table').length,
			0
		)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-chart')).length,
			0
		)
		assert.equal(page.calls.overview.length, 1)
		assert.equal(page.calls.workspaces.length, 1)
	} finally {
		page.dispose()
	}
})

test('trend chart and readable table are alternatives rather than duplicate simultaneously rendered content', async () => {
	const page = setupPage()
	try {
		await page.mount()
		toggle(disclosures(view(page))[0], true)
		let nodes = view(page)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-table')).length,
			0
		)
		const chart = nodes.find((node) => hasClass(node, 'trend-chart'))
		assert.equal(chart.props.role, 'img')
		assert.match(chart.props['aria-label'], /成功 0 次，失败 0 次/)
		const showTable = nodes.find(
			(node) => node.type === 'button' && text(node).trim() === '数据明细'
		)
		showTable.props.onClick()
		nodes = view(page)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-chart')).length,
			0
		)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-table')).length,
			1
		)
		const showChart = nodes.find(
			(node) => node.type === 'button' && text(node).trim() === '趋势图'
		)
		showChart.props.onClick()
		nodes = view(page)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-table')).length,
			0
		)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-chart')).length,
			1
		)
		assert.equal(page.calls.overview.length, 1)
	} finally {
		page.dispose()
	}
})

test('overview and workspace failures remain visible outside collapsed sections with a usable retry', async () => {
	let workspaceReads = 0
	const page = setupPage({
		overviewRequest: async () => {
			throw new Error('private diagnostics')
		},
		workspacesRequest: async () => {
			if (workspaceReads++ === 0) throw new Error('offline')
			return { data: sampleWorkspaces() }
		},
	})
	try {
		await page.mount()
		let nodes = view(page)
		const alerts = nodes.filter((node) => node.type?.name === 'el-alert')
		assert.equal(alerts.length, 2)
		assert.match(alerts[0].props.title, /总览加载失败/)
		assert.match(alerts[1].props.title, /工作空间加载失败/)
		assert.doesNotMatch(
			alerts.map((node) => node.props.title).join(''),
			/private diagnostics/
		)
		const retry = nodes.find(
			(node) => node.type?.name === 'el-button' && text(node) === '重新加载空间'
		)
		await retry.props.onClick()
		await flush()
		nodes = view(page)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'workspace-load-error')).length,
			0
		)
		assert.equal(
			nodes.filter((node) => node.type?.name === 'el-table').length,
			0
		)
		assert.equal(page.calls.workspaces.length, 2)
		assert.equal(page.calls.overview.length, 1)
	} finally {
		page.dispose()
	}
})

test('partial hidden diagnostics emit a visible unknown warning while real zero metrics remain zero', async () => {
	const data = sampleOverview()
	delete data.runtime
	delete data.shares
	const page = setupPage({ overviewRequest: async () => ({ data }) })
	try {
		await page.mount()
		const nodes = view(page)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'summary-warning')).length,
			1
		)
		const cards = nodes.filter((node) => hasClass(node, 'metric'))
		assert.match(text(cards.at(-1)), /—/)
		assert.equal(
			nodes.filter((node) => node.type?.name === 'el-table').length,
			0
		)
	} finally {
		page.dispose()
	}
	const complete = setupPage()
	try {
		await complete.mount()
		assert.equal(
			view(complete).filter((node) => hasClass(node, 'summary-warning')).length,
			0
		)
		assert.match(
			text(
				view(complete)
					.filter((node) => hasClass(node, 'metric'))
					.at(-1)
			),
			/分享记录0/
		)
	} finally {
		complete.dispose()
	}
})

test('workspace filters retain bounded server-side queries and collapsed state does not reset applied filters', async () => {
	const page = setupPage()
	try {
		await page.mount()
		toggle(disclosures(view(page))[1], true)
		page.bindings.keyword.value = '  Course  '
		page.bindings.enabled.value = 'false'
		await page.bindings.applyFilters()
		assert.deepEqual(page.calls.workspaces.at(-1).query, {
			keyword: 'Course',
			enabled: false,
			page: 1,
			pageSize: 10,
		})
		toggle(disclosures(view(page))[1], false)
		toggle(disclosures(view(page))[1], true)
		assert.equal(page.bindings.keyword.value, '  Course  ')
		assert.equal(page.calls.workspaces.length, 2)
		await page.bindings.changePageSize(50)
		assert.equal(page.calls.workspaces.at(-1).query.pageSize, 50)
		assert.equal(page.calls.workspaces.at(-1).query.enabled, false)
	} finally {
		page.dispose()
	}
})

test('pending-owner reminders and unknown diagnostic values are not hidden with the diagnostic section', async () => {
	const data = sampleOverview()
	data.pendingAgentTasks = 3
	data.dailyLogins[0].successes = null
	delete data.statuses.activeMembers
	const page = setupPage({ overviewRequest: async () => ({ data }) })
	try {
		await page.mount()
		const nodes = view(page)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'summary-warning')).length,
			1
		)
		const reminder = nodes.find((node) => hasClass(node, 'attention-panel'))
		assert.match(text(reminder), /待确认 Agent 任务3/)
		assert.match(text(reminder), /全局管理员不能代为执行/)
		assert.equal(
			nodes.filter((node) => hasClass(node, 'trend-chart')).length,
			0
		)
		assert.equal(page.bindings.expandedSections.value.diagnostics, false)
	} finally {
		page.dispose()
	}
})

test('disposing the actual overview cancels both pending requests and prevents stale data from rendering', async () => {
	const first = deferred(),
		second = deferred()
	const page = setupPage({
		overviewRequest: () => first.promise,
		workspacesRequest: () => second.promise,
	})
	const mounting = page.mount()
	await flush()
	assert.equal(page.calls.overview.length, 1)
	assert.equal(page.calls.workspaces.length, 1)
	page.dispose()
	assert.equal(page.calls.overview[0].signal.aborted, true)
	assert.equal(page.calls.workspaces[0].config.signal.aborted, true)
	first.resolve({ data: sampleOverview() })
	second.resolve({ data: sampleWorkspaces() })
	await mounting
	assert.equal(page.bindings.overview.value, null)
	assert.equal(page.bindings.workspacePage.value, null)
})

test('independent overview shares one content response with the file panel and keeps account counts accurate', async () => {
	const page = setupPage({ accountModel: 'independent' })
	try {
		await page.mount()
		assert.equal(page.calls.fileBusiness.length, 1)
		const cards = view(page).filter((node) => hasClass(node, 'metric'))
		assert.match(text(cards[1]), /普通成员账号6/)
		assert.match(text(cards[2]), /存储源5已启用 4/)
		assert.equal(cards[3].type, 'article')
		assert.match(text(cards[3]), /受控分享记录9有效 2/)
		assert.doesNotMatch(text(cards[3]), /已过期/)
		assert.equal(page.bindings.summaryOverview.value.members, 6)
		assert.equal(
			page.bindings.summaryOverview.value.accountModel,
			'independent'
		)
		const panel = view(page).find((node) => node.props?.managed === '')
		assert.ok(panel)
		assert.equal(panel.props.overview, page.bindings.fileBusiness.value)
		assert.doesNotMatch(text(view(page)), /接口不存在/)
		assert.match(text(view(page)), /跨业务申请、知识反馈/)
		await panel.props.onRefresh(30)
		assert.equal(page.calls.fileBusiness.length, 2)
		assert.equal(page.calls.fileBusiness[1][0], 30)
		await page.bindings.refreshAll()
		assert.equal(page.calls.fileBusiness.length, 3)
		assert.equal(page.calls.fileBusiness[2][0], 30)
	} finally {
		page.dispose()
	}
})

test('independent partial content failures stay unknown while platform counts remain visible', async () => {
	const partial = contentOverview()
	partial.metrics.mounts = { available: false, value: 123, note: '暂不可用' }
	partial.metrics.activeShares = { available: false, value: 12 }
	const page = setupPage({
		accountModel: 'independent',
		fileBusinessRequest: async () => ({ data: partial }),
	})
	try {
		await page.mount()
		const cards = view(page).filter((node) => hasClass(node, 'metric'))
		assert.match(text(cards[0]), /工作空间2/)
		assert.match(text(cards[2]), /存储源—/)
		assert.match(text(cards[3]), /受控分享记录9有效 —/)
		assert.equal(page.bindings.summaryOverview.value.storageSources, null)
	} finally {
		page.dispose()
	}
	const failure = setupPage({
		accountModel: 'independent',
		fileBusinessRequest: async () => {
			throw new Error('private failure')
		},
	})
	try {
		await failure.mount()
		assert.match(failure.bindings.fileBusinessError.value, /文件统计暂不可用/)
		assert.equal(failure.bindings.summaryOverview.value.shares, null)
		assert.equal(failure.bindings.overview.value.members, 6)
	} finally {
		failure.dispose()
	}
})

test('global overview rejects workspace content and session changes fence all late responses', async () => {
	const invalid = setupPage({
		accountModel: 'independent',
		fileBusinessRequest: async () => ({
			data: { ...contentOverview(), scope: 'WORKSPACE', workspaceId: 4 },
		}),
	})
	try {
		await invalid.mount()
		assert.equal(invalid.bindings.fileBusiness.value, null)
		assert.match(invalid.bindings.fileBusinessError.value, /范围与当前账号不符/)
	} finally {
		invalid.dispose()
	}
	const pending = [deferred(), deferred(), deferred()]
	const page = setupPage({
		accountModel: 'independent',
		overviewRequest: () => pending[0].promise,
		workspacesRequest: () => pending[1].promise,
		fileBusinessRequest: () => pending[2].promise,
	})
	const mounting = page.mount()
	await flush()
	page.store.loginInfo.role = 'USER'
	await flush()
	assert.equal(page.calls.fileBusiness[0][1].signal.aborted, true)
	pending[0].resolve({ data: sampleOverview() })
	pending[1].resolve({ data: sampleWorkspaces() })
	pending[2].resolve({ data: contentOverview() })
	await mounting
	assert.equal(page.bindings.overview.value, null)
	assert.equal(page.bindings.workspacePage.value, null)
	assert.equal(page.bindings.fileBusiness.value, null)
	page.dispose()
})

test('a platform overview failure does not discard independently successful content counts', async () => {
	const page = setupPage({
		accountModel: 'independent',
		overviewRequest: async () => {
			throw new Error('platform unavailable')
		},
	})
	try {
		await page.mount()
		assert.equal(page.bindings.overview.value, null)
		const cards = view(page).filter((node) => hasClass(node, 'metric'))
		assert.match(text(cards[0]), /工作空间—/)
		assert.match(text(cards[2]), /存储源5/)
		assert.match(text(cards[3]), /受控分享记录9/)
		assert.equal(page.calls.fileBusiness.length, 1)
	} finally {
		page.dispose()
	}
})

test('credential changes replace a pending global request without accepting the previous identity response', async () => {
	const old = deferred(),
		current = deferred()
	let reads = 0
	const page = setupPage({
		accountModel: 'independent',
		fileBusinessRequest: () => (reads++ ? current.promise : old.promise),
	})
	const mounting = page.mount()
	await flush()
	page.credential.value = 'new-token'
	await flush()
	assert.equal(page.calls.fileBusiness.length, 2)
	assert.equal(page.calls.fileBusiness[0][1].signal.aborted, true)
	old.resolve({ data: contentOverview() })
	await mounting
	assert.equal(page.bindings.fileBusiness.value, null)
	current.resolve({
		data: {
			...contentOverview(),
			metrics: { mounts: { available: true, value: 0 } },
		},
	})
	await flush()
	assert.equal(page.bindings.summaryOverview.value.storageSources, 0)
	page.dispose()
})
