import assert from 'node:assert/strict'
import test from 'node:test'
import {
	createOverviewRequestGate,
	formatOverviewBytes,
	formatOverviewCount,
	formatOverviewDate,
	formatOverviewUptime,
	normalizeOverviewWorkspaceQuery,
	overviewAttention,
	overviewFailureMessage,
	overviewLoginTrend,
	overviewLoginLoggingNote,
	overviewNumber,
} from '../src/utils/admin-overview.mjs'

test('overview keeps missing or invalid counts unknown instead of healthy zero', () => {
	for (const value of [null, undefined, NaN, Infinity, -1, '0', false]) {
		assert.equal(overviewNumber(value), null)
		assert.equal(formatOverviewCount(value), '—')
	}
	assert.equal(formatOverviewCount(0), '0')
	assert.equal(formatOverviewCount(12000), '12,000')
})

test('memory formatting uses binary heap units and preserves unknown values', () => {
	assert.equal(formatOverviewBytes(0), '0 B')
	assert.equal(formatOverviewBytes(512), '512 B')
	assert.equal(formatOverviewBytes(1024), '1 KiB')
	assert.equal(formatOverviewBytes(1.5 * 1024 ** 3), '1.5 GiB')
	assert.equal(formatOverviewBytes(0.1), '0.1 B')
	assert.equal(formatOverviewBytes(null), '—')
	assert.equal(formatOverviewBytes(-1), '—')
})

test('uptime formatting handles fresh process, day boundaries, and missing data', () => {
	assert.equal(formatOverviewUptime(0), '不足 1 分钟')
	assert.equal(formatOverviewUptime(59999), '不足 1 分钟')
	assert.equal(formatOverviewUptime(60000), '1 分钟')
	assert.equal(formatOverviewUptime(61 * 60000), '1 小时 1 分钟')
	assert.equal(formatOverviewUptime(1501 * 60000), '1 天 1 小时 1 分钟')
	assert.equal(formatOverviewUptime(undefined), '—')
})

test('timestamps follow the server statistics timezone and safely handle invalid timezone', () => {
	const timestamp = Date.UTC(2026, 9, 1, 16, 5, 9)
	assert.match(
		formatOverviewDate(timestamp, 'Asia/Shanghai'),
		/2026\/10\/02 00:05:09/
	)
	assert.match(formatOverviewDate(timestamp, 'UTC'), /2026\/10\/01 16:05:09/)
	assert.equal(
		formatOverviewDate(timestamp, 'invalid-zone'),
		formatOverviewDate(timestamp, 'Asia/Shanghai')
	)
	assert.equal(formatOverviewDate('2026-10-02'), '—')
	assert.equal(formatOverviewDate(null), '—')
	assert.equal(formatOverviewDate(Number.MAX_VALUE), '—')
})

test('workspace query removes empty status and constrains pagination and keyword', () => {
	assert.deepEqual(normalizeOverviewWorkspaceQuery(), {
		keyword: '',
		page: 1,
		pageSize: 10,
	})
	assert.deepEqual(
		normalizeOverviewWorkspaceQuery({
			keyword: '  Alpha  ',
			page: '3',
			pageSize: '20',
			enabled: 'false',
		}),
		{ keyword: 'Alpha', page: 3, pageSize: 20, enabled: false }
	)
	assert.deepEqual(
		normalizeOverviewWorkspaceQuery({ enabled: true, page: 0, pageSize: -1 }),
		{ keyword: '', page: 1, pageSize: 10, enabled: true }
	)
	assert.equal(
		normalizeOverviewWorkspaceQuery({
			enabled: '',
			keyword: '中'.repeat(101),
			pageSize: 1000,
		}).keyword.length,
		100
	)
	assert.equal(
		normalizeOverviewWorkspaceQuery({ pageSize: 1000 }).pageSize,
		100
	)
	assert.equal(normalizeOverviewWorkspaceQuery({ page: Infinity }).page, 1)
	assert.equal(normalizeOverviewWorkspaceQuery({ page: 10001 }).page, 10000)
	assert.equal(
		normalizeOverviewWorkspaceQuery({ keyword: 'x'.repeat(99) + '😀' }).keyword,
		'x'.repeat(99)
	)
})

test('workspace deep links use a separate validated exact ID', () => {
	assert.deepEqual(normalizeOverviewWorkspaceQuery({ workspaceId: '1' }), {
		keyword: '',
		page: 1,
		pageSize: 10,
		workspaceId: 1,
	})
	assert.equal(
		normalizeOverviewWorkspaceQuery({ workspaceId: 2147483647 }).workspaceId,
		2147483647
	)
	for (const workspaceId of [
		undefined,
		null,
		'',
		0,
		-1,
		1.5,
		'oops',
		Infinity,
		2147483648,
	]) {
		assert.equal(
			Object.hasOwn(
				normalizeOverviewWorkspaceQuery({ workspaceId }),
				'workspaceId'
			),
			false
		)
	}
})

