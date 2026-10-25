<template>
	<section class="business-charts" aria-labelledby="business-charts-title">
		<header class="business-heading">
			<div>
				<h2 id="business-charts-title">业务运行概况</h2>
				<p>课程用量、待办与知识整理，最多缓存 30 秒。</p>
			</div>
			<div class="chart-actions">
				<button :aria-pressed="tableMode" @click="tableMode = !tableMode">
					{{ tableMode ? '查看图表' : '数据明细' }}</button
				><button :disabled="loading" @click="load">
					{{ loading ? '加载中…' : '刷新' }}
				</button>
			</div>
		</header>
		<form class="metrics-filter" @submit.prevent="applyFilter">
			<label
				>工作空间<select
					v-model="tenantDraft"
					:disabled="loading"
					aria-label="选择统计工作空间"
				>
					<option value="">全部空间</option>
					<option
						v-for="item in choices"
						:key="item.id"
						:value="String(item.id)"
					>
						{{ item.name }}
					</option>
				</select></label
			>
			<details>
				<summary>按空间 ID 筛选</summary>
				<label
					>空间 ID<input
						v-model="tenantDraft"
						inputmode="numeric"
						pattern="[0-9]*"
						maxlength="10"
						:disabled="loading"
						placeholder="留空查看全部"
				/></label>
			</details>
			<button type="submit" :disabled="loading || !validTenant">应用</button
			><span v-if="metrics" class="scope-note">{{
				metrics.tenantId ? `空间 #${metrics.tenantId}` : '全站数据'
			}}</span>
		</form>
		<p v-if="error" class="error" role="alert">{{ error }}</p>
		<p v-if="loading && !metrics" class="chart-empty" role="status">
			正在读取业务统计…
		</p>
		<div v-if="metrics" class="business-grid">
			<article class="business-card">
				<h3>云盘类型</h3>
				<p class="chart-note">已保存的连接配置数量，不代表测试连通。</p>
				<table v-if="tableMode">
					<caption class="visually-hidden">
						云盘类型明细
					</caption>
					<thead>
						<tr>
							<th>类型</th>
							<th>数量</th>
						</tr>
					</thead>
					<tbody>
						<tr v-for="item in metrics.storageTypes" :key="item.key">
							<td>{{ typeLabel(item.key) }}</td>
							<td>{{ count(item.count) }}</td>
						</tr>
					</tbody>
				</table>
				<template v-else
					><div v-if="sourceTotal" class="source-ring">
						<svg viewBox="0 0 120 120" role="img" aria-label="云盘类型数量占比">
							<circle
								cx="60"
								cy="60"
								r="43"
								fill="none"
								stroke="#efedf2"
								stroke-width="12"
							/>
							<circle
								v-for="(item, index) in sourceSegments"
								:key="item.key"
								cx="60"
								cy="60"
								r="43"
								fill="none"
								:stroke="colors[index % colors.length]"
								stroke-width="12"
								pathLength="100"
								:stroke-dasharray="`${item.percent} ${100 - item.percent}`"
								:stroke-dashoffset="item.offset"
								transform="rotate(-90 60 60)"
							>
								<title>{{ typeLabel(item.key) }}：{{ item.count }} 个</title>
							</circle>
							<text x="60" y="59" text-anchor="middle" class="ring-total">
								{{ sourceTotal }}
							</text>
							<text x="60" y="76" text-anchor="middle" class="ring-label">
								云盘连接
							</text>
						</svg>
						<ul class="ring-legend">
							<li v-for="(item, index) in metrics.storageTypes" :key="item.key">
								<span
									:style="{ background: colors[index % colors.length] }"
								/>{{ typeLabel(item.key)
								}}<strong>{{ count(item.count) }}</strong>
							</li>
						</ul>
					</div>
					<p v-else class="chart-empty">尚未添加云盘连接</p></template
				>
			</article>
			<article class="business-card">
				<h3>待处理申请</h3>
				<p class="chart-note">
					本地云盘由全局管理员审批，成员及课程申请由对应创建者处理。
				</p>
				<table v-if="tableMode">
					<caption class="visually-hidden">
						待处理申请明细
					</caption>
					<thead>
						<tr>
							<th>申请类型</th>
							<th>数量</th>
						</tr>
					</thead>
					<tbody>
						<tr v-for="item in metrics.pendingApprovals" :key="item.key">
							<td>{{ approvalLabel(item.key) }}</td>
							<td>{{ count(item.count) }}</td>
						</tr>
					</tbody>
				</table>
				<ul v-else class="bar-list">
					<li v-for="(item, index) in metrics.pendingApprovals" :key="item.key">
						<div>
							<span>{{ approvalLabel(item.key) }}</span
							><strong>{{ count(item.count) }}</strong>
						</div>
						<svg
							viewBox="0 0 300 10"
							role="img"
							:aria-label="`${approvalLabel(item.key)} ${item.count} 项`"
						>
							<rect x="0" y="0" width="300" height="10" rx="5" fill="#f0edf4" />
							<rect
								x="0"
								y="0"
								:width="barWidth(item.count, pendingMax)"
								height="10"
								rx="5"
								:fill="colors[index % colors.length]"
							/>
						</svg>
					</li>
				</ul>
				<p v-if="!pendingTotal" class="chart-note">当前没有待处理申请。</p>
			</article>
			<article class="business-card">
				<h3>知识库改进</h3>
				<p class="chart-note">
					只统计用户主动反馈和审核发布内容，不展示问答或个人信息。
				</p>
				<table v-if="tableMode">
					<caption class="visually-hidden">
						知识改进明细
					</caption>
					<thead>
						<tr>
							<th>反馈状态</th>
							<th>数量</th>
						</tr>
					</thead>
					<tbody>
						<tr v-for="item in metrics.feedbackStatuses" :key="item.key">
							<td>{{ feedbackLabel(item.key) }}</td>
							<td>{{ count(item.count) }}</td>
						</tr>
					</tbody>
				</table>
				<ul v-else class="feedback-counts">
					<li v-for="item in feedbackRows" :key="item.key">
						<span>{{ feedbackLabel(item.key) }}</span
						><strong>{{ count(item.count) }}</strong>
					</li>
				</ul>
				<div class="knowledge-total">
					<strong>{{ knowledgeTotal }}</strong
					><span>条已发布知识</span
					><router-link to="/admin/ai-feedback">整理反馈 →</router-link>
				</div>
				<ul class="knowledge-scopes">
					<li v-for="item in metrics.knowledgeScopes" :key="item.key">
						{{ scopeLabel(item.key) }} {{ count(item.count) }}
					</li>
				</ul>
			</article>
			<article class="business-card">
				<h3>课程协作</h3>
				<p class="chart-note">
					课程资料与个人云盘分别计量，已用包含未完成上传的容量预留。
				</p>
				<dl class="course-numbers">
					<div>
						<dt>共享课程</dt>
						<dd>
							{{ count(metrics.courses.total) }}
							<small>{{ count(metrics.courses.active) }} 门启用</small>
						</dd>
					</div>
					<div>
						<dt>加入人次</dt>
						<dd>{{ count(metrics.courses.members) }}</dd>
					</div>
					<div>
						<dt>可下载资料</dt>
						<dd>{{ count(metrics.courses.materials) }}</dd>
					</div>
					<div>
						<dt>课程已用容量</dt>
						<dd>{{ bytes(metrics.courses.usedBytes) }}</dd>
					</div>
				</dl>
				<p class="chart-note">
					课程容量上限合计
					{{ bytes(metrics.courses.quotaBytes) }}，不代表实际占用或预留磁盘。
				</p>
				<p class="unknown-usage">个人云盘总占用：暂未统一计量</p>
			</article>
			<article class="business-card usage-card">
				<h3>各空间课程用量</h3>
				<p class="chart-note">
					按已用容量降序，最多 10 个空间。进度表示已用／各课程容量上限合计。
				</p>
				<table v-if="tableMode">
					<caption class="visually-hidden">
						工作空间课程容量明细
					</caption>
					<thead>
						<tr>
							<th>工作空间</th>
							<th>课程数</th>
							<th>已用</th>
							<th>容量上限合计</th>
						</tr>
					</thead>
					<tbody>
						<tr
							v-for="item in metrics.courseUsageByWorkspace"
							:key="item.tenantId"
						>
							<td>{{ item.name }}</td>
							<td>{{ count(item.courses) }}</td>
							<td>{{ bytes(item.usedBytes) }}</td>
							<td>{{ bytes(item.quotaBytes) }}</td>
						</tr>
					</tbody>
				</table>
				<ul v-else class="usage-list">
					<li
						v-for="item in metrics.courseUsageByWorkspace"
						:key="item.tenantId"
					>
						<button @click="filterWorkspace(item.tenantId)">
							{{ item.name }}
						</button>
						<div>
							<svg
								viewBox="0 0 500 12"
								role="img"
								:aria-label="`${item.name}，已用 ${bytes(
									item.usedBytes
								)}，上限合计 ${bytes(item.quotaBytes)}`"
							>
								<rect
									x="0"
									y="0"
									width="500"
									height="12"
									rx="6"
									fill="#efedf2"
								/>
								<rect
									x="0"
									y="0"
									:width="barWidth(item.usedBytes, item.quotaBytes, 500)"
									height="12"
									rx="6"
									fill="#8b68d9"
								/></svg
							><span
								>{{ bytes(item.usedBytes) }} /
								{{ bytes(item.quotaBytes) }}</span
							>
						</div>
						<small>{{ item.courses }} 门课程</small>
					</li>
				</ul>
				<p v-if="!metrics.courseUsageByWorkspace.length" class="chart-empty">
					暂无课程容量数据
				</p>
			</article>
		</div>
	</section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import useStorageConfigStore from '~/stores/storage-config'
import { getBusinessMetricsReq } from '~/api/admin/business-metrics'
const props = defineProps({ workspaces: { type: Array, default: () => [] } })
const store = useStorageConfigStore()
const metrics = ref(null),
	loading = ref(false),
	error = ref(''),
	tableMode = ref(false),
	tenantDraft = ref(''),
	selectedTenant = ref(null)
const colors = [
	'#8964dc',
	'#ff9a62',
	'#e1d353',
	'#45414b',
	'#b4a0d3',
	'#d4c2af',
]
let controller,
	version = 0
const validTenant = computed(
	() =>
		tenantDraft.value === '' ||
		(/^[1-9][0-9]{0,9}$/.test(tenantDraft.value) &&
			Number(tenantDraft.value) <= 2147483647)
)
const choices = computed(() => {
	const values = new Map()
	for (const item of props.workspaces)
		if (Number.isInteger(item.id))
			values.set(item.id, { id: item.id, name: item.name })
	for (const item of metrics.value?.courseUsageByWorkspace || [])
		values.set(item.tenantId, { id: item.tenantId, name: item.name })
	return [...values.values()].slice(0, 20)
})
const sourceTotal = computed(() =>
	(metrics.value?.storageTypes || []).reduce(
		(sum, item) => sum + (validCount(item.count) ? item.count : 0),
		0
	)
)
const sourceSegments = computed(() => {
	let offset = 0
	return (metrics.value?.storageTypes || []).map((item) => {
		const percent = sourceTotal.value
			? (item.count / sourceTotal.value) * 100
			: 0
		const result = { ...item, percent, offset: -offset }
		offset += percent
		return result
	})
})
const pendingMax = computed(() =>
	Math.max(
		1,
		...(metrics.value?.pendingApprovals || []).map((item) => item.count)
	)
)
const pendingTotal = computed(() =>
	(metrics.value?.pendingApprovals || []).reduce(
		(sum, item) => sum + item.count,
		0
	)
)
const knowledgeTotal = computed(() =>
	(metrics.value?.knowledgeScopes || []).reduce(
		(sum, item) => sum + item.count,
		0
	)
)
const feedbackRows = computed(() =>
	['OPEN', 'RESOLVED', 'DISMISSED'].map((key) => ({
		key,
		count:
			metrics.value?.feedbackStatuses.find((item) => item.key === key)?.count ||
			0,
	}))
)
function validCount(value) {
	return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}
function count(value) {
	return validCount(value) ? value.toLocaleString('zh-CN') : '—'
}
function bytes(value) {
	if (!validCount(value)) return '—'
	if (value >= 1073741824) return `${(value / 1073741824).toFixed(2)} GB`
	if (value >= 1048576) return `${(value / 1048576).toFixed(1)} MB`
	if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`
	return `${value} B`
}
function barWidth(value, total, width = 300) {
	return validCount(value) &&
		typeof total === 'number' &&
		Number.isFinite(total) &&
		total > 0
		? Math.max(0, Math.min(width, (value / total) * width))
		: 0
}
function typeLabel(key) {
	key = String(key || '').toUpperCase()
	return (
		{
			LOCAL: '本地云盘',
			S3: 'S3',
			ALIYUN: '阿里云 OSS',
			MINIO: 'MinIO',
			WEBDAV: 'WebDAV',
			SFTP: 'SFTP',
			FTP: 'FTP',
			TENCENT: '腾讯云 COS',
			HUAWEI: '华为云 OBS',
			QINIU: '七牛云',
			ONEDRIVE: 'OneDrive',
		}[key] ||
		key ||
		'未知类型'
	)
}
function approvalLabel(key) {
	return (
		{
			LOCAL_STORAGE: '本地云盘申请',
			MEMBER_CLOUD: '成员云盘使用申请',
			COURSE_UPLOAD: '课程上传权限申请',
		}[key] || key
	)
}
function feedbackLabel(key) {
	return { OPEN: '待整理', RESOLVED: '已整理', DISMISSED: '已忽略' }[key] || key
}
function scopeLabel(key) {
	return { GLOBAL: '全站通用', TENANT: '工作空间', COURSE: '课程' }[key] || key
}
async function load() {
	controller?.abort()
	controller = new AbortController()
	const current = ++version,
		signal = controller.signal
	if (store.loginInfo.role !== 'ADMIN' || store.loginInfo.isLogin !== true) {
		metrics.value = null
		loading.value = false
		return
	}
	loading.value = true
	error.value = ''
	try {
		const response = await getBusinessMetricsReq(selectedTenant.value, {
			signal,
		})
		const data = response.data
		if (
			!data ||
			![
				'storageTypes',
				'pendingApprovals',
				'feedbackStatuses',
				'knowledgeScopes',
			].every(
				(key) =>
					Array.isArray(data[key]) &&
					data[key].every(
						(item) => typeof item.key === 'string' && validCount(item.count)
					)
			) ||
			!data.courses ||
			![
				'total',
				'active',
				'members',
				'materials',
				'usedBytes',
				'quotaBytes',
			].every((key) => validCount(data.courses[key])) ||
			!Array.isArray(data.courseUsageByWorkspace) ||
			!data.courseUsageByWorkspace.every(
				(item) =>
					Number.isInteger(item.tenantId) &&
					['courses', 'usedBytes', 'quotaBytes'].every((key) =>
						validCount(item[key])
					)
			)
		)
			throw new Error('Invalid aggregate metrics')
		if (current === version && !signal.aborted) metrics.value = response.data
	} catch (err) {
		if (current === version && !signal.aborted) {
			metrics.value = null
			error.value =
				err?.response?.data?.msg ||
				err?.data?.msg ||
				'业务统计暂不可用，请稍后重试'
		}
	} finally {
		if (current === version) loading.value = false
	}
}
function applyFilter() {
	if (!validTenant.value) return
	selectedTenant.value = tenantDraft.value ? Number(tenantDraft.value) : null
	metrics.value = null
	load()
}
function filterWorkspace(id) {
	tenantDraft.value = String(id)
	applyFilter()
}
watch(
	() => [
		store.loginInfo.role,
		store.loginInfo.username,
		store.loginInfo.tenantId,
		store.loginInfo.isLogin,
	],
	() => {
		controller?.abort()
		version++
		metrics.value = null
		tenantDraft.value = ''
		selectedTenant.value = null
		load()
	},
	{ flush: 'sync' }
)
onMounted(load)
onUnmounted(() => {
	controller?.abort()
	version++
	metrics.value = null
})
</script>

