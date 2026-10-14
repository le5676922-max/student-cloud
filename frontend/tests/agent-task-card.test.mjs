import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import {
	deriveRepeatTask,
	taskCardSummary,
	taskCardWarning,
	taskStepLabel,
	completedFileChanges,
} from '../src/utils/agent-task-card.mjs'

const step = (overrides = {}) => ({
	action: 'MOVE_FILE',
	storageKey: 'workspace-2',
	sourcePath: '/课程资料/笔记.txt',
	targetPath: '/课程资料/文档/笔记.txt',
	courseId: null,
	tags: '',
	locationToken: 'old-token-must-not-be-reused',
	...overrides,
})
const task = (overrides = {}) => ({
	id: 'task-1',
	status: 'SUCCEEDED',
	expiresAt: 100,
	steps: [step()],
	results: [{ index: 0, success: true, message: '已完成' }],
	...overrides,
})

test('repeat uses ordinary form fields, never old execution data or ownership', () => {
	assert.deepEqual(deriveRepeatTask(task(), 101), {
		type: 'ORGANIZE_FILES',
		storageKey: 'workspace-2',
		path: '/课程资料',
		courseId: null,
		tags: '',
	})
	assert.deepEqual(
		deriveRepeatTask(
			task({ steps: [step({ action: 'RENAME_FILE', sourcePath: '/a.txt' })] })
		),
		{
			type: 'RENAME_BY_DATE',
			storageKey: 'workspace-2',
			path: '/',
			courseId: null,
			tags: '',
		}
	)
})

test('course repeat preserves only one authorized-context candidate course and tag string', () => {
	const first = step({
		action: 'INDEX_MATERIAL',
		courseId: 12,
		tags: '数据库,课堂',
	})
	assert.deepEqual(deriveRepeatTask(task({ steps: [first] })), {
		type: 'IMPORT_LEARNING',
		storageKey: 'workspace-2',
		path: '/课程资料',
		courseId: 12,
		tags: '数据库,课堂',
	})
	for (const mismatch of [{ courseId: 13 }, { tags: 'different' }]) {
		assert.equal(
			deriveRepeatTask(
				task({
					steps: [
						first,
						{ ...first, ...mismatch, sourcePath: '/课程资料/第二章.md' },
					],
				})
			),
			null
		)
	}
	for (const courseId of [null, 0, -1, '12', 1.2]) {
		assert.equal(
			deriveRepeatTask(task({ steps: [{ ...first, courseId }] })),
			null
		)
	}
	assert.equal(
		deriveRepeatTask(task({ steps: [{ ...first, tags: 'a'.repeat(201) }] })),
		null
	)
})

test('repeat refuses pending, running, interrupted and unknown states', () => {
	for (const status of [
		'PENDING',
		'RUNNING',
		'INTERRUPTED',
		'UNKNOWN',
		undefined,
	]) {
		assert.equal(deriveRepeatTask(task({ status, expiresAt: 500 }), 101), null)
	}
	for (const status of [
		'SUCCEEDED',
		'FAILED',
		'PARTIAL',
		'CANCELLED',
		'EXPIRED',
	]) {
		assert.ok(deriveRepeatTask(task({ status }), 101))
	}
	assert.ok(deriveRepeatTask(task({ status: 'PENDING', expiresAt: 100 }), 101))
})

test('repeat refuses mixed operations, sources, directories, or unrecognized steps', () => {
	for (const mismatch of [
		{ action: 'DELETE_FILE' },
		{ action: 'RENAME_FILE' },
		{ storageKey: 'other-owner-source' },
		{ sourcePath: '/other-space/file.txt' },
	]) {
		assert.equal(
			deriveRepeatTask(task({ steps: [step(), step(mismatch)] })),
			null
		)
	}
	assert.equal(deriveRepeatTask(task({ steps: [null] })), null)
	assert.equal(deriveRepeatTask(task({ steps: [step(), null] })), null)
	assert.equal(deriveRepeatTask(task({ steps: [] })), null)
	assert.equal(
		deriveRepeatTask(task({ steps: Array.from({ length: 21 }, () => step()) })),
		null
	)
})

test('repeat refuses ambiguous or non-normalized directories', () => {
	for (const sourcePath of [
		'file.txt',
		'/',
		'/folder/',
		'//folder/file.txt',
		'/folder/../file.txt',
		'/./file.txt',
		'/folder\\file.txt',
		'/folder/%2e%2e/file.txt',
		'/folder/%2ffile.txt',
		'/file.txt?path=other',
		'/file.txt#fragment',
		'/folder/\u0000file.txt',
		`/${'a'.repeat(501)}`,
	])
		assert.equal(
			deriveRepeatTask(task({ steps: [step({ sourcePath })] })),
			null,
			sourcePath
		)
	for (const storageKey of ['', ' ', '\u0000secret', 'x'.repeat(101), null]) {
		assert.equal(
			deriveRepeatTask(task({ steps: [step({ storageKey })] })),
			null
		)
	}
})

