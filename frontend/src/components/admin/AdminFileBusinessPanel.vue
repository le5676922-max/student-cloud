<template>
	<section
		v-if="store.globalConfig?.accountModel === 'independent'"
		class="file-business-panel"
		aria-labelledby="file-business-title"
		:aria-busy="loading"
	>
		<header>
			<div>
				<p class="file-eyebrow">文件业务</p>
				<h2 id="file-business-title">存储与协作统计</h2>
				<p>
					{{
						result?.scope === 'WORKSPACE'
							? '当前工作空间；课程仅统计本人创建的课程。'
							: '全局内容服务汇总；只展示数量与容量。'
					}}
				</p>
			</div>
			<button type="button" :disabled="loading" @click="load">
				{{ loading ? '读取中…' : '刷新文件统计' }}
			</button>
		</header>
		<p v-if="result?.generatedAt" class="file-business-hint">
			统计时间：{{
				formatOverviewDate(
					result.generatedAt,
					result.timeZone || 'Asia/Shanghai'
				)
			}}（{{ result.timeZone || 'Asia/Shanghai' }}）
		</p>
		<p v-if="error" role="alert" class="file-business-error">{{ error }}</p>
		<div class="file-business-metrics">
			<article v-for="metric in metrics" :key="metric.key">
				<span>{{ metric.label }}</span
				><strong>{{ metric.value }}</strong
				><small>{{ metric.note }}</small>
			</article>
		</div>
		<p
			v-if="!result || hasUnavailable"
			class="file-business-hint"
			role="status"
		>
			“—”表示数据源尚未返回或暂不可用，不代表0。
		</p>
		<div class="file-business-trend-heading">
			<h3>最近{{ days }}天的创建记录</h3>
			<label
				>日期范围<el-select
					v-model="days"
					:disabled="loading"
					aria-label="文件统计日期范围"
				>
					<el-option :value="7" label="7天" />
					<el-option :value="30" label="30天" /> </el-select
			></label>
		</div>
		<p class="file-business-hint">
			按当前保留记录的创建日期统计，不代表历史审计总量。课程资料仅计当前可用记录。时间：Asia/Shanghai。
		</p>
		<div class="file-business-trends">
			<figure v-for="series in trends" :key="series.key">
				<figcaption>{{ series.label }}</figcaption>
				<p v-if="!series.available" class="file-business-hint">
					{{ series.note || '趋势数据尚未返回' }}
				</p>
				<div
					v-else
					class="file-business-bars"
					role="img"
					:aria-label="series.description"
				>
					<div
						v-for="day in series.days"
						:key="day.date"
						class="file-business-day"
					>
						<span
							class="file-business-column"
							:title="`${day.date}：${day.count}`"
							><i :style="{ height: `${day.height}%` }" /></span
						><small>{{ day.date.slice(5) }}</small>
					</div>
				</div>
				<details v-if="series.available">
					<summary>查看数据</summary>
					<table>
						<caption>
							{{
								series.label
							}}按日统计
						</caption>
						<thead>
							<tr>
								<th>日期</th>
								<th>数量</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="day in series.days" :key="day.date">
								<th scope="row">{{ day.date }}</th>
								<td>{{ day.count }}</td>
							</tr>
						</tbody>
					</table>
				</details>
			</figure>
		</div>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useStorage } from '@vueuse/core'
import useStorageConfigStore from '~/stores/storage-config'
import { getFileBusinessOverviewReq } from '~/api/admin/file-business'
import {
	formatOverviewBytes,
	formatOverviewCount,
	formatOverviewDate,
} from '~/utils/admin-overview.mjs'
import { createLatestRequest } from '~/utils/latest-request.mjs'
// The global overview owns one content request for both its cards and this panel.
// Workspace administration keeps the existing independently loaded variant.
const props = defineProps({
	managed: { type: Boolean, default: false },
	overview: { type: Object, default: null },
	pending: { type: Boolean, default: false },
	failure: { type: String, default: '' },
	period: { type: Number, default: 7 },
})
const emit = defineEmits(['refresh'])
const store = useStorageConfigStore(),
	credential = useStorage('tongligong-student-cloud-token', '', undefined, {
		writeDefaults: false,
	})
const ownResult = ref(null),
	ownLoading = ref(false),
	ownError = ref(''),
	ownDays = ref(7),
	requests = createLatestRequest()
