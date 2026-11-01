const bytes = (value) => {
  if (value < 1024) return `${value} B`
  const unit = Math.min(4, Math.floor(Math.log(value) / Math.log(1024)))
  return `${(value / 1024 ** unit).toFixed(1)} ${['B','KiB','MiB','GiB','TiB'][unit]}`
}
const safe = (n) => Number.isSafeInteger(n) && n >= 0
/** 容量未知与确实为零分开；预留也占用可写额度。 */
export function quotaPresentation(view) {
  if (!view || view.usageState === 'UNKNOWN') return {percent:null,detail:'容量尚未校准，不会按 0 计算。',canReconcile:!(view?.pendingWrites > 0)}
  if (!safe(view.usedBytes) || !safe(view.reservedBytes) || (view.limitBytes !== null && !safe(view.limitBytes)) || !safe(view.usedBytes + view.reservedBytes))
    return {percent:null,detail:'容量数据无法安全显示，请重新读取。',canReconcile:false}
  const occupied = view.usedBytes + view.reservedBytes
  const percent = view.limitBytes === null ? null : view.limitBytes === 0 ? 100 : Math.min(100, occupied / view.limitBytes * 100)
  const limit = view.limitBytes === null ? '未设置上限' : `上限 ${bytes(view.limitBytes)}`
  const warning = view.limitBytes !== null && occupied > view.limitBytes ? ' · 已超过上限，新增写入受限' : ''
  return { percent, detail:`已用 ${bytes(view.usedBytes)} · 预留 ${bytes(view.reservedBytes)} · ${limit}${view.usageState === 'ESTIMATED' ? ' · 估算值，可校准' : ''}${warning}`,canReconcile:view.reservedBytes === 0 && !(view.pendingWrites > 0) }
}