test('summary communicates file count and shared scope without hiding mixed work', () => {
	assert.deepEqual(taskCardSummary(task()), {
		label: '按类型整理',
		count: 1,
		location: 'workspace-2 · /课程资料',
		target: '按文件类型分入子目录',
	})
	const mixed = taskCardSummary(
		task({
			steps: [
				step(),
				step({ action: 'INDEX_MATERIAL', sourcePath: '/其他目录/a.md' }),
			],
		})
	)
	assert.equal(mixed.label, '组合任务')
	assert.equal(mixed.location, '')
	assert.equal(mixed.target, '')
	assert.equal(taskStepLabel('DELETE_FILE'), '未知操作（请重新生成预览）')
	assert.equal(taskCardSummary(task({ steps: [] })).count, 0)
})

test('failed and interrupted work always has an explicit warning outside its details', () => {
	assert.match(
		taskCardWarning(
			task({
				results: [{ index: 0, success: false, message: '目标同名冲突' }],
			}),
			'PARTIAL'
		),
		/目标同名冲突/
	)
	assert.match(taskCardWarning(task(), 'FAILED'), /不会自动回滚/)
	assert.match(taskCardWarning(task(), 'INTERRUPTED'), /未自动重试.*核实/)
	assert.match(taskCardWarning(task(), 'UNKNOWN'), /不会自动执行或重试/)
	assert.equal(taskCardWarning(task(), 'SUCCEEDED'), '')
})

test('completion refresh emits both affected directories and deduplicates repeated paths', () => {
	const previous = task({ status: 'RUNNING' })
	assert.deepEqual(completedFileChanges(previous, task()), [
		{ storageKey: 'workspace-2', path: '/课程资料' },
		{ storageKey: 'workspace-2', path: '/课程资料/文档' },
	])
	assert.deepEqual(
		completedFileChanges(
			previous,
			task({
				steps: [
					step({ action: 'RENAME_FILE', targetPath: '/课程资料/20261009.txt' }),
					step({
						action: 'RENAME_FILE',
						sourcePath: '/课程资料/第二章.md',
						targetPath: '/课程资料/20261009.md',
					}),
				],
				results: [
					{ index: 0, success: true },
					{ index: 1, success: true },
				],
			})
		),
		[{ storageKey: 'workspace-2', path: '/课程资料' }]
	)
})

test('history, uncertain progress and failed operations cannot trigger automatic file refresh', () => {
	for (const previous of [
		undefined,
		task(),
		task({ status: 'PENDING' }),
		task({ id: 'different-task', status: 'RUNNING' }),
	]) {
		assert.deepEqual(completedFileChanges(previous, task()), [])
	}
	for (const status of ['RUNNING', 'FAILED', 'INTERRUPTED', 'UNKNOWN']) {
		assert.deepEqual(
			completedFileChanges(task({ status: 'RUNNING' }), task({ status })),
			[]
		)
	}
	for (const results of [
		[{ index: 0, success: false }],
		[{ index: 12, success: true }],
		[{ index: '0', success: true }],
	]) {
		assert.deepEqual(
			completedFileChanges(
				task({ status: 'RUNNING' }),
				task({ status: 'PARTIAL', results })
			),
			[]
		)
	}
	assert.deepEqual(
		completedFileChanges(
			task({ status: 'RUNNING' }),
			task({ steps: [step({ action: 'INDEX_MATERIAL' })] })
		),
		[]
	)
})

test('card keeps explicit confirmation, repeat opt-in, cancellation and visible failure contracts', async () => {
	const source = await readFile(
		new URL('../src/components/ai/AgentTaskCard.vue', import.meta.url),
		'utf8'
	)
	assert.match(source, /allowRepeat: \{ type: Boolean, default: false \}/)
	assert.match(source, /emit\('repeat', context\)/)
	assert.match(source, /ElMessageBox\.confirm\(/)
	assert.match(source, /action === 'confirm' \? \{ confirmed: true \}/)
	assert.match(source, /status === 'PENDING'" class="task-expiry"/)
	assert.ok(
		source.indexOf('v-if="warning"') < source.indexOf('class="task-details"')
	)
	assert.match(source, /actionController\?\.abort\(\)/)
	assert.match(
		source,
		/if \(disposed \|\| controller\.signal\.aborted \|\| task\.value\.id !== id\) return/g
	)
	assert.match(source, /if \(pollingKey === key\) return/)
})
