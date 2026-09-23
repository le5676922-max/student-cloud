import assert from 'node:assert/strict'
import test from 'node:test'
import {
	clampPanelSize,
	restorePanelSize,
} from '../src/components/ai/ai-assistant-panel.mjs'

const desktopViewport = { width: 1440, height: 900, padding: 12 }

test('聊天框尺寸不会小于最小值或超出桌面可视区域', () => {
	assert.deepEqual(clampPanelSize(100, 100, desktopViewport), {
		width: 320,
		height: 360,
	})
	assert.deepEqual(clampPanelSize(2000, 2000, desktopViewport), {
		width: 1404,
		height: 800,
	})
})

test('恢复聊天框尺寸时会忽略损坏的本地数据', () => {
	assert.equal(restorePanelSize(null, desktopViewport), null)
	assert.equal(
		restorePanelSize({ width: 'wide', height: 400 }, desktopViewport),
		null
	)
	assert.deepEqual(
		restorePanelSize({ width: 420, height: 560 }, desktopViewport),
		{
			width: 420,
			height: 560,
		}
	)
})
