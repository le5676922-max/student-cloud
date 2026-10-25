const count = (value) =>
	Number.isSafeInteger(value) && value >= 0 ? value : null

function distribution(id, title, total, first, labels, note) {
	total = count(total)
	first = count(first)
	const valid = total !== null && first !== null && first <= total
	return {
		id,
		title,
		total: valid ? total : null,
		note,
		items: labels.map((label, index) => ({
			label,
			value: valid ? (index === 0 ? first : total - first) : null,
			color: index === 0 ? '#7551df' : '#efb866',
		})),
	}
}

export function overviewDistributions(value) {
	return [
		distribution(
			'roles',
			'空间成员构成',
			value?.members,
			value?.workspaceAdmins,
			['空间管理员', '普通成员'],
			'不含全局管理员和系统内置账号'
		),
		distribution(
			'storages',
			'云盘运行状态',
			value?.storageSources,
			value?.statuses?.enabledStorages,
			['已启用', '已停用'],
			'启用状态不代表远端连接一定正常'
		),
		distribution(
			'workspaces',
			'工作空间状态',
			value?.workspaces,
			value?.statuses?.enabledWorkspaces,
			['已启用', '已停用'],
			'统计覆盖全部工作空间'
		),
	]
}

export function ringSegments(items) {
	if (!Array.isArray(items) || items.some((item) => count(item.value) === null))
		return []
	const total = items.reduce((sum, item) => sum + item.value, 0)
	if (!Number.isSafeInteger(total) || total === 0) return []
	let consumed = 0
	return items.map((item) => {
		const percent = item.value / total
		const segment = {
			...item,
			dash: `${percent * 100} ${100 - percent * 100}`,
			offset: -consumed * 100,
			percent: Math.round(percent * 1000) / 10,
		}
		consumed += percent
		return segment
	})
}

export function overviewTrendSeries(value) {
	if (!Array.isArray(value) || !value.length) return null
	const days = value.slice(-7)
	if (
		days.some(
			(day) =>
				!/^\d{4}-\d{2}-\d{2}$/.test(day?.date ?? '') ||
				count(day.successes) === null ||
				count(day.failures) === null
		)
	)
		return null
	const max = Math.max(
		1,
		...days.flatMap((day) => [day.successes, day.failures])
	)
	const coordinate = (value, index) => ({
		x: 22 + index * (396 / Math.max(1, days.length - 1)),
		y: 132 - (value / max) * 104,
	})
	return {
		max,
		days,
		series: ['successes', 'failures'].map((key) => ({
			key,
			label: key === 'successes' ? '成功' : '失败',
			color: key === 'successes' ? '#7551df' : '#de9259',
			points: days.map((day, index) => ({
				...coordinate(day[key], index),
				value: day[key],
				date: day.date,
			})),
			path: days
				.map((day, index) => {
					const point = coordinate(day[key], index)
					return `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`
				})
				.join(' '),
		})),
	}
}
