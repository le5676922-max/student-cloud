import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
	authorizedTaskForm,
	createWorkbenchResources,
	groupTasks,
	localSources,
	suggestedScheduleTitle,
	targetFromContext,
} from '../src/utils/agent-workbench.mjs'

const sources = [
	{ key: 'mine', name: '本人资料', type: { key: 'local' } },
	{ key: 'cloud', name: '对象存储', type: 's3' },
]
const validPath = (path) =>
	typeof path === 'string' && path.startsWith('/') && !path.includes('..')
		? path.replace(/\/$/, '') || '/'
		: null

test('only permitted local sources are offered and a single source is a form default, never an action', () => {
	assert.deepEqual(
		localSources(sources).map((item) => item.key),
		['mine']
	)
	assert.equal(targetFromContext({}, sources).storageKey, 'mine')
	assert.equal(targetFromContext({}, sources).path, '/')
	assert.equal(
		targetFromContext({}, [...sources, { key: 'other', type: 'local' }])
			.storageKey,
		''
	)
	assert.equal(
		targetFromContext({ storageKey: 'unknown', path: '/private' }, sources)
			.storageKey,
		''
	)
	assert.equal(
		targetFromContext({ storageKey: 'cloud', path: '/private' }, sources)
			.storageKey,
		''
	)
	assert.equal(
		targetFromContext({ storageKey: 'unknown', path: '/private' }, sources)
			.path,
		'/'
	)
})

test('current directory context only attaches to an authorized source and an allowed operation', () => {
	assert.equal(
		targetFromContext({}, sources, { sourceRequested: true }).storageKey,
		''
	)
	assert.equal(
		targetFromContext({ storageKey: 'mine' }, sources, { pathRequested: true })
			.path,
		''
	)
	assert.deepEqual(
		targetFromContext(
			{ storageKey: 'mine', path: '/课件', type: 'IMPORT_LEARNING' },
			sources
		),
		{
			storageKey: 'mine',
			path: '/课件',
			type: 'IMPORT_LEARNING',
			matched: true,
		}
	)
	assert.equal(
		targetFromContext({ storageKey: 'mine', type: 'DELETE_FILES' }, sources)
			.type,
		'ORGANIZE_FILES'
	)
})

test('repeated task or submitted form cannot introduce a new source, course or execution argument', () => {
	const form = {
		type: 'IMPORT_LEARNING',
		storageKey: 'mine',
		path: '/课件/',
		courseId: 3,
		tags: '资料',
		userId: 999,
		confirmed: true,
	}
	const courses = [{ id: 3, name: '本人的课程' }]
	assert.deepEqual(authorizedTaskForm(form, sources, courses, validPath), {
		type: 'IMPORT_LEARNING',
		storageKey: 'mine',
		path: '/课件',
		courseId: 3,
		tags: '资料',
	})
	assert.equal(
		authorizedTaskForm(
			{ ...form, storageKey: 'foreign' },
			sources,
			courses,
			validPath
		),
		null
	)
	assert.equal(
		authorizedTaskForm(
			{ ...form, storageKey: 'cloud' },
			sources,
			courses,
			validPath
		),
		null
	)
	assert.equal(
		authorizedTaskForm({ ...form, courseId: 99 }, sources, courses, validPath),
		null
	)
	assert.equal(
		authorizedTaskForm(
			{ ...form, path: '/../private' },
			sources,
			courses,
			validPath
		),
		null
	)
	assert.equal(
		authorizedTaskForm(
			{ ...form, type: 'MOVE_ALL' },
			sources,
			courses,
			validPath
		),
		null
	)
	assert.equal(
		authorizedTaskForm(
			{ ...form, type: 'ORGANIZE_FILES' },
			sources,
			[],
			validPath
		).courseId,
		null
	)
})

test('task buckets separate expired plans from pending and running plans', () => {
	const grouped = groupTasks(
		[
			{ id: 'pending', status: 'PENDING', expiresAt: 200 },
			{ id: 'expired', status: 'PENDING', expiresAt: 50 },
			{ id: 'running', status: 'RUNNING' },
			{ id: 'done', status: 'SUCCEEDED' },
		],
		100
	)
	assert.deepEqual(
		grouped.pending.map((item) => item.id),
		['pending']
	)
	assert.deepEqual(
		grouped.running.map((item) => item.id),
		['running']
	)
	assert.deepEqual(
		grouped.history.map((item) => item.id),
		['expired', 'done']
	)
	assert.equal(
		suggestedScheduleTitle('ORGANIZE_FILES', '/学习/资料'),
		'每周分类整理 · 资料'
	)
	assert.equal(
		suggestedScheduleTitle('IMPORT_LEARNING', '/'),
		'每周课程归档 · 当前目录'
	)
})

