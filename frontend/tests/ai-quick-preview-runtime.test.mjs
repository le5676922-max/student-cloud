import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as automation from '../src/utils/automation-context.mjs'
import * as panel from '../src/components/ai/ai-assistant-panel.mjs'
import * as uploadIntent from '../src/utils/ai-upload-intent.mjs'
import * as assistantSupport from '../src/utils/assistant-support.mjs'

// Executes the real SFC setup and Vue watchers, with no browser, model, server or upload.
// This is runtime logic coverage, not browser/UI rendering coverage.
const source = await readFile(
	new URL('../src/components/ai/AiAssistant.vue', import.meta.url),
	'utf8'
)
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, {
	id: 'ai-quick-preview-test',
}).content

function deferred() {
	let resolve, reject
	const promise = new Promise((finish, fail) => {
		resolve = finish
		reject = fail
	})
	return { promise, resolve, reject }
}
const plain = (value) => JSON.parse(JSON.stringify(value))
const plan = (id = 'preview-one', status = 'PENDING') => ({
	id,
	status,
	title: '整理目录',
	expiresAt: Date.now() + 600000,
	steps: [
		{
			action: 'MOVE_FILE',
			storageKey: 'workspace-2',
			sourcePath: '/资料/a.txt',
			targetPath: '/资料/文档/a.txt',
		},
	],
	results: [],
})

function setupAssistant({
	request = async () => ({ data: plan() }),
	chat = async () => ({ answer: '测试回答' }),
	feedback = async () => ({ data: { id: 'f'.repeat(32), status: 'OPEN' } }),
	token = null,
} = {}) {
	const requests = [],
		feedbackRequests = [],
		streams = [],
		uploads = [],
		notices = [],
		cleanups = []
	const route = vue.reactive({
		path: '/workspace-2/资料',
		params: { storageKey: 'workspace-2', fullpath: ['资料'] },
		meta: { layout: 'file' },
	})
	const permission = { move: true, newFolder: true, rename: true }
	const storage = vue.reactive({
		loginInfo: {
			isLogin: true,
			username: '100000001',
			tenantId: 2,
			role: 'USER',
		},
		permission,
		folderConfig: { permission },
	})
	const files = vue.reactive({
		currentStorageSource: { key: 'workspace-2', type: { key: 'local' } },
	})
	const localValues = new Map(
		token ? [['tongligong-student-cloud-token', token]] : []
	)
	const credential = vue.ref(token || '')
	const modules = {
		'@vueuse/core': { useStorage: () => credential },
		vue: {
			...vue,
			onMounted() {},
			onBeforeUnmount: (callback) => cleanups.push(callback),
		},
		marked: { marked: { parse: (content) => String(content) } },
		'element-plus': {
			ElMessage: {
				success: (message) => notices.push(message),
				warning: (message) => notices.push(message),
				error: (message) => notices.push(message),
			},
		},
		'vue-router': { useRoute: () => route, useRouter: () => ({ push() {} }) },
		'~/api/home/ai-agent': {
			streamWithAiAgentReq: async (...args) => {
				streams.push(args)
				return await chat(...args)
			},
		},
		'~/api/home/ai-feedback': {
			submitAiFeedbackReq: (...args) => {
				feedbackRequests.push(args)
				return feedback(...args)
			},
		},
		'~/api/home/agent-tasks': {
			agentTaskRequest: (...args) => {
				requests.push(args)
				return request(...args)
			},
		},
		'~/utils/automation-context.mjs': automation,
		'~/components/ai/AiMascotSprite.vue': { default: {} },
		'~/components/ai/AgentTaskCard.vue': { default: {} },
		'~/components/ai/AiMemoryPanel.vue': { default: {} },
		'~/components/support/AssistantSupportChat.vue': { default: {} },
		'~/utils/assistant-support.mjs': assistantSupport,
		'~/components/ai/ai-assistant-panel.mjs': panel,
		'~/api/home/home': {
			getSourceListReq: async () => ({ data: [] }),
			loadStorageConfigReq: async () => ({ data: {} }),
			loadFileListReq: async () => ({ data: [] }),
		},
		'~/composables/file/useFileUpload': {
			default: () => ({ silentUploadFile: (...args) => uploads.push(args) }),
		},
		'~/stores/storage-config': { default: () => storage },
		'~/stores/file-data': { default: () => files },
		'~/utils/ai-upload-intent.mjs': uploadIntent,
	}
	const executable = compiled
		.replace(
			/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
			(_whole, bindings, name) => {
				const key = JSON.stringify(name)
				return bindings.trim().startsWith('{')
					? `const ${bindings.replace(/\s+as\s+/g, ': ')} = modules[${key}];`
					: `const ${bindings.trim()} = modules[${key}].default;`
			}
		)
		.replace('export default', 'return')
	const context = vm.createContext({
		modules,
		AbortController,
		URL,
		setTimeout,
		clearTimeout,
		console,
		document: { cookie: '' },
		window: {
			localStorage: {
				getItem: (key) => localValues.get(key) || null,
				setItem: (key, value) => localValues.set(key, value),
				removeItem: (key) => localValues.delete(key),
			},
			removeEventListener() {},
			clearTimeout,
		},
		DOMParser: class {
			parseFromString(html) {
				return {
					querySelector: () => ({
						innerHTML: html,
						querySelectorAll: () => [],
					}),
					createTextNode: (text) => ({ textContent: text }),
				}
			}
		},
	})
	const Component = vm.runInContext(`(function(){${executable}\n})()`, context)
	const scope = vue.effectScope()
	let api
	scope.run(() => {
		api = Component.setup({}, { expose() {} })
	})
	return {
		api,
		route,
		storage,
		files,
		requests,
		feedbackRequests,
		streams,
		uploads,
		notices,
		localValues,
		credential,
		unmount() {
			for (const cleanup of cleanups) cleanup()
			scope.stop()
		},
	}
}

