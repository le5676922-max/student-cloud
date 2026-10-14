import test from 'node:test'
import assert from 'node:assert/strict'
import { schedulePreviewStatus } from '../src/utils/agent-schedule.mjs'

test('older schedules do not claim success simply because lastError is absent', () => {
	assert.match(schedulePreviewStatus({ lastRunAt: 1 }), /确认/)
})

test('retry state clearly says preview only and uncertainty requires verification', () => {
	assert.match(
		schedulePreviewStatus({ previewState: 'WAITING_RETRY' }),
		/仅生成预览/
	)
	assert.match(schedulePreviewStatus({ previewState: 'UNCERTAIN' }), /核实/)
	assert.match(
		schedulePreviewStatus({ previewState: 'PREVIEW_CREATED' }),
		/当前状态/
	)
})
