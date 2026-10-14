export function taskStatus(task, now = Date.now()) {
	return task.status === 'PENDING' && task.expiresAt <= now
		? 'EXPIRED'
		: task.status
}

export function taskStatusLabel(status) {
	return (
		{
			PENDING: '待确认',
			RUNNING: '排队或执行中',
			INTERRUPTED: '执行中断，请核实文件状态',
			SUCCEEDED: '已完成',
			PARTIAL: '部分完成，已停止',
			FAILED: '执行失败',
			CANCELLED: '已取消',
			EXPIRED: '已过期',
		}[status] || '未知状态'
	)
}

/** One outstanding read per task; terminal tasks stop, disposal also aborts an in-flight read. */
export function createTaskPoller({
	load,
	onUpdate,
	onError,
	delay = 1500,
	schedule = setTimeout,
	unschedule = clearTimeout,
}) {
	let timer,
		controller,
		generation = 0
	function stop() {
		generation++
		unschedule(timer)
		controller?.abort()
		controller = undefined
	}
	function start(task) {
		stop()
		if (task.status !== 'RUNNING') return
		const current = generation
		let failures = 0
		async function poll() {
			controller = new AbortController()
			try {
				const value = await load(task.id, controller.signal)
				if (current !== generation) return
				failures = 0
				onUpdate(value)
				if (value.status === 'RUNNING' && current === generation)
					timer = schedule(poll, delay)
			} catch (error) {
				if (current !== generation) return
				if (++failures < 3) timer = schedule(poll, delay * failures * 2)
				else onError(error)
			}
		}
		timer = schedule(poll, delay)
	}
	return { start, stop }
}

export function taskProgress(task) {
	const results = task.results || []
	return {
		succeeded: results.filter((result) => result.success).length,
		failed: results.filter((result) => !result.success).length,
		remaining: Math.max(0, task.steps.length - results.length),
	}
}