test('quick preview makes one explicit preview POST with PENDING card, never confirms, uploads or invokes the model', async () => {
	const app = setupAssistant()
	try {
		await app.api.previewDirectory('ORGANIZE_FILES')
		assert.equal(app.requests.length, 1)
		assert.deepEqual(plain(app.requests[0].slice(0, 3)), [
			'post',
			'',
			{ storageKey: 'workspace-2', path: '/资料', type: 'ORGANIZE_FILES' },
		])
		assert.equal(app.requests[0][3].timeout, 15000)
		assert.equal(app.api.messages.value.at(-1).plans[0].status, 'PENDING')
		assert.equal(app.api.isQuickPreviewing.value, false)
		assert.equal(app.streams.length, 0)
		assert.equal(app.uploads.length, 0)
		assert.equal(
			app.requests.some((args) => /confirm|retry/.test(args[1])),
			false
		)
	} finally {
		app.unmount()
	}
})

test('repeated clicks while preview is pending produce only one POST', async () => {
	const response = deferred()
	const app = setupAssistant({ request: () => response.promise })
	try {
		const first = app.api.previewDirectory('ORGANIZE_FILES')
		const second = app.api.previewDirectory('RENAME_BY_DATE')
		assert.equal(app.requests.length, 1)
		assert.equal(app.api.shortcutBusy.value, true)
		response.resolve({ data: plan() })
		await Promise.all([first, second])
		assert.equal(app.requests.length, 1)
	} finally {
		app.unmount()
	}
})

test('quick preview requires the current local source and explicit matching file-operation permissions', async () => {
	const cases = [
		(app) => {
			app.files.currentStorageSource.type = 's3'
		},
		(app) => {
			app.files.currentStorageSource.key = 'different'
		},
		(app) => {
			app.storage.permission.move = false
		},
		(app) => {
			app.storage.permission.newFolder = false
		},
		(app) => {
			app.storage.permission.move = 'true'
		},
		(app) => {
			app.route.meta.layout = 'admin'
		},
		(app) => {
			app.route.params.fullpath = ['..', 'private']
		},
		(app) => {
			app.route.params.storageKey = ['workspace-2']
		},
	]
	for (const change of cases) {
		const app = setupAssistant()
		try {
			change(app)
			await app.api.previewDirectory('ORGANIZE_FILES')
			assert.equal(app.requests.length, 0)
		} finally {
			app.unmount()
		}
	}
	const app = setupAssistant()
	try {
		app.storage.permission.rename = false
		await app.api.previewDirectory('RENAME_BY_DATE')
		await app.api.previewDirectory('DELETE_FILES')
		assert.equal(app.requests.length, 0)
	} finally {
		app.unmount()
	}
})