test('first visit loads only sources/tasks; courses and schedules are lazy and reused; task refresh is independent', async () => {
	const calls = { sources: 0, tasks: 0, courses: 0, schedules: 0 }
	const requests = Object.fromEntries(
		Object.keys(calls).map((key) => [
			key,
			async () => {
				calls[key]++
				return [key]
			},
		])
	)
	const loader = createWorkbenchResources({ requests, onChange() {} })
	await loader.initialize()
	assert.deepEqual(calls, { sources: 1, tasks: 1, courses: 0, schedules: 0 })
	await loader.load('courses')
	await loader.load('courses')
	await loader.load('schedules')
	await loader.load('schedules')
	await loader.refreshTasks()
	assert.deepEqual(calls, { sources: 1, tasks: 2, courses: 1, schedules: 1 })
	loader.dispose()
})

test('one failed resource cannot clear another successfully loaded resource', async () => {
	const states = {}
	const loader = createWorkbenchResources({
		requests: {
			sources: async () => {
				throw new Error('unavailable')
			},
			tasks: async () => [{ id: 'recent' }],
		},
		onChange: (name, state) => {
			states[name] = state
		},
	})
	await loader.initialize()
	assert.equal(states.sources.loading, false)
	assert.equal(states.sources.error.message, 'unavailable')
	assert.equal(states.tasks.loaded, true)
	assert.deepEqual(states.tasks.value, [{ id: 'recent' }])
	loader.dispose()
})

test('refresh aborts an old read and a late result cannot replace the new task list', async () => {
	const updates = []
	let oldResolve,
		oldSignal,
		calls = 0
	const loader = createWorkbenchResources({
		requests: {
			tasks: ({ signal }) => {
				if (++calls === 1) {
					oldSignal = signal
					return new Promise((resolve) => {
						oldResolve = resolve
					})
				}
				return Promise.resolve(['latest'])
			},
		},
		onChange: (_, state) => {
			if (state.loaded && !state.loading) updates.push(state.value)
		},
	})
	const old = loader.load('tasks')
	await Promise.resolve()
	await loader.refreshTasks()
	assert.equal(oldSignal.aborted, true)
	oldResolve(['stale'])
	await old
	assert.deepEqual(updates, [['latest']])
	loader.dispose()
})

test('duplicate lazy reads share one request; disposal aborts it and suppresses updates', async () => {
	let calls = 0,
		signal,
		resolve
	const updates = []
	const loader = createWorkbenchResources({
		requests: {
			courses: (config) => {
				calls++
				signal = config.signal
				return new Promise((done) => {
					resolve = done
				})
			},
		},
		onChange: (_, state) => updates.push(state),
	})
	const first = loader.load('courses')
	const second = loader.load('courses')
	await Promise.resolve()
	assert.equal(calls, 1)
	loader.dispose()
	assert.equal(signal.aborted, true)
	resolve(['late'])
	await Promise.all([first, second])
	assert.equal(updates.length, 1)
	assert.equal(await loader.load('courses'), null)
})

test('a hung read reaches its deadline, ends loading and may be retried explicitly', async () => {
	let tick, signal, state
	const loader = createWorkbenchResources({
		requests: {
			schedules: (config) => {
				signal = config.signal
				return new Promise(() => {})
			},
		},
		onChange: (_, value) => {
			state = value
		},
		schedule: (callback) => {
			tick = callback
			return 1
		},
		unschedule() {},
	})
	const pending = loader.load('schedules')
	await Promise.resolve()
	tick()
	assert.equal(await pending, null)
	assert.equal(signal.aborted, true)
	assert.equal(state.loading, false)
	assert.equal(state.error.message, 'REQUEST_TIMEOUT')
	loader.dispose()
})

test('page form submission is explicit, repeat only fills the form, schedule names respect user edits and API accepts cancellation', async () => {
	const page = await readFile(
		new URL('../src/pages/agent-tasks.vue', import.meta.url),
		'utf8'
	)
	const repeat = page.slice(
		page.indexOf('async function repeatPlan('),
		page.indexOf('function sourceName(')
	)
	assert.doesNotMatch(
		repeat,
		/agentTaskRequest|agentScheduleRequest|confirmed:\s*true/
	)
	assert.match(repeat, /authorizedTaskForm\(/)
	assert.match(page, /if \(!scheduleTitleEdited\.value\)/)
	assert.match(page, /@input="scheduleTitleEdited = true"/)
	assert.match(page, /@repeat="repeatPlan"/)
	assert.match(page, /:allow-repeat="true"/)
	assert.match(page, /@click="refreshTasks"/)
	assert.match(page, /loader\.dispose\(\)/)
	const api = await readFile(
		new URL('../src/api/home/agent-schedules.js', import.meta.url),
		'utf8'
	)
	assert.match(api, /config = \{\}/)
	assert.match(api, /timeout: 15000, \.\.\.config/)
})
