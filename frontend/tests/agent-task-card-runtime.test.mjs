import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as taskUtils from '../src/utils/agent-task.mjs'
import * as cardUtils from '../src/utils/agent-task-card.mjs'

const source = await readFile(
	new URL('../src/components/ai/AgentTaskCard.vue', import.meta.url),
	'utf8'
)
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, {
	id: 'agent-card-test',
	inlineTemplate: true,
}).content

function deferred() {
	let resolve
	const promise = new Promise((finish) => {
		resolve = finish
	})
	return { promise, resolve }
}
function timers() {
	const callbacks = new Map()
	let sequence = 0,
		scheduled = 0
	return {
		callbacks,
		get scheduled() {
			return scheduled
		},
		schedule(callback) {
			scheduled++
			callbacks.set(++sequence, callback)
			return sequence
		},
		unschedule(id) {
			callbacks.delete(id)
		},
		async tick() {
			const [id, callback] = callbacks.entries().next().value
			callbacks.delete(id)
			await callback()
		},
	}
}
const sample = (status = 'PENDING') => ({
	id: 'task-one',
	title: '按文件类型整理目录',
	status,
	expiresAt: Date.now() + 600_000,
	notice: '不覆盖、不删除，失败后停止。',
	steps: [
		{
			action: 'MOVE_FILE',
			storageKey: 'workspace-2',
			sourcePath: '/资料/a.txt',
			targetPath: '/资料/文档/a.txt',
		},
	],
	results:
		status === 'SUCCEEDED'
			? [{ index: 0, success: true, message: '完成' }]
			: status === 'FAILED'
			? [{ index: 0, success: false, message: '目标文件已存在' }]
			: [],
})

function mountCard(
	plan,
	{
		request = async () => ({ data: plan }),
		allowRepeat = false,
		confirmation = async () => true,
	} = {}
) {
	const clock = timers(),
		requests = [],
		messages = [],
		updated = [],
		created = [],
		repeated = [],
		fileChanges = []
	const modules = {
		vue,
		'@vueuse/core': {
			useEventBus: () => ({ emit: (value) => fileChanges.push(value) }),
		},
		'element-plus': {
			ElMessage: {
				success: (message) => messages.push(message),
				error: (message) => messages.push(message),
			},
			ElMessageBox: { confirm: confirmation },
		},
		'~/api/home/agent-tasks': {
			agentTaskRequest: (...args) => {
				requests.push(args)
				return request(...args)
			},
		},
		'~/utils/agent-task.mjs': {
			...taskUtils,
			createTaskPoller: (options) =>
				taskUtils.createTaskPoller({
					...options,
					schedule: clock.schedule,
					unschedule: clock.unschedule,
				}),
		},
		'~/utils/agent-task-card.mjs': cardUtils,
	}
	const executable = compiled
		.replace(
			/import \{([\s\S]*?)\} from ['"]([^'"]+)['"]/g,
			(_whole, names, module) =>
				`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
					module
				)}]`
		)
		.replace('export default', 'return')
	const Card = new Function('modules', executable)(modules)
	const element = (type) => ({
		type,
		children: [],
		props: {},
		parent: null,
		text: '',
	})
	const renderer = vue.createRenderer({
		createElement: element,
		createText: (text) => ({ ...element('#text'), text }),
		createComment: (text) => ({ ...element('#comment'), text }),
		insert(node, parent, anchor) {
			if (node.parent) {
				const old = node.parent.children.indexOf(node)
				if (old >= 0) node.parent.children.splice(old, 1)
			}
			const index = anchor ? parent.children.indexOf(anchor) : -1
			parent.children.splice(
				index < 0 ? parent.children.length : index,
				0,
				node
			)
			node.parent = parent
		},
		remove(node) {
			const index = node.parent?.children.indexOf(node)
			if (index >= 0) node.parent.children.splice(index, 1)
			node.parent = null
		},
		setElementText(node, text) {
			node.text = text
			node.children = []
		},
		setText(node, text) {
			node.text = text
		},
		setComment(node, text) {
			node.text = text
		},
		parentNode: (node) => node.parent,
		nextSibling(node) {
			return (
				node.parent?.children[node.parent.children.indexOf(node) + 1] || null
			)
		},
		patchProp(node, key, _previous, value) {
			node.props[key] = value
		},
	})
	const current = vue.ref(plan),
		root = element('root')
	const app = renderer.createApp({
		render: () =>
			vue.h(Card, {
				plan: current.value,
				allowRepeat,
				onUpdated(value) {
					updated.push(value)
					current.value = value
				},
				onCreated: (value) => created.push(value),
				onRepeat: (value) => repeated.push(value),
			}),
	})
	app.component('ElButton', {
		inheritAttrs: false,
		props: ['disabled', 'loading'],
		setup(props, { attrs, slots }) {
			return () =>
				vue.h(
					'button',
					{ ...attrs, disabled: props.disabled },
					slots.default?.()
				)
		},
	})
	app.component('RouterLink', {
		setup(_props, { slots }) {
			return () => vue.h('a', slots.default?.())
		},
	})
	app.mount(root)
	const text = (node) => node.text + node.children.map(text).join('')
	const find = (predicate, node = root) =>
		predicate(node)
			? node
			: node.children.map((child) => find(predicate, child)).find(Boolean)
	return {
		app,
		current,
		clock,
		requests,
		messages,
		updated,
		created,
		repeated,
		fileChanges,
		root,
		find,
		text,
		button: (label) =>
			find((node) => node.type === 'button' && text(node).includes(label)),
	}
}

