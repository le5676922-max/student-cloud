<template>
	<section class="operation-journal" aria-label="我的操作记录" :aria-busy="loading">
		<header>
			<div><h2>我的操作记录</h2><p>仅显示当前云盘中你本人的操作，时间为北京时间。</p></div>
			<button type="button" :disabled="loading || !driveKey || !identityKey" @click="load(false)">刷新记录</button>
		</header>
		<p v-if="error" role="alert">{{ error }}<span v-if="rows.length"> 当前显示上次读取的记录。</span></p>
		<p v-if="loading" role="status">正在读取操作记录…</p>
		<p v-else-if="!rows.length && !error">暂无操作记录。已接入收据的文件操作会显示在这里。</p>
		<ol v-if="rows.length">
			<li v-for="row in rows" :key="row.id">
				<div class="operation-heading"><strong>{{ typeText(row.type) }}</strong><span>{{ stateText(row.state) }}</span></div>
				<time :datetime="new Date(row.createdAt).toISOString()">{{ formatTime(row.createdAt) }}</time>
				<p class="operation-id">操作编号：{{ row.id }}</p>
				<p v-if="row.state === 'INDETERMINATE' || row.state === 'COMMITTING'">{{ row.state === 'INDETERMINATE' ? '写入结果待核查。' : '正在确认写入结果。' }}不要重复提交；先刷新记录，由支持此类型的存储核查确认实际结果。</p>
				<p v-else-if="row.state === 'FAILED'">已确认失败。请查看当前文件状态后再决定下一步。</p>
				<p v-else-if="row.state === 'CANCELLED'">已取消；此操作尚未开始写入。</p>
				<p v-else-if="row.state === 'RUNNING'">正在处理，不会自动重新执行。</p>
				<p v-else-if="row.state === 'PREPARED'">已记录请求，等待执行。</p>
				<p v-else-if="row.state === 'SUCCEEDED'">本次操作已完成。</p>
				<p v-if="row.resultRef" class="operation-id">结果引用：{{ row.resultRef }}</p>
			</li>
		</ol>
		<button v-if="cursor" type="button" :disabled="loading" @click="load(true)">加载更多记录</button>
	</section>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { listDriveOperationsReq } from '~/api/home/drive-operations'

const props = defineProps({ driveKey: { type: String, default: '' }, identityKey: { type: String, default: '' } })
const rows = ref([]), cursor = ref(null), loading = ref(false), error = ref('')
const requests = createLatestRequest()
const stateText = state => ({ PREPARED: '等待执行', RUNNING: '处理中', COMMITTING: '确认结果中', SUCCEEDED: '已完成', FAILED: '已失败', CANCELLED: '已取消', INDETERMINATE: '待核查' }[state] || '状态待确认')
const typeText = type => ({ UPLOAD: '上传文件', MKDIR: '新建目录', COPY: '复制文件', MOVE: '移动文件', RENAME: '重命名', DELETE: '移入回收站', RESTORE: '恢复文件', PURGE: '清理回收站' }[type] || '文件操作')
const formatTime = value => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium', hour12: false }).format(new Date(value))
async function load(append = false) {
	if (loading.value || !props.driveKey || !props.identityKey) return
	const request = requests.begin()
	loading.value = true;error.value = ''
	try {
		const response = await listDriveOperationsReq(props.driveKey, { cursor: append ? cursor.value : undefined, limit: 20 }, { signal: request.signal })
		if (!request.isCurrent()) return
		const page = response.data
		rows.value = append ? [...rows.value, ...page.items.filter(row => !rows.value.some(old => old.id === row.id))] : page.items
		cursor.value = page.nextCursor
	} catch (failure) {
		if (request.isCurrent()) {
			if ([401, 403].includes(failure?.response?.status || failure?.status)) { rows.value = [];cursor.value = null }
			error.value = '操作记录读取失败，请确认登录和云盘权限后刷新。'
		}
	} finally { if (request.isCurrent()) loading.value = false }
}
watch(() => [props.driveKey, props.identityKey], () => {
	requests.cancel();rows.value = [];cursor.value = null;loading.value = false;error.value = '';load(false)
}, { immediate: true })
onBeforeUnmount(() => { requests.cancel();rows.value = [] })
</script>
<style scoped>
.operation-journal { color: var(--cloud-ink); border: 1px solid var(--cloud-line); border-radius: var(--cloud-radius); padding: 16px; margin-block: 16px; }
header, .operation-heading { display: flex; align-items: start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
h2 { font-size: 18px; font-weight: 600; margin: 0; }
p { margin-block: 6px; line-height: 1.6; }
header p, time, .operation-id { font-size: 13px; color: var(--cloud-muted); }
ol { list-style: none; padding: 0; margin-block: 12px; }
li { border-top: 1px solid var(--cloud-line); padding-block: 12px; overflow-wrap: anywhere; }
.operation-id { font-variant-numeric: tabular-nums; }
button { min-height: 44px; border: 1px solid var(--cloud-line); border-radius: 8px; padding: 8px 12px; background: var(--cloud-surface); color: inherit; cursor: pointer; }
button:focus-visible { outline: 2px solid var(--cloud-accent); outline-offset: 3px; }
button:disabled { opacity: .6; cursor: not-allowed; }
[role='alert'] { border-left: 3px solid currentColor; padding-left: 10px; }
</style>
