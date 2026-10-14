import { taskStatus } from './agent-task.mjs'

const actions = {
	MOVE_FILE: { label: '按类型整理', type: 'ORGANIZE_FILES' },
	RENAME_FILE: { label: '按日期重命名', type: 'RENAME_BY_DATE' },
	INDEX_MATERIAL: { label: '归档课程资料', type: 'IMPORT_LEARNING' },
}
const repeatableStates = new Set([
	'SUCCEEDED',
	'PARTIAL',
	'FAILED',
	'CANCELLED',
	'EXPIRED',
])

function hasControlCharacters(value) {
	return [...value].some((character) => {
		const code = character.charCodeAt(0)
		return code < 32 || code === 127
	})
}

function sourceDirectory(path) {
	if (
		typeof path !== 'string' ||
		!path.startsWith('/') ||
		path.endsWith('/') ||
		path.length > 500 ||
		/[\\?#]/.test(path) ||
		hasControlCharacters(path) ||
		/%(?:2e|2f|5c|00)/i.test(path)
	)
		return null
	const parts = path.slice(1).split('/')
	if (parts.some((part) => !part || part === '.' || part === '..')) return null
	return path.slice(0, path.lastIndexOf('/')) || '/'
}

/** Reuse only ordinary form inputs, never old targets, file tokens or execution data. */
export function deriveRepeatTask(task, now = Date.now()) {
	if (!task || !repeatableStates.has(taskStatus(task, now))) return null
	const steps = task.steps
	if (!Array.isArray(steps) || !steps.length || steps.length > 20) return null
	const first = steps[0]
	const action = actions[first?.action]
	const path = sourceDirectory(first?.sourcePath)
	if (
		!action ||
		path === null ||
		typeof first.storageKey !== 'string' ||
		!first.storageKey.trim() ||
		first.storageKey.length > 100 ||
		hasControlCharacters(first.storageKey)
	)
		return null
	const importLearning = action.type === 'IMPORT_LEARNING'
	const tags = first.tags ?? ''
	if (
		importLearning &&
		(!Number.isSafeInteger(first.courseId) ||
			first.courseId <= 0 ||
			typeof tags !== 'string' ||
			tags.length > 200)
	)
		return null
	if (
		steps.some(
			(step) =>
				step?.action !== first.action ||
				step.storageKey !== first.storageKey ||
				sourceDirectory(step.sourcePath) !== path ||
				(importLearning &&
					(step.courseId !== first.courseId || (step.tags ?? '') !== tags))
		)
	)
		return null
	return {
		type: action.type,
		storageKey: first.storageKey,
		path,
		courseId: importLearning ? first.courseId : null,
		tags: importLearning ? tags : '',
	}
}

export function taskCardSummary(task) {
	const steps = Array.isArray(task?.steps) ? task.steps : []
	const first = steps[0]
	const sameAction = steps.every((step) => step?.action === first?.action)
	const path = sourceDirectory(first?.sourcePath)
	const sameLocation =
		path !== null &&
		steps.every(
			(step) =>
				step?.storageKey === first.storageKey &&
				sourceDirectory(step.sourcePath) === path
		)
	return {
		label: sameAction
			? actions[first?.action]?.label || '文件任务'
			: '组合任务',
		count: steps.length,
		location: sameLocation ? `${first.storageKey} · ${path}` : '',
		target:
			first?.action === 'INDEX_MATERIAL' &&
			sameAction &&
			steps.every((step) => step.courseId === first.courseId)
				? `课程 #${first.courseId}`
				: first?.action === 'MOVE_FILE' && sameAction
				? '按文件类型分入子目录'
				: first?.action === 'RENAME_FILE' && sameAction
				? '保留目录，更新文件名'
				: '',
	}
}

export function taskStepLabel(action) {
	return (
		{
			MOVE_FILE: '移动文件',
			RENAME_FILE: '重命名文件',
			INDEX_MATERIAL: '建立资料索引',
		}[action] || '未知操作（请重新生成预览）'
	)
}

export function taskCardWarning(task, status) {
	if (status === 'INTERRUPTED')
		return '任务中断，未自动重试。请先核实文件与课程索引，避免重复操作。'
	if (['FAILED', 'PARTIAL'].includes(status)) {
		const failure = task.results?.find((result) => result.success === false)
		return `任务已停止，已完成操作不会自动回滚。${
			failure?.message || '请展开步骤查看结果。'
		}`
	}
	if (
		!['PENDING', 'RUNNING', 'SUCCEEDED', 'CANCELLED', 'EXPIRED'].includes(
			status
		)
	)
		return '任务状态暂无法识别，请刷新确认；不会自动执行或重试。'
	return ''
}

/** Refresh only newly completed moves, not historical cards or unresolved operations. */
export function completedFileChanges(previous, task) {
	if (
		previous?.id !== task?.id ||
		previous?.status !== 'RUNNING' ||
		!['SUCCEEDED', 'PARTIAL'].includes(task?.status)
	)
		return []
	const changes = new Map()
	for (const result of task.results || []) {
		if (result.success !== true || !Number.isSafeInteger(result.index)) continue
		const step = task.steps?.[result.index]
		if (!step || !['MOVE_FILE', 'RENAME_FILE'].includes(step.action)) continue
		if (typeof step.storageKey !== 'string' || !step.storageKey.trim()) continue
		for (const filePath of [step.sourcePath, step.targetPath]) {
			const path = sourceDirectory(filePath)
			if (path === null) continue
			const key = JSON.stringify([step.storageKey, path])
			changes.set(key, { storageKey: step.storageKey, path })
		}
	}
	return [...changes.values()]
}
