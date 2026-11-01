import test from 'node:test'
import assert from 'node:assert/strict'
import { quotaPresentation } from '../src/utils/quota-presentation.mjs'

test('unknown usage never pretends to be zero or an empty progress bar', () => {
  const view = quotaPresentation({limitBytes: 200, usedBytes: null, reservedBytes: 0, usageState:'UNKNOWN'})
  assert.equal(view.percent, null)
  assert.match(view.detail, /尚未校准/)
  assert.equal(view.canReconcile, true)
})
test('a zero-byte unresolved operation also disables calibration for unscanned usage', () => {
  assert.equal(quotaPresentation({usageState:'UNKNOWN',usedBytes:null,reservedBytes:0,pendingWrites:1}).canReconcile,false)
})
test('held bytes count toward available capacity without double counting used bytes', () => {
  const view = quotaPresentation({limitBytes:200,usedBytes:50,reservedBytes:100,availableBytes:50,usageState:'ESTIMATED'})
  assert.equal(view.percent,75)
  assert.match(view.detail,/预留/)
  assert.match(view.detail,/估算/)
  assert.equal(view.canReconcile,false)
})
test('over-limit and unlimited are different from unknown or zero capacity', () => {
  assert.match(quotaPresentation({limitBytes:0,usedBytes:40,reservedBytes:0,usageState:'EXACT'}).detail,/已超过上限/)
  assert.match(quotaPresentation({limitBytes:null,usedBytes:40,reservedBytes:0,usageState:'EXACT'}).detail,/未设置上限/)
  assert.equal(quotaPresentation({limitBytes:0,usedBytes:0,reservedBytes:0,usageState:'EXACT'}).percent,100)
})
test('unsafe JSON integer precision is not silently shown as exact capacity', () => {
  const view = quotaPresentation({limitBytes:Infinity,usedBytes:2**60,reservedBytes:-1,usageState:'EXACT'})
  assert.equal(view.percent,null)
  assert.match(view.detail,/无法安全显示/)
})
