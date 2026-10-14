const countFormatter = new Intl.NumberFormat('zh-CN')

export function overviewNumber(value) {
	return typeof value === 'number' && Number.isFinite(value) && value >= 0
		? value
		: null
}

export function formatOverviewCount(value) {
	const number = overviewNumber(value)
	return number === null ? '—' : countFormatter.format(number)
}

export function formatOverviewBytes(value) {
	const number = overviewNumber(value)
	if (number === null) return '—'
	if (number === 0) return '0 B'
	const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
	const unit = Math.max(
		0,
		Math.min(Math.floor(Math.log(number) / Math.log(1024)), 4)
	)
	return `${countFormatter.format(
		Number((number / 1024 ** unit).toFixed(1))
	)} ${units[unit]}`
}

export function formatOverviewUptime(value) {
	const milliseconds = overviewNumber(value)
	if (milliseconds === null) return '—'
	const minutes = Math.floor(milliseconds / 60000)
	if (minutes < 1) return '不足 1 分钟'
	const days = Math.floor(minutes / 1440)
	const hours = Math.floor((minutes % 1440) / 60)
	const remaining = minutes % 60
	return [days && `${days} 天`, hours && `${hours} 小时`, `${remaining} 分钟`]
		.filter(Boolean)
		.join(' ')
}

export function formatOverviewDate(value, timeZone = 'Asia/Shanghai') {
	if (overviewNumber(value) === null) return '—'
	const date = new Date(value)
	if (!Number.isFinite(date.getTime())) return '—'
	const options = {
		year: 'numeric',
		month: '2-digit',
		day: '2-digit',
		hour: '2-digit',
		minute: '2-digit',
		second: '2-digit',
		hour12: false,
	}
	try {
		return new Intl.DateTimeFormat('zh-CN', { ...options, timeZone }).format(
			date
		)
	} catch {
		return new Intl.DateTimeFormat('zh-CN', {
			...options,
			timeZone: 'Asia/Shanghai',
		}).format(date)
	}
}

export function normalizeOverviewWorkspaceQuery(params = {}) {
	const page = Number(params.page)
	const pageSize = Number(params.pageSize)
	const result = {
		keyword: String(params.keyword ?? '')
			.trim()
			.slice(0, 100)
			.replace(/[\uD800-\uDBFF]$/, ''),
		page: Number.isInteger(page) && page > 0 ? Math.min(page, 10000) : 1,
		pageSize:
			Number.isInteger(pageSize) && pageSize > 0 ? Math.min(pageSize, 100) : 10,
	}
	if (params.enabled === true || params.enabled === 'true')
		result.enabled = true
	if (params.enabled === false || params.enabled === 'false')
		result.enabled = false
	const workspaceId = Number(params.workspaceId)
	if (
		Number.isInteger(workspaceId) &&
		workspaceId > 0 &&
		workspaceId <= 2147483647
	)
		result.workspaceId = workspaceId
	return result
}

export function overviewLoginTrend(entries) {
	const rows = Array.isArray(entries)
		? entries.slice(0, 7).map((entry) => ({
				date: /^\d{4}-\d{2}-\d{2}$/.test(entry?.date) ? entry.date : '—',
				successes: overviewNumber(entry?.successes),
				failures: overviewNumber(entry?.failures),
		  }))
		: []
	const maximum = Math.max(
		1,
		...rows.flatMap((row) => [row.successes ?? 0, row.failures ?? 0])
	)
	return rows.map((row) => ({
		...row,
		successHeight: row.successes === null ? 0 : (row.successes / maximum) * 100,
		failureHeight: row.failures === null ? 0 : (row.failures / maximum) * 100,
	}))
}

export function overviewAttention(overview) {
	const definitions = [
		{
			key: 'pendingMemberships',
			label: '等待加入审核',
			note: '由对应空间管理员审核，不在全局控制台代为处理。',
			to: '/admin/tenant-list',
			linkText: '查看工作空间',
		},
		{
			key: 'disabledWorkspaces',
			label: '已停用空间',
			note: '停用可能是管理决定，可检查空间状态。',
			to: '/admin/tenant-list',
			linkText: '管理工作空间',
		},
		{
			key: 'expiredShares',
			label: '已过期分享',
			note: '检查分享记录及其有效期。',
			to: '/admin/share-link',
			linkText: '查看分享记录',
		},
		{
			key: 'failedAgentTasks',
			label: '异常 Agent 任务',
			note: '包含失败、部分完成和中断的任务；详情及重试由任务所有者处理。',
		},
		{
			key: 'failedSchedules',
			label: '异常自动化计划',
			note: '仅作全局汇总，计划检查和重试由计划所有者处理。',
		},
		{
			key: 'pendingAgentTasks',
			label: '待确认 Agent 任务',
			note: '操作必须由任务所有者确认，全局管理员不能代为执行。',
		},
	]
	const values = definitions.map((item) => ({
		...item,
		count: overviewNumber(
			item.key === 'pendingAgentTasks'
				? overview?.pendingAgentTasks
				: overview?.statuses?.[item.key]
		),
	}))
	return {
		complete: values.every((item) => item.count !== null),
		items: values.filter((item) => item.count !== null && item.count > 0),
	}
}

export function overviewFailureMessage(failure, fallback) {
	const message = failure?.response?.data?.msg || failure?.data?.msg
	return typeof message === 'string' && message.trim()
		? message.trim().slice(0, 280)
		: fallback
}

export function overviewLoginLoggingNote(enabled) {
	if (enabled === false)
		return '登录日志未开启，下方仅反映历史已记录的尝试，不是实时访问量。'
	if (enabled === true)
		return '仅基于已记录的登录尝试；不是在线人数或网站访问量。'
	return '登录日志开关状态未获取，统计仅反映已记录的尝试。'
}

// Requests are cancellable, and only the newest response may replace the view.
export function createOverviewRequestGate() {
	let sequence = 0
	let controller = null
	let disposed = false
	return {
		begin() {
			if (disposed) return null
			controller?.abort()
			controller = new AbortController()
			sequence += 1
			return { id: sequence, signal: controller.signal }
		},
		isCurrent(request) {
			return !disposed && Boolean(request) && request.id === sequence
		},
		finish(request) {
			if (!disposed && request?.id === sequence) controller = null
		},
		dispose() {
			disposed = true
			sequence += 1
			controller?.abort()
			controller = null
		},
	}
}