const days = computed({
	get: () => (props.managed ? props.period : ownDays.value),
	set: (value) => {
		if (props.managed) {
			if (authorized.value && !props.pending) emit('refresh', value)
		} else ownDays.value = value
	},
})
const authorized = computed(
	() =>
		store.loginInfo?.isLogin === true &&
		['ADMIN', 'TENANT_ADMIN'].includes(store.loginInfo?.role)
)
const result = computed(() =>
	authorized.value ? (props.managed ? props.overview : ownResult.value) : null
)
const loading = computed(() =>
	props.managed ? props.pending : ownLoading.value
)
const error = computed(() => (props.managed ? props.failure : ownError.value))
const identity = computed(() =>
	JSON.stringify([
		store.globalConfig?.accountModel,
		store.loginInfo?.id,
		store.loginInfo?.accountId,
		store.loginInfo?.username,
		store.loginInfo?.role,
		store.loginInfo?.tenantId,
		store.loginInfo?.isLogin,
		credential.value,
	])
)
const definitions = computed(() => [
	['mounts', '存储连接', '当前已登记连接'],
	[
		'courses',
		result.value?.courseScope === 'OWN_CREATED' ? '本人创建课程' : '创建课程',
		'含已暂停课程，不含已删除课程',
	],
	['readyMaterials', '可用课程资料', '当前已完成的课程文件'],
	['materialBytes', '课程资料容量', '可用资料的文件大小', true],
	['activeUploads', '进行中上传', '未过期且接收或发布中的任务'],
	['stagingBytes', '上传暂存占用', '待确认清理的预留容量', true],
	['activeShares', '有效受控分享', '未撤销且未过期的分享'],
	['trashItems', '回收记录', '包含保留中和待核查记录'],
	['trashBytes', '回收保留容量', '包含过期但尚未确认清理的内容', true],
])
const metrics = computed(() =>
	definitions.value.map(([key, label, note, bytes]) => {
		const metric = result.value?.metrics?.[key],
			value = metricValue(metric)
		return {
			key,
			label,
			value: bytes ? formatOverviewBytes(value) : formatOverviewCount(value),
			note:
				metric?.available === false ? metric.note || '数据源暂不可用' : note,
		}
	})
)
const hasUnavailable = computed(
	() =>
		result.value?.dataComplete === false ||
		definitions.value.some(
			([key]) => metricValue(result.value?.metrics?.[key]) === null
		)
)
function metricValue(metric) {
	return metric?.available === true &&
		Number.isSafeInteger(metric.value) &&
		metric.value >= 0
		? metric.value
		: null
}
const trends = computed(() =>
	[
		['uploads', '续传任务创建'],
		['shares', '受控分享创建'],
		['materials', '课程资料创建'],
	].map(([key, label]) => {
		const trend = result.value?.trends?.[key],
			source = Array.isArray(trend?.days) ? trend.days.slice(0, 32) : []
		const valid =
			trend?.available === true &&
			source.length > 0 &&
			source.every(
				(day) =>
					typeof day?.date === 'string' &&
					/^\d{4}-\d{2}-\d{2}$/.test(day.date) &&
					Number.isFinite(day.count) &&
					day.count >= 0
			)
		const maximum = Math.max(1, ...source.map((day) => day.count || 0))
		const values = valid
			? source.map((day) => ({ ...day, height: (day.count / maximum) * 100 }))
			: []
		return {
			key,
			label,
			available: valid,
			days: values,
			note: trend?.note,
			description: values.map((day) => `${day.date}：${day.count}`).join('；'),
		}
	})
)
async function load() {
	if (props.managed) {
		if (authorized.value && !props.pending) emit('refresh', days.value)
		return
	}
	const request = requests.begin()
	ownResult.value = null
	ownError.value = ''
	ownLoading.value = false
	if (store.globalConfig?.accountModel !== 'independent') return
	if (
		store.loginInfo?.isLogin !== true ||
		!['ADMIN', 'TENANT_ADMIN'].includes(store.loginInfo?.role)
	) {
		ownError.value = '仅管理员可以查看相应范围的文件业务统计。'
		return
	}
	ownLoading.value = true
	try {
		const response = await getFileBusinessOverviewReq(days.value, {
			signal: request.signal,
		})
		if (!request.isCurrent()) return
		if (
			!response.data ||
			!['GLOBAL', 'WORKSPACE'].includes(response.data.scope) ||
			(store.loginInfo.role === 'ADMIN' &&
				(response.data.scope !== 'GLOBAL' ||
					response.data.workspaceId != null)) ||
			(store.loginInfo.role === 'TENANT_ADMIN' &&
				(response.data.scope !== 'WORKSPACE' ||
					response.data.workspaceId !== store.loginInfo.tenantId))
		)
			throw new Error('统计范围与当前账号不符，请重新登录后重试')
		ownResult.value = response.data
	} catch (failure) {
		if (request.isCurrent())
			ownError.value =
				failure?.response?.data?.msg ||
				failure?.message ||
				'文件统计暂不可用，请刷新重试。'
	} finally {
		if (request.isCurrent()) ownLoading.value = false
	}
}
watch(
	identity,
	() => {
		requests.cancel()
		ownResult.value = null
		ownError.value = ''
		ownLoading.value = false
		if (!props.managed) load()
	},
	{ immediate: true, flush: 'sync' }
)
watch(ownDays, load)
onBeforeUnmount(() => requests.cancel())
</script>