test('account switch and logout abort pending preview and suppress the old response after conversation clearing', async () => {
	for (const change of [
		(app) => {
			app.storage.loginInfo.username = '100000002'
		},
		(app) => {
			app.storage.loginInfo.tenantId = 3
		},
		(app) => {
			app.storage.loginInfo.isLogin = false
		},
	]) {
		const response = deferred()
		const app = setupAssistant({ request: () => response.promise })
		try {
			const pending = app.api.previewDirectory('ORGANIZE_FILES')
			change(app)
			await vue.nextTick()
			assert.equal(app.requests[0][3].signal.aborted, true)
			assert.equal(app.api.messages.value.length, 1)
			assert.equal(app.api.messages.value[0].welcome, true)
			response.resolve({ data: plan('old-account') })
			await pending
			assert.equal(
				app.api.messages.value.some((message) => message.plans?.length),
				false
			)
			assert.equal(app.api.isQuickPreviewing.value, false)
		} finally {
			app.unmount()
		}
	}
})

test('unmount aborts pending preview and late completion cannot append a task or error', async () => {
	const response = deferred()
	const app = setupAssistant({ request: () => response.promise })
	const pending = app.api.previewDirectory('ORGANIZE_FILES')
	const count = app.api.messages.value.length
	app.unmount()
	assert.equal(app.requests[0][3].signal.aborted, true)
	response.resolve({ data: plan('late') })
	await pending
	assert.equal(app.api.messages.value.length, count)
	assert.equal(
		app.api.messages.value.some((message) => message.plans?.length),
		false
	)
})

test('routine identity refresh with unchanged account and workspace does not clear or cancel its pending preview', async () => {
	const response = deferred()
	const app = setupAssistant({ request: () => response.promise })
	try {
		const pending = app.api.previewDirectory('ORGANIZE_FILES')
		app.storage.loginInfo = { ...app.storage.loginInfo }
		await vue.nextTick()
		assert.equal(app.requests[0][3].signal.aborted, false)
		assert.equal(app.api.messages.value.length, 2)
		response.resolve({ data: plan() })
		await pending
		assert.equal(app.api.messages.value.at(-1).plans[0].id, 'preview-one')
	} finally {
		app.unmount()
	}
})

test('an aborted preview rejection from an old account does not show an error in the next account', async () => {
	const response = deferred()
	const app = setupAssistant({ request: () => response.promise })
	try {
		const pending = app.api.previewDirectory('ORGANIZE_FILES')
		app.storage.loginInfo.username = '100000002'
		await vue.nextTick()
		response.reject(new Error('old account timeout'))
		await pending
		assert.equal(app.api.messages.value.length, 1)
		assert.equal(app.api.messages.value[0].welcome, true)
		assert.equal(app.api.messages.value[0].error, undefined)
	} finally {
		app.unmount()
	}
})

test('timeout and gateway errors do not auto-retry and ask to inspect the task list first', async () => {
	for (const error of [
		Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }),
		{ response: { data: { msg: '网关暂时不可用' } } },
	]) {
		const app = setupAssistant({
			request: async () => {
				throw error
			},
		})
		try {
			await app.api.previewDirectory('ORGANIZE_FILES')
			await vue.nextTick()
			assert.equal(app.requests.length, 1)
			assert.equal(app.api.isQuickPreviewing.value, false)
			assert.match(
				app.api.messages.value.at(-1).content,
				/先打开自动化任务并刷新列表/
			)
			assert.equal(app.api.messages.value.at(-1).error, true)
			assert.equal(app.streams.length, 0)
		} finally {
			app.unmount()
		}
	}
})

test('a quick preview captures its original directory rather than changing request scope with navigation', async () => {
	const response = deferred()
	const app = setupAssistant({ request: () => response.promise })
	try {
		const pending = app.api.previewDirectory('ORGANIZE_FILES')
		app.route.params.fullpath = ['新目录']
		app.route.path = '/workspace-2/新目录'
		await vue.nextTick()
		assert.equal(app.api.automationContext.value.path, '/新目录')
		assert.equal(app.requests[0][2].path, '/资料')
		assert.match(app.api.messages.value.at(-1).content, /\/资料$/)
		response.resolve({ data: plan() })
		await pending
		assert.equal(
			app.api.messages.value.at(-1).plans[0].steps[0].sourcePath,
			'/资料/a.txt'
		)
	} finally {
		app.unmount()
	}
})