test('runtime pending steps expand, completed history folds and failure warns outside details', async () => {
	for (const status of [
		'PENDING',
		'SUCCEEDED',
		'FAILED',
		'INTERRUPTED',
		'UNKNOWN',
	]) {
		const card = mountCard(sample(status))
		try {
			const details = card.find((node) => node.props.class === 'task-details')
			assert.equal(details.props.open, status === 'PENDING')
			assert.equal(
				Boolean(card.find((node) => node.props.class === 'task-expiry')),
				status === 'PENDING'
			)
			assert.equal(Boolean(card.button('确认执行')), status === 'PENDING')
			if (['FAILED', 'INTERRUPTED', 'UNKNOWN'].includes(status)) {
				const warning = card.find((node) => node.props.class === 'task-warning')
				assert.equal(warning.props.role, 'alert')
				assert.notEqual(warning.parent, details)
			}
			assert.equal(card.requests.length, 0)
		} finally {
			card.app.unmount()
		}
	}
})

test('runtime repeated RUNNING props and parent updates do not restart polling', async () => {
	const plan = sample('RUNNING')
	const card = mountCard(plan, { request: async () => ({ data: { ...plan } }) })
	try {
		assert.equal(card.clock.scheduled, 1)
		card.current.value = { ...plan }
		await vue.nextTick()
		assert.equal(card.clock.scheduled, 1)
		await card.clock.tick()
		await vue.nextTick()
		assert.equal(card.clock.scheduled, 2)
		assert.equal(card.clock.callbacks.size, 1)
		assert.equal(card.requests.length, 1)
	} finally {
		card.app.unmount()
	}
	assert.equal(card.clock.callbacks.size, 0)
})

test('runtime retry aborts on unmount and cannot emit a late created preview', async () => {
	const response = deferred()
	const card = mountCard(sample('FAILED'), { request: () => response.promise })
	const running = card.button('重新预览失败项').props.onClick()
	assert.equal(card.requests.length, 1)
	const signal = card.requests[0][3].signal
	card.app.unmount()
	assert.equal(signal.aborted, true)
	response.resolve({ data: sample() })
	await running
	assert.deepEqual(card.created, [])
	assert.deepEqual(card.messages, [])
})

test('runtime refresh aborts on unmount without changing or emitting late task state', async () => {
	const response = deferred()
	const card = mountCard(sample('RUNNING'), { request: () => response.promise })
	const running = card.button('刷新状态').props.onClick()
	card.app.unmount()
	assert.equal(card.requests[0][3].signal.aborted, true)
	response.resolve({ data: sample('SUCCEEDED') })
	await running
	assert.deepEqual(card.updated, [])
	assert.deepEqual(card.fileChanges, [])
	assert.deepEqual(card.messages, [])
})

test('runtime repeat only emits safe form fields, remains opt-in and never makes a request', async () => {
	const card = mountCard(sample('SUCCEEDED'), { allowRepeat: true })
	try {
		card.button('再建同类任务').props.onClick()
		assert.deepEqual(card.repeated, [
			{
				type: 'ORGANIZE_FILES',
				storageKey: 'workspace-2',
				path: '/资料',
				courseId: null,
				tags: '',
			},
		])
		assert.equal(card.requests.length, 0)
		assert.equal(card.fileChanges.length, 0)
	} finally {
		card.app.unmount()
	}
	const embedded = mountCard(sample('SUCCEEDED'))
	try {
		assert.equal(embedded.button('再建同类任务'), undefined)
	} finally {
		embedded.app.unmount()
	}
})

test('runtime double confirmation opens one dialog and unmount prevents any later POST', async () => {
	const dialog = deferred()
	let dialogs = 0
	const card = mountCard(sample(), {
		confirmation: () => {
			dialogs++
			return dialog.promise
		},
	})
	const first = card.button('确认执行').props.onClick()
	const second = card.button('确认执行').props.onClick()
	assert.equal(dialogs, 1)
	card.app.unmount()
	dialog.resolve(true)
	await Promise.all([first, second])
	assert.equal(card.requests.length, 0)
})

test('runtime accepted confirmation makes exactly one explicit POST and observes completion once', async () => {
	const card = mountCard(sample(), {
		request: async (method) => ({
			data: sample(method === 'post' ? 'RUNNING' : 'SUCCEEDED'),
		}),
	})
	try {
		await card.button('确认执行').props.onClick()
		await vue.nextTick()
		assert.equal(card.requests.length, 1)
		assert.deepEqual(card.requests[0].slice(0, 3), [
			'post',
			'/task-one/confirm',
			{ confirmed: true },
		])
		await card.clock.tick()
		await vue.nextTick()
		assert.equal(card.requests.length, 2)
		assert.equal(card.clock.callbacks.size, 0)
		assert.deepEqual(card.fileChanges, [
			{ storageKey: 'workspace-2', path: '/资料' },
			{ storageKey: 'workspace-2', path: '/资料/文档' },
		])
		card.current.value = sample('SUCCEEDED')
		await vue.nextTick()
		assert.equal(card.fileChanges.length, 2)
	} finally {
		card.app.unmount()
	}
})