<style scoped>
.file-business-panel {
	margin: 24px 0;
	padding: 24px;
	border: 1px solid var(--cloud-line, #e3e3e7);
	border-radius: 16px;
	background: var(--cloud-surface, #fff);
	color: var(--cloud-ink, #242428);
}
header,
.file-business-trend-heading {
	display: flex;
	gap: 18px;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
}
h2 {
	font-size: 18px;
	margin: 4px 0 8px;
}
h3,
figcaption {
	font-size: 14px;
	font-weight: 600;
}
p,
small,
label {
	font-size: 12px;
	line-height: 1.7;
	color: var(--cloud-muted, #696970);
}
.file-eyebrow {
	color: var(--cloud-accent-ink, #5b3ec4);
	margin: 0;
}
button {
	color: inherit;
	background: var(--cloud-surface, #fff);
	border: 1px solid var(--cloud-line, #e3e3e7);
	border-radius: 8px;
	padding: 8px 10px;
	cursor: pointer;
	font: inherit;
	font-size: 12px;
	min-width: 112px;
}
button:hover:enabled {
	border-color: var(--cloud-accent);
	background: var(--cloud-soft);
}
button:focus-visible,
summary:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 3px;
}
button:active:enabled {
	background: var(--cloud-lilac);
}
button:disabled {
	opacity: 0.5;
	cursor: wait;
}
.file-business-trend-heading :deep(.el-select) {
	margin-left: 8px;
	width: 110px;
}
.file-business-metrics {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(145px, 1fr));
	gap: 12px;
	margin: 20px 0;
}
.file-business-metrics article {
	padding: 16px;
	border-radius: 12px;
	background: var(--cloud-soft, #f7f7f8);
}
.file-business-metrics span {
	font-size: 12px;
}
.file-business-metrics strong {
	display: block;
	font-size: 24px;
	margin: 10px 0 6px;
	letter-spacing: -0.04em;
}
.file-business-metrics small {
	display: block;
}
.file-business-trends {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
	gap: 20px;
}
.file-business-trends figure {
	margin: 0;
	min-width: 0;
}
.file-business-bars {
	display: flex;
	gap: 4px;
	margin-top: 16px;
	overflow-x: auto;
}
.file-business-day {
	flex: 1;
	min-width: 19px;
	text-align: center;
}
.file-business-column {
	display: flex;
	height: 110px;
	align-items: flex-end;
	justify-content: center;
	border-bottom: 1px solid var(--cloud-line, #e3e3e7);
}
.file-business-column i {
	display: block;
	width: 70%;
	max-width: 24px;
	border-radius: 4px 4px 0 0;
	background: var(--cloud-accent, #7855df);
}
.file-business-day small {
	display: block;
	font-size: 9px;
	writing-mode: vertical-rl;
	margin: 6px auto;
}
.file-business-error {
	color: var(--el-color-danger, #a43232);
}
details {
	margin-top: 12px;
	font-size: 12px;
}
summary {
	cursor: pointer;
	color: var(--cloud-accent-ink, #5b3ec4);
}
table {
	width: 100%;
	border-collapse: collapse;
	margin-top: 12px;
}
th,
td {
	padding: 6px;
	text-align: left;
	border-bottom: 1px solid var(--cloud-line, #e3e3e7);
}
caption {
	text-align: left;
	padding-bottom: 8px;
	color: var(--cloud-muted, #696970);
}
@media (max-width: 600px) {
	.file-business-panel {
		padding: 16px;
	}
	.file-business-trends {
		grid-template-columns: 1fr;
	}
}
</style>