test('task card updated/created events synchronize message plans, deduplicate new previews and ignore cleared conversations', async () => {
	const app = setupAssistant()
	try {
		await app.api.previewDirectory('ORGANIZE_FILES')
		const message = app.api.messages.value.at(-1)
		app.api.updateMessagePlan(message, plan('preview-one', 'RUNNING'))
		assert.equal(message.plans[0].status, 'RUNNING')
		app.api.addMessagePlan(message, plan('retry-two'))
		app.api.addMessagePlan(message, plan('retry-two'))
		assert.equal(message.plans.length, 2)
		app.api.clearMessages()
		app.api.addMessagePlan(message, plan('late-card'))
		app.api.updateMessagePlan(message, plan('preview-one', 'SUCCEEDED'))
		assert.equal(message.plans.length, 2)
		assert.equal(message.plans[0].status, 'RUNNING')
		assert.equal(app.api.messages.value.length, 1)
		assert.match(source, /@updated="updateMessagePlan\(message, \$event\)"/)
		assert.match(source, /@created="addMessagePlan\(message, \$event\)"/)
	} finally {
		app.unmount()
	}
})

test('pressing Enter to chat cannot start a model stream while a quick preview is pending', async () => {
	const response = deferred()
	const app = setupAssistant({ request: () => response.promise })
	try {
		const pending = app.api.previewDirectory('ORGANIZE_FILES')
		app.api.inputMessage.value = '请解释一下目录里的资料'
		await app.api.sendMessage()
		assert.equal(app.streams.length, 0)
		response.resolve({ data: plan() })
		await pending
	} finally {
		app.unmount()
	}
})

test('asking for human service opens a review card in the assistant without invoking AI or creating a conversation', async () => {
	const app = setupAssistant()
	try {
		app.api.inputMessage.value = '人工服务'
		await app.api.sendMessage()
		assert.equal(app.streams.length, 0)
		assert.equal(app.requests.length, 0)
		assert.match(app.api.supportOffer.value.reason, /转接全局管理员/)
		assert.equal(app.api.supportMode.value, false)
		assert.equal(app.api.supportSession.value, null)
		assert.equal(app.api.supportIssue.value, '我需要人工客服协助。')
		app.api.supportIssue.value = ''
		app.api.confirmHumanSupport()
		assert.equal(
			app.api.supportMode.value,
			false,
			'empty issues require a user description'
		)
		app.api.supportIssue.value = '上传文件一直失败，请帮助检查'
		app.api.confirmHumanSupport()
		assert.equal(app.api.supportMode.value, true)
		assert.equal(
			app.api.supportSession.value.issue,
			'上传文件一直失败，请帮助检查'
		)
		assert.equal(app.api.supportSession.value.issue.includes('AI 对话'), false)
		const key = app.api.supportSession.value.clientMessageId
		app.api.supportMode.value = false
		app.api.requestHumanSupport()
		assert.equal(app.api.supportMode.value, true)
		assert.equal(app.api.supportSession.value.clientMessageId, key)
	} finally {
		app.unmount()
	}
})

test('a model suggestion only opens human review after a repeated unresolved exchange', async () => {
	const app = setupAssistant({
		chat: async () => ({
			answer: '这个问题需要管理员检查。',
			supportSuggestion: { reason: '需要人工核实空间配置。' },
		}),
	})
	try {
		app.api.inputMessage.value = '为什么上传完成后文件找不到？'
		await app.api.sendMessage()
		assert.equal(app.streams.length, 1)
		assert.equal(app.api.supportOffer.value, null)
		app.api.inputMessage.value = '还是不行'
		await app.api.sendMessage()
		assert.equal(app.api.supportOffer.value, null)
		app.api.inputMessage.value = '请继续排查原因'
		await app.api.sendMessage()
		assert.equal(app.streams.length, 3)
		assert.equal(app.api.supportIssue.value, '请继续排查原因')
		assert.equal(app.api.supportOffer.value.reason, '需要人工核实空间配置。')
		assert.equal(app.api.supportSession.value, null)
		app.api.dismissSupportOffer()
		assert.equal(app.api.supportOffer.value, null)
		assert.equal(app.api.supportMode.value, false)
		assert.equal(app.requests.length, 0)
	} finally {
		app.unmount()
	}
})

