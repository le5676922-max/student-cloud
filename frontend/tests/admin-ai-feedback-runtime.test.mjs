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
import {
	adminNavigationGroups,
	adminPrimaryNavigation,
	adminNavigationExpansionForPath,
} from '../src/utils/admin-navigation.mjs'

const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of [
	'window',
	'document',
	'Document',
	'Node',
	'Element',
	'HTMLElement',
	'HTMLInputElement',
	'HTMLTextAreaElement',
	'HTMLSelectElement',
	'SVGElement',
	'Event',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
const source = await readFile(
	new URL('../src/pages/admin/ai-feedback.vue', import.meta.url),
	'utf8'
)
const descriptor = parse(source).descriptor
const compiled = compileScript(descriptor, { id: 'admin-ai-feedback-runtime' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'ai-feedback.vue',
	id: 'admin-ai-feedback-runtime',
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
const idA = 'a'.repeat(32),
	idB = 'b'.repeat(32)
const row = (id = idA, status = 'OPEN') => ({
	id,
	questionPreview: '上传没有成功怎么办？',
	accountId: 'SC00000001',
	nickname: '测试成员',
	tenantName: '课程空间',
	source: 'userSubmitted',
	status,
	createdAt: 10000,
})
const detail = (id = idA) => ({
	...row(id),
	userId: 12,
	tenantId: 9,
	question: '上传没有成功怎么办？',
	answer: '<img src=x onerror="alert(1)">原回答待核验',
	correctedAnswer: '',
	note: '',
	pagePath: '/workspace-9',
	updatedAt: 10000,
})
const originalCreateUrl = URL.createObjectURL,
	originalRevokeUrl = URL.revokeObjectURL
const originalAnchorClick = dom.window.HTMLAnchorElement.prototype.click
let activeDownloads = null
URL.createObjectURL = (blob) => {
	activeDownloads?.blobs.push(blob)
	return 'blob:https://local.invalid/reviewed'
}
URL.revokeObjectURL = (url) => activeDownloads?.revoked.push(url)
dom.window.HTMLAnchorElement.prototype.click = function () {
	activeDownloads?.links.push({ href: this.href, filename: this.download })
}
after(() => {
	URL.createObjectURL = originalCreateUrl
	URL.revokeObjectURL = originalRevokeUrl
	dom.window.HTMLAnchorElement.prototype.click = originalAnchorClick
	dom.window.close()
})

async function mount({
	role = 'ADMIN',
	list,
	get = async (id) => ({ data: detail(id) }),
	save = async (id, data) => ({ data: { ...detail(id), ...data } }),
	exportRows = async () => ({ data: [] }),
} = {}) {
	const store = vue.reactive({
		loginInfo: { isLogin: true, role, username: 'global-admin' },
	})
	const credential = vue.ref('synthetic-session')
	const calls = { list: [], get: [], save: [], export: [] }
	const downloads = { blobs: [], links: [], revoked: [] }
	activeDownloads = downloads
	const modules = {
		vue,
		'@vueuse/core': { useStorage: () => credential },
		'~/stores/storage-config': () => store,
		'~/utils/latest-request.mjs': { createLatestRequest },
		'~/components/admin/AiKnowledgeCuration.vue': { render: () => null },
		'~/api/admin/admin-ai-feedback': {
			listAiFeedbackReq: (...args) => {
				calls.list.push(args)
				return list
					? list(...args)
					: Promise.resolve({
							data: {
								items: [row()],
								total: 1,
								page: args[0].page,
								pageSize: args[0].pageSize,
							},
					  })
			},
			getAiFeedbackReq: (...args) => {
				calls.get.push(args)
				return get(...args)
			},
			saveAiFeedbackReq: (...args) => {
				calls.save.push(args)
				return save(...args)
			},
			exportAiFeedbackReq: (...args) => {
				calls.export.push(args)
				return exportRows(...args)
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
	const app = vue.createApp(Component)
	app.component('ElDrawer', {
		props: ['modelValue', 'title'],
		setup(props, { slots }) {
			return () =>
				props.modelValue
					? vue.h('aside', { 'data-drawer': true }, slots.default?.())
					: null
		},
	})
	const vm = app.mount(host)
	await flush()
	return {
		host,
		api: vm.$.setupState,
		calls,
		downloads,
		store,
		credential,
		dispose() {
			app.unmount()
			host.remove()
			activeDownloads = null
		},
	}
}

test('global feedback lives in diagnostics without adding another primary navigation entry', async () => {
	assert.equal(adminPrimaryNavigation.length, 5)
	assert.deepEqual(
		adminNavigationGroups
			.find((group) => group.id === 'logs')
			.items.find((item) => item.id === 'ai-feedback'),
		{
			id: 'ai-feedback',
			name: 'AI 反馈',
			href: '/admin/ai-feedback',
			icon: 'document',
		}
	)
	assert.deepEqual(adminNavigationExpansionForPath('/admin/ai-feedback'), [
		'more',
		'logs',
	])
	assert.doesNotMatch(source, /v-html/)
	for (const style of descriptor.styles)
		assert.deepEqual(
			compileStyle({
				source: style.content,
				filename: 'ai-feedback.vue',
				id: 'admin-ai-feedback-runtime',
				scoped: true,
			}).errors,
			[]
		)
	const apiSource = await readFile(
		new URL('../src/api/admin/admin-ai-feedback.js', import.meta.url),
		'utf8'
	)
	assert.match(apiSource, /timeout: 15000/)
	assert.match(apiSource, /showDefaultMsg: false/)
	assert.match(apiSource, /from '~\/http\/request'/)
})

test('list and detail render submitted text safely and never promote the original answer into the correction', async () => {
	const app = await mount()
	try {
		assert.deepEqual(app.calls.list[0][0], {
			page: 1,
			pageSize: 20,
			status: 'OPEN',
		})
		assert.match(app.host.textContent, /上传没有成功怎么办/)
		await app.api.openDetail(idA)
		await flush()
		assert.equal(app.api.draft.correctedAnswer, '')
		assert.equal(app.host.querySelector('img'), null)
		assert.match(app.host.textContent, /<img src=x/)
		assert.equal(app.host.querySelector('.feedback-original').open, false)
		assert.match(app.host.textContent, /未经核验/)
	} finally {
		app.dispose()
	}
})

test('members and workspace administrators cannot read, export or save the global feedback queue', async () => {
	for (const role of ['TENANT_ADMIN', 'USER']) {
		const app = await mount({ role })
		try {
			await app.api.openDetail(idA)
			await app.api.exportReviewed()
			await app.api.saveDetail()
			assert.equal(app.calls.list.length, 0)
			assert.equal(app.calls.get.length, 0)
			assert.equal(app.calls.save.length, 0)
			assert.equal(app.calls.export.length, 0)
			assert.match(app.host.textContent, /仅全局管理员/)
		} finally {
			app.dispose()
		}
	}
})

test('marking resolved requires a corrected answer and writes only the review fields', async () => {
	const app = await mount()
	try {
		await app.api.openDetail(idA)
		app.api.draft.status = 'RESOLVED'
		assert.equal(app.api.canSave, false)
		await app.api.saveDetail()
		assert.equal(app.calls.save.length, 0)
		app.api.draft.correctedAnswer = '先确认存储源和目录授权，再重试上传。'
		app.api.draft.note = '已核查项目使用说明'
		await app.api.saveDetail()
		assert.equal(app.calls.save.length, 1)
		assert.deepEqual(app.calls.save[0].slice(0, 2), [
			idA,
			{
				status: 'RESOLVED',
				correctedAnswer: '先确认存储源和目录授权，再重试上传。',
				note: '已核查项目使用说明',
			},
		])
		assert.equal(app.api.selectedId, '')
		assert.equal(app.calls.list.length, 2)
	} finally {
		app.dispose()
	}
})

test('unknown save preserves the correction draft, keeps the drawer open and does not auto-retry', async () => {
	const app = await mount({
		save: async () => {
			throw new Error('unknown result')
		},
	})
	try {
		await app.api.openDetail(idA)
		app.api.draft.correctedAnswer = '尚未确认保存的人工修订'
		app.api.draft.status = 'RESOLVED'
		await app.api.saveDetail()
		assert.equal(app.api.selectedId, idA)
		assert.equal(app.api.draft.correctedAnswer, '尚未确认保存的人工修订')
		assert.match(app.api.saveError, /保存结果未确认/)
		assert.equal(app.calls.save.length, 1)
		assert.equal(app.api.saving, false)
	} finally {
		app.dispose()
	}
})

test('saving blocks closing or switching the drawer until the acknowledgement settles', async () => {
	const response = deferred()
	const app = await mount({ save: () => response.promise })
	try {
		await app.api.openDetail(idA)
		const pending = app.api.saveDetail()
		app.api.closeDetail()
		await app.api.openDetail(idB)
		assert.equal(app.api.selectedId, idA)
		assert.equal(app.calls.get.length, 1)
		response.resolve({ data: detail(idA) })
		await pending
		assert.equal(app.api.selectedId, '')
	} finally {
		app.dispose()
	}
})

test('a late detail response cannot replace a newly selected feedback item', async () => {
	const response = deferred()
	const app = await mount({
		get: (id) =>
			id === idA ? response.promise : Promise.resolve({ data: detail(id) }),
	})
	try {
		const pending = app.api.openDetail(idA)
		await app.api.openDetail(idB)
		assert.equal(app.calls.get[0][1].signal.aborted, true)
		response.resolve({ data: detail(idA) })
		await pending
		assert.equal(app.api.detail.id, idB)
	} finally {
		app.dispose()
	}
})

test('credential or role changes synchronously clear private content and suppress old exports and details', async () => {
	const detailResponse = deferred(),
		exportResponse = deferred()
	const app = await mount({
		get: () => detailResponse.promise,
		exportRows: () => exportResponse.promise,
	})
	try {
		const reading = app.api.openDetail(idA),
			exporting = app.api.exportReviewed()
		app.store.loginInfo.role = 'USER'
		assert.equal(app.api.pageData, null)
		assert.equal(app.api.selectedId, '')
		assert.equal(app.calls.get[0][1].signal.aborted, true)
		assert.equal(app.calls.export[0][0].signal.aborted, true)
		detailResponse.resolve({ data: detail() })
		exportResponse.resolve({
			data: [{ question: 'PRIVATE QUESTION', answer: 'PRIVATE ANSWER' }],
		})
		await Promise.all([reading, exporting])
		assert.equal(app.downloads.links.length, 0)
		assert.equal(app.api.detail, null)
		assert.equal(app.host.textContent.includes('PRIVATE'), false)
	} finally {
		app.dispose()
	}
})

test('JSONL export uses corrected question-answer fields only and releases the temporary download URL', async () => {
	const app = await mount({
		exportRows: async () => ({
			data: [
				{
					question: '问题\n第二行',
					answer: '人工修订答案',
					accountId: 'PRIVATE_ID',
					note: 'PRIVATE_NOTE',
					pagePath: '/PRIVATE_PATH',
				},
			],
		}),
	})
	try {
		await app.api.exportReviewed()
		assert.equal(app.downloads.links.length, 1)
		assert.equal(app.downloads.links[0].filename, 'ai-reviewed-feedback.jsonl')
		const text = await app.downloads.blobs[0].text()
		assert.deepEqual(JSON.parse(text.trim()), {
			question: '问题\n第二行',
			answer: '人工修订答案',
		})
		assert.equal(text.includes('PRIVATE'), false)
		assert.deepEqual(app.downloads.revoked, [
			'blob:https://local.invalid/reviewed',
		])
	} finally {
		app.dispose()
	}
})

test('empty or malformed exports do not create pretend downloadable datasets', async () => {
	for (const rows of [
		[],
		[{ question: 'q', answer: ' ' }],
		Array.from({ length: 501 }, () => ({ question: 'q', answer: 'a' })),
	]) {
		const app = await mount({ exportRows: async () => ({ data: rows }) })
		try {
			await app.api.exportReviewed()
			assert.equal(app.downloads.links.length, 0)
			assert.ok(app.api.notice || app.api.exportError)
		} finally {
			app.dispose()
		}
	}
})

test('bad list or mismatched detail acknowledgements stay unavailable instead of inventing empty healthy data', async () => {
	const app = await mount({
		list: async () => ({ data: {} }),
		get: async () => ({ data: detail(idB) }),
	})
	try {
		assert.equal(app.api.pageData, null)
		assert.ok(app.api.listError)
		assert.equal(app.host.textContent.includes('共 0 条'), false)
		await app.api.openDetail(idA)
		assert.equal(app.api.draft, null)
		assert.ok(app.api.detailError)
	} finally {
		app.dispose()
	}
})