<style scoped>
.business-charts {
	margin: 22px 0;
}
.business-heading,
.chart-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
}
.business-heading h2 {
	font-size: 18px;
	margin: 0 0 8px;
}
.business-heading p,
.chart-note,
.scope-note {
	color: #827b88;
	font-size: 12px;
	line-height: 1.7;
}
.business-heading p {
	margin: 0;
}
.chart-actions button,
.metrics-filter button {
	background: #fff;
	border: 1px solid #ded8e4;
	border-radius: 22px;
	padding: 8px 14px;
	font-size: 12px;
	color: #3c3545;
}
.chart-actions button[aria-pressed='true'] {
	background: #eee7f8;
	border-color: #bfa9df;
}
.metrics-filter {
	display: flex;
	gap: 12px;
	align-items: center;
	flex-wrap: wrap;
	margin: 18px 0;
}
.metrics-filter label {
	display: flex;
	gap: 8px;
	align-items: center;
	font-size: 12px;
}
.metrics-filter select,
.metrics-filter input {
	max-width: 210px;
	background: #fff;
	color: #3b3542;
	border: 1px solid #ded8e4;
	border-radius: 8px;
	padding: 7px 10px;
}
.metrics-filter details {
	font-size: 12px;
	color: #827b88;
}
.metrics-filter summary {
	cursor: pointer;
}
.metrics-filter details[open] label {
	margin-top: 8px;
}
.business-grid {
	display: grid;
	grid-template-columns: 1fr 1fr;
	gap: 18px;
}
.business-card {
	padding: 22px;
	background: #fff;
	border: 1px solid #e1dce6;
	border-radius: 17px;
	min-width: 0;
}
.business-card h3 {
	font-size: 15px;
	margin: 0 0 8px;
}
.chart-note {
	margin: 0 0 20px;
}
.source-ring {
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 24px;
}
.source-ring svg {
	width: 160px;
	flex-shrink: 0;
}
.ring-total {
	font-size: 22px;
	font-weight: 650;
	fill: #332a40;
}
.ring-label {
	font-size: 9px;
	fill: #887f91;
}
.ring-legend {
	list-style: none;
	padding: 0;
	min-width: 130px;
}
.ring-legend li {
	font-size: 12px;
	display: flex;
	align-items: center;
	gap: 8px;
	margin: 12px 0;
}
.ring-legend li > span {
	height: 7px;
	width: 7px;
	border-radius: 50%;
	flex-shrink: 0;
}
.ring-legend strong {
	margin-left: auto;
	font-weight: 600;
}
.bar-list {
	list-style: none;
	padding: 0;
}
.bar-list li {
	margin: 20px 0;
}
.bar-list li > div {
	display: flex;
	justify-content: space-between;
	font-size: 12px;
	margin-bottom: 9px;
}
.bar-list svg {
	width: 100%;
	height: 10px;
}
.feedback-counts {
	display: grid;
	grid-template-columns: repeat(3, 1fr);
	list-style: none;
	padding: 0;
	margin: 28px 0;
}
.feedback-counts span {
	font-size: 12px;
	color: #8c8394;
}
.feedback-counts strong {
	font-size: 27px;
	display: block;
	margin-top: 10px;
}
.knowledge-total {
	display: flex;
	align-items: baseline;
	gap: 9px;
	border-top: 1px solid #eee9f2;
	padding-top: 16px;
}
.knowledge-total strong {
	font-size: 20px;
	color: #7c59b9;
}
.knowledge-total span,
.knowledge-total a {
	font-size: 12px;
}
.knowledge-total a {
	margin-left: auto;
	color: #6d5593;
}
.knowledge-scopes {
	list-style: none;
	padding: 0;
	display: flex;
	gap: 15px;
	flex-wrap: wrap;
	color: #908598;
	font-size: 11px;
}
.course-numbers {
	display: grid;
	grid-template-columns: 1fr 1fr;
	gap: 22px;
	margin: 22px 0;
}
.course-numbers dt {
	font-size: 12px;
	color: #8c8394;
}
.course-numbers dd {
	font-size: 23px;
	margin: 10px 0 0;
}
.course-numbers small {
	font-size: 11px;
	font-weight: 400;
	color: #9a8fa3;
	margin-left: 5px;
}
.unknown-usage {
	font-size: 12px;
	color: #95899e;
	padding: 10px;
	background: #f7f4f9;
	border-radius: 8px;
}
.usage-card {
	grid-column: 1/-1;
}
.usage-list {
	list-style: none;
	margin: 24px 0 0;
	padding: 0;
}
.usage-list li {
	display: grid;
	grid-template-columns: minmax(90px, 160px) 1fr 70px;
	gap: 20px;
	align-items: center;
	margin: 18px 0;
}
.usage-list button {
	background: none;
	border: 0;
	padding: 0;
	color: #4a3f57;
	text-align: left;
	font-size: 12px;
	overflow-wrap: anywhere;
}
.usage-list svg {
	width: 100%;
	height: 12px;
}
.usage-list span {
	display: block;
	font-size: 11px;
	color: #998ca3;
	margin-top: 6px;
}
.usage-list small {
	font-size: 11px;
	color: #998ca3;
	text-align: right;
}
table {
	width: 100%;
	border-collapse: collapse;
	font-size: 12px;
}
th,
td {
	padding: 12px 8px;
	border-bottom: 1px solid #efeaf4;
	text-align: left;
}
th {
	color: #8d8097;
	font-weight: 500;
}
.chart-empty {
	padding: 36px 12px;
	text-align: center;
	color: #9a8da3;
	font-size: 13px;
}
.error {
	color: #a74848;
	font-size: 13px;
	background: #fff2f2;
	padding: 12px;
	border-radius: 8px;
}
.visually-hidden {
	position: absolute;
	width: 1px;
	height: 1px;
	overflow: hidden;
	clip: rect(0, 0, 0, 0);
}
button:focus-visible,
select:focus-visible,
input:focus-visible {
	outline: 2px solid #9977d8;
	outline-offset: 3px;
}
button:disabled {
	opacity: 0.55;
	cursor: wait;
}
@media (max-width: 1050px) {
	.source-ring {
		gap: 10px;
	}
	.source-ring svg {
		width: 125px;
	}
}
@media (max-width: 760px) {
	.business-grid {
		grid-template-columns: 1fr;
	}
	.business-heading {
		align-items: flex-start;
		flex-wrap: wrap;
	}
	.usage-list li {
		grid-template-columns: 90px 1fr;
		gap: 12px;
	}
	.usage-list small {
		display: none;
	}
	.source-ring svg {
		width: 150px;
	}
	.business-card {
		padding: 18px;
	}
}
@media (prefers-reduced-motion: no-preference) {
	.bar-list rect,
	.usage-list rect {
		transition: width 0.2s ease;
	}
}
</style>