test('unhelpful answer feedback offers the relevant question without assistant answer or attachments', async () => {
	const app = setupAssistant({
		chat: async () => ({ answer: 'PRIVATE-ASSISTANT-ANSWER' }),
	})
	try {
		app.api.inputMessage.value = '上传提示权限不足怎么办？'
		app.api.selectedFiles.value = [{ name: 'private.txt', size: 15 }]
		await app.api.sendMessage()
		const reply = app.api.messages.value.at(-1)
		app.api.reportUnhelpful(reply)
		assert.equal(app.api.supportOffer.value, null)
		app.api.requestHumanSupport()
		assert.equal(app.api.supportIssue.value, '上传提示权限不足怎么办？')
		assert.equal(app.api.supportMode.value, false)
		assert.equal(app.api.supportSession.value, null)
		assert.equal(app.api.supportIssue.value.includes('PRIVATE'), false)
		assert.equal(app.api.supportIssue.value.includes('private.txt'), false)
		app.api.supportIssue.value = '仅发送这条新描述'
		app.api.confirmHumanSupport()
		assert.equal(app.api.supportSession.value.issue, '仅发送这条新描述')
		assert.equal(
			app.api.messages.value.at(-1).content,
			'PRIVATE-ASSISTANT-ANSWER',
			'AI history remains available when returning'
		)
	} finally {
		app.unmount()
	}
})

test('first unresolved feedback continues AI troubleshooting without a handoff prompt', async () => {
	const app = setupAssistant()
	try {
		app.api.inputMessage.value = '上传失败怎么办？'
		await app.api.sendMessage()
		app.api.inputMessage.value = '还是不行'
		await app.api.sendMessage()
		assert.equal(app.streams.length, 2)
		assert.equal(app.api.supportOffer.value, null)
		assert.equal(app.api.supportMode.value, false)
	} finally {
		app.unmount()
	}
})

test('ordinary answers do not prompt and an explicit decline is passed to AI instead of being rerouted', async () => {
	for (const chat of [
		async () => ({ answer: '请继续查看操作步骤。' }),
		async () => ({
			answer: '建议人工',
			supportSuggestion: { reason: '意外的旧服务建议' },
		}),
		async () => {
			throw new Error('网络中断')
		},
	]) {
		const app = setupAssistant({ chat })
		try {
			app.api.inputMessage.value = '不要转人工，继续说明操作步骤'
			await app.api.sendMessage()
			assert.equal(app.streams.length, 1)
			assert.equal(app.api.supportOffer.value, null)
			assert.equal(app.api.supportMode.value, false)
		} finally {
			app.unmount()
		}
	}
})

test('a single transport failure or stopped response does not push human service', async () => {
	for (const error of [
		new Error('网络中断'),
		Object.assign(new Error('stopped'), { name: 'AbortError' }),
	]) {
		const app = setupAssistant({
			chat: async () => {
				throw error
			},
		})
		try {
			app.api.inputMessage.value = '查一下我的存储空间'
			await app.api.sendMessage()
			assert.equal(app.api.supportOffer.value, null)
			assert.equal(app.api.supportSession.value, null)
			assert.equal(app.api.supportMode.value, false)
		} finally {
			app.unmount()
		}
	}
})

test('global admins cannot open member support and a role or account change clears a confirmed handoff', async () => {
	const app = setupAssistant()
	try {
		app.api.supportIssue.value = '私人问题'
		app.api.requestHumanSupport()
		app.api.supportIssue.value = '私人问题'
		app.api.confirmHumanSupport()
		assert.equal(app.api.supportMode.value, true)
		app.storage.loginInfo.role = 'ADMIN'
		await vue.nextTick()
		assert.equal(app.api.supportSession.value, null)
		assert.equal(app.api.supportMode.value, false)
		app.api.requestHumanSupport()
		assert.equal(app.api.supportOffer.value, null)
	} finally {
		app.unmount()
	}
})

test('a late model suggestion cannot open support for the next logged-in account', async () => {
	const result = deferred()
	const app = setupAssistant({ chat: () => result.promise })
	try {
		app.api.inputMessage.value = '第一个账号的私有问题'
		const pending = app.api.sendMessage()
		app.storage.loginInfo.username = 'second-user'
		await vue.nextTick()
		result.resolve({
			answer: 'old answer',
			supportSuggestion: { reason: 'old private reason' },
		})
		await pending
		assert.equal(app.api.supportOffer.value, null)
		assert.equal(app.api.supportSession.value, null)
		assert.equal(app.api.messages.value.length, 1)
		assert.equal(app.api.isLoading.value, false)
	} finally {
		app.unmount()
	}
})