test('zero-login trend never divides by zero and includes empty days', () => {
	const rows = overviewLoginTrend(
		Array.from({ length: 7 }, (_, index) => ({
			date: `2026-10-0${index + 1}`,
			successes: 0,
			failures: 0,
		}))
	)
	assert.equal(rows.length, 7)
	for (const row of rows) {
		assert.equal(row.successHeight, 0)
		assert.equal(row.failureHeight, 0)
		assert.equal(row.successes, 0)
	}
})

test('disabled login logging describes historical records, not lack of visitors', () => {
	assert.match(overviewLoginLoggingNote(false), /日志未开启/)
	assert.match(overviewLoginLoggingNote(false), /历史已记录/)
	assert.match(overviewLoginLoggingNote(false), /不是实时访问量/)
	assert.match(overviewLoginLoggingNote(true), /不是在线人数或网站访问量/)
	assert.match(overviewLoginLoggingNote(undefined), /状态未获取/)
})

test('login trend scales both outcomes together and does not invent missing counts', () => {
	const rows = overviewLoginTrend([
		{ date: '2026-10-01', successes: 20, failures: 10 },
		{ date: 'not-a-date', successes: undefined, failures: -3 },
	])
	assert.equal(rows[0].successHeight, 100)
	assert.equal(rows[0].failureHeight, 50)
	assert.equal(rows[1].date, '—')
	assert.equal(rows[1].successes, null)
	assert.equal(rows[1].failures, null)
	assert.deepEqual(overviewLoginTrend(null), [])
	assert.equal(
		overviewLoginTrend(
			Array(8).fill({ date: '2026-10-01', successes: 1, failures: 2 })
		).length,
		7
	)
})

test('operational reminders report missing data and do not offer cross-owner Agent actions', () => {
	assert.deepEqual(overviewAttention(null), { complete: false, items: [] })
	const overview = {
		pendingAgentTasks: 0,
		statuses: {
			pendingMemberships: 0,
			disabledWorkspaces: 0,
			expiredShares: 0,
			failedAgentTasks: 0,
			failedSchedules: 0,
		},
	}
	assert.deepEqual(overviewAttention(overview), { complete: true, items: [] })
	overview.pendingAgentTasks = 2
	overview.statuses.pendingMemberships = 3
	overview.statuses.failedAgentTasks = 4
	const attention = overviewAttention(overview)
	assert.equal(attention.complete, true)
	assert.equal(attention.items.length, 3)
	assert.equal(
		attention.items.find((item) => item.key === 'pendingMemberships').to,
		'/admin/tenant-list'
	)
	assert.equal(
		attention.items.find((item) => item.key === 'pendingAgentTasks').to,
		undefined
	)
	assert.equal(
		attention.items.find((item) => item.key === 'failedAgentTasks').to,
		undefined
	)
})

test('API error messages support both Axios and application envelopes with bounded safe text', () => {
	assert.equal(
		overviewFailureMessage(
			{ response: { data: { msg: ' 数据库不可用 ' } } },
			'加载失败'
		),
		'数据库不可用'
	)
	assert.equal(
		overviewFailureMessage({ data: { msg: '权限不足' } }, '加载失败'),
		'权限不足'
	)
	assert.equal(
		overviewFailureMessage({ message: 'Network Error' }, '加载失败'),
		'加载失败'
	)
	assert.equal(
		overviewFailureMessage({ data: { msg: 'x'.repeat(1000) } }, '加载失败')
			.length,
		280
	)
	assert.equal(
		overviewFailureMessage({ data: { msg: {} } }, '加载失败'),
		'加载失败'
	)
})

test('newest request cancels prior request and stale responses cannot replace page data', async () => {
	const gate = createOverviewRequestGate()
	let resolveFirst
	const first = gate.begin()
	const delayed = new Promise((resolve) => {
		resolveFirst = resolve
	})
	let rendered = null
	const firstResponse = delayed.then((data) => {
		if (gate.isCurrent(first)) rendered = data
	})
	const second = gate.begin()
	assert.equal(first.signal.aborted, true)
	assert.equal(gate.isCurrent(first), false)
	assert.equal(gate.isCurrent(second), true)
	if (gate.isCurrent(second)) rendered = 'second page'
	resolveFirst('stale first page')
	await firstResponse
	assert.equal(rendered, 'second page')
	gate.finish(first)
	assert.equal(gate.isCurrent(second), true)
	gate.dispose()
	assert.equal(second.signal.aborted, true)
	assert.equal(gate.isCurrent(second), false)
	assert.equal(gate.begin(), null)
})

test('finishing a current request does not cancel its completed signal', () => {
	const gate = createOverviewRequestGate()
	const first = gate.begin()
	gate.finish(first)
	const second = gate.begin()
	assert.equal(first.signal.aborted, false)
	assert.equal(gate.isCurrent(first), false)
	assert.equal(gate.isCurrent(second), true)
	gate.dispose()
	gate.dispose()
})
