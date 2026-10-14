import { taskStatus } from './agent-task.mjs'

export const taskOptions = Object.freeze([
	{
		value: 'ORGANIZE_FILES',
		name: '分类整理',
		description: '按类型放进文档、图片等文件夹',
		icon: 'folder',
	},
	{
		value: 'RENAME_BY_DATE',
		name: '统一命名',
		description: '按修改日期生成整齐的文件名',
		icon: 'calendar',
	},
	{
		value: 'IMPORT_LEARNING',
		name: '课程归档',
		description: '建立课程资料索引，不移动文件',
		icon: 'book',
	},
])

/** Only the source list returned for this account may supply a task target. */
export function localSources(value) {
	return (Array.isArray(value) ? value : []).filter(
		(source) =>
			(typeof source?.key === 'string' || typeof source?.key === 'number') &&
			(source.type?.key || source.type) === 'local'
	)
}

export function targetFromContext(
	context,
	sources,
	{ sourceRequested = Boolean(context?.storageKey), pathRequested = false } = {}
) {
	const permitted = localSources(sources)
	const source = permitted.find(
		(item) => String(item.key) === String(context?.storageKey)
	)
	return {
		storageKey:
			source?.key ??
			(!sourceRequested && permitted.length === 1 ? permitted[0].key : ''),
		// A directory from an unknown storage cannot be transferred to another one.
		path:
			pathRequested && !context?.path
				? ''
				: source && context?.path
				? context.path
				: '/',
		type: taskOptions.some((option) => option.value === context?.type)
			? context.type
			: 'ORGANIZE_FILES',
		matched: Boolean(source),
	}
}

export function authorizedTaskForm(form, sources, courses, validatePath) {
	const source = localSources(sources).find(
		(item) => String(item.key) === String(form.storageKey)
	)
	const path = validatePath(form.path)
	if (!source || !path || !taskOptions.some((item) => item.value === form.type))
		return null
	const course =
		form.type === 'IMPORT_LEARNING'
			? courses.find((item) => String(item.id) === String(form.courseId))
			: null
	if (form.type === 'IMPORT_LEARNING' && !course) return null
	return {
		type: form.type,
		storageKey: source.key,
		path,
		courseId: course?.id ?? null,
		tags: typeof form.tags === 'string' ? form.tags.slice(0, 200) : '',
	}
}

export function groupTasks(plans, now = Date.now()) {
	const groups = { pending: [], running: [], history: [] }
	for (const plan of Array.isArray(plans) ? plans : []) {
		const status = taskStatus(plan, now)
		groups[
			status === 'PENDING'
				? 'pending'
				: status === 'RUNNING'
				? 'running'
				: 'history'
		].push(plan)
	}
	return groups
}

export function suggestedScheduleTitle(type, path) {
	const name =
		taskOptions.find((option) => option.value === type)?.name || '整理'
	const directory =
		typeof path === 'string' && path !== '/'
			? path.split('/').filter(Boolean).at(-1)
			: '当前目录'
	return `每周${name} · ${directory || '当前目录'}`.slice(0, 100)
}

/** Resource failures are independent; only lazy resources are reused in this page. */
export function createWorkbenchResources({
	requests,
	onChange,
	timeout = 15000,
	schedule = setTimeout,
	unschedule = clearTimeout,
}) {
	const states = {}
	const active = new Map()
	let disposed = false
	for (const name of Object.keys(requests))
		states[name] = { value: [], loading: false, loaded: false, error: null }
	function notify(name) {
		if (!disposed) onChange(name, { ...states[name] })
	}
	async function load(name, { force = false } = {}) {
		if (disposed || !states[name]) return null
		if (!force && states[name].loaded) return states[name].value
		if (!force && active.has(name)) return active.get(name).promise
		active.get(name)?.controller.abort()
		const controller = new AbortController()
		const token = { controller }
		active.set(name, token)
		states[name] = { ...states[name], loading: true, error: null }
		notify(name)
		let timer, abortListener
		const deadline = new Promise((_, reject) => {
			abortListener = () => reject(new Error('REQUEST_ABORTED'))
			controller.signal.addEventListener('abort', abortListener, { once: true })
			timer = schedule(() => {
				reject(new Error('REQUEST_TIMEOUT'))
				controller.abort()
			}, timeout)
		})
		token.promise = (async () => {
			try {
				const value = await Promise.race([
					Promise.resolve().then(() =>
						requests[name]({ signal: controller.signal, timeout })
					),
					deadline,
				])
				if (disposed || active.get(name) !== token) return null
				states[name] = { value, loading: false, loaded: true, error: null }
				notify(name)
				return value
			} catch (error) {
				if (disposed || active.get(name) !== token) return null
				states[name] = { ...states[name], loading: false, error }
				notify(name)
				return null
			} finally {
				unschedule(timer)
				controller.signal.removeEventListener('abort', abortListener)
				if (active.get(name) === token) active.delete(name)
			}
		})()
		return token.promise
	}
	function dispose() {
		disposed = true
		for (const { controller } of active.values()) controller.abort()
		active.clear()
	}
	return {
		load,
		initialize: () => Promise.allSettled([load('sources'), load('tasks')]),
		refreshTasks: () => load('tasks', { force: true }),
		dispose,
	}
}