test('a credential change clears confirmed handoff synchronously before user profile refresh', async () => {
	const app = setupAssistant({ token: 'first-session' })
	try {
		app.api.requestHumanSupport()
		app.api.supportIssue.value = '第一个登录身份确认的问题'
		app.api.confirmHumanSupport()
		assert.equal(app.api.supportMode.value, true)
		app.credential.value = 'replacement-session'
		assert.equal(app.api.supportSession.value, null)
		assert.equal(app.api.supportMode.value, false)
		assert.equal(app.api.supportOffer.value, null)
	} finally {
		app.unmount()
	}
})

test('unresolved feedback records only the reviewed turn and offers human service without creating it', async () => {
	const app = setupAssistant({ chat: async () => ({ answer: '本轮回答内容' }) })
	try {
		app.api.messages.value.push(
			app.api.createMessage('user', 'PREVIOUS-PRIVATE-QUESTION')
		)
		app.api.messages.value.push(
			app.api.createMessage('assistant', 'PREVIOUS-PRIVATE-ANSWER')
		)
		app.api.inputMessage.value = '当前问题'
		app.api.selectedFiles.value = [{ name: 'PRIVATE-FILE.txt', size: 12 }]
		await app.api.sendMessage()
		assert.equal(app.feedbackRequests.length, 0)
		const reply = app.api.messages.value.at(-1)
		await app.api.reportUnhelpful(reply)
		assert.equal(app.feedbackRequests.length, 1)
		const payload = plain(app.feedbackRequests[0][0])
		assert.deepEqual(Object.keys(payload).sort(), [
			'answer',
			'clientMessageId',
			'pagePath',
			'question',
		])
		assert.equal(payload.question, '当前问题')
		assert.equal(payload.answer, '本轮回答内容')
		assert.equal(JSON.stringify(payload).includes('PRIVATE'), false)
		assert.equal(reply.feedbackState, 'saved')
		assert.equal(app.api.supportSession.value, null)
		assert.equal(app.api.supportOffer.value, null)
		app.api.requestHumanSupport()
		assert.ok(app.api.supportOffer.value)
		assert.equal(app.api.submittedSupportFeedback.value, true)
		app.api.dismissSupportOffer()
		assert.equal(app.api.submittedSupportFeedback.value, true)
		assert.equal(
			reply.feedbackState,
			'saved',
			'canceling handoff keeps submitted feedback'
		)
		await app.api.reportUnhelpful(reply)
		assert.equal(app.feedbackRequests.length, 1)
	} finally {
		app.unmount()
	}
})

test('uncertain feedback preserves its exact turn and idempotency key on explicit retry', async () => {
	let attempts = 0
	const app = setupAssistant({
		feedback: async () => {
			if (++attempts === 1) throw new Error('unknown')
			return { data: { id: 'a'.repeat(32) } }
		},
	})
	try {
		app.api.inputMessage.value = '原问题'
		await app.api.sendMessage()
		const reply = app.api.messages.value.at(-1)
		await app.api.reportUnhelpful(reply)
		assert.equal(reply.feedbackState, 'error')
		const payload = plain(app.feedbackRequests[0][0])
		app.api.inputMessage.value = '尚未发送的新问题'
		await app.api.reportUnhelpful(reply)
		assert.deepEqual(plain(app.feedbackRequests[1][0]), payload)
		assert.equal(reply.feedbackState, 'saved')
	} finally {
		app.unmount()
	}
})

test('pending feedback deduplicates clicks and late acknowledgements cannot affect another account', async () => {
	const response = deferred()
	const app = setupAssistant({ feedback: () => response.promise })
	try {
		app.api.inputMessage.value = '私人问题'
		await app.api.sendMessage()
		const reply = app.api.messages.value.at(-1)
		const pending = app.api.reportUnhelpful(reply)
		await app.api.reportUnhelpful(reply)
		assert.equal(app.feedbackRequests.length, 1)
		app.storage.loginInfo.username = 'other-user'
		assert.equal(app.feedbackRequests[0][1].signal.aborted, true)
		response.resolve({ data: { id: 'c'.repeat(32) } })
		await pending
		assert.equal(app.api.messages.value.length, 1)
		assert.equal(app.api.supportOffer.value, null)
	} finally {
		app.unmount()
	}
})
