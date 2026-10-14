export const schedulePreviewStatus = (schedule) => {
	const states = {
		GENERATING: '正在生成预览',
		UNCERTAIN: '上次生成结果未确认，请先核实任务列表',
		WAITING_RETRY: '等待有限重试（仅生成预览）',
		FAILED: '生成失败，需要检查后手动重试',
		PREVIEW_CREATED: '已生成预览，请在任务列表查看当前状态',
		WAITING: '等待下次触发',
		PAUSED: '规则已暂停',
	}
	return (
		states[schedule?.previewState] ||
		(schedule?.lastError ? '上次生成失败' : '请查看任务列表确认生成结果')
	)
}
