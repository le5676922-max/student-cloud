<template>
	<section class="global-overview">
		<div class="heading">
			<div>
				<p class="eyebrow">全局管理</p>
				<h1>站点总览</h1>
				<p class="heading-description">
					查看所有工作空间的规模、运营记录与待处理事项。
				</p>
				<p class="updated-at" aria-live="polite">
					{{
						overview
							? `统计时间：${formatOverviewDate(
									overview.generatedAt,
									timeZone
							  )}（${timeZone}）`
							: '统计数据尚未加载'
					}}
				</p>
			</div>
			<el-button
				class="refresh-button"
				:loading="refreshing"
				:disabled="refreshing"
				@click="refreshAll"
				><ArrowPathIcon
					v-if="!refreshing"
					aria-hidden="true"
				/>刷新数据</el-button
			>
		</div>
		<el-alert
			v-if="overviewError"
			:title="overviewError"
			type="error"
			:closable="false"
			show-icon
		/>
		<div
			v-loading="overviewLoading"
			class="metrics"
			:aria-busy="overviewLoading"
		>
			<router-link
				v-for="metric in metrics"
				:key="metric.label"
				class="metric"
				:class="`metric-${metric.tone}`"
				:to="metric.to"
			>
				<div class="metric-heading">
					<span class="metric-icon"
						><component :is="metricIcons[metric.icon]" aria-hidden="true"
					/></span>
				</div>
				<span class="metric-label">{{ metric.label }}</span>
				<strong>{{ formatOverviewCount(metric.value) }}</strong>
				<small>{{ metric.note }}</small>
			</router-link>
			<div class="metric metric-clay">
				<div class="metric-heading">
					<span class="metric-icon"><ClockIcon aria-hidden="true" /></span>
				</div>
				<span class="metric-label">待确认 Agent 任务</span>
				<strong>{{ formatOverviewCount(overview?.pendingAgentTasks) }}</strong>
				<small>由任务所有者确认；不开放全局代执行</small>
			</div>
		</div>

		<template v-if="overview">
			<section class="panel attention-panel" aria-labelledby="attention-title">
				<div class="section-heading">
					<h2 id="attention-title">运营提醒</h2>
					<span class="hint">统计范围：所有工作空间</span>
				</div>
				<div v-if="attention.items.length" class="attention-grid">
					<div
						v-for="item in attention.items"
						:key="item.key"
						class="attention-item"
					>
						<div class="attention-summary">
							<span>{{ item.label }}</span
							><strong>{{ formatOverviewCount(item.count) }}</strong>
						</div>
						<p>{{ item.note }}</p>
						<router-link v-if="item.to" :to="item.to"
							>{{ item.linkText }} →</router-link
						>
					</div>
				</div>
				<p v-else-if="attention.complete" class="empty-note">
					当前没有上述待处理事项。停用、过期及异常记录会在此提示。
				</p>
				<p v-if="!attention.complete" class="incomplete-note">
					部分待处理指标尚未返回，无法判断是否全部正常，请刷新后重试。
				</p>
			</section>

			<div class="operations-grid">
				<section class="panel" aria-labelledby="login-trend-title">
					<div class="section-heading">
						<h2 id="login-trend-title">最近 7 天登录记录</h2>
						<router-link to="/admin/login-log">查看登录日志 →</router-link>
					</div>
					<p
						:class="overview.loginLoggingEnabled === true ? 'hint' : 'log-note'"
					>
						{{ overviewLoginLoggingNote(overview.loginLoggingEnabled) }}
					</p>
					<div class="login-totals">
						<div>
							<span>24 小时成功</span
							><strong>{{
								formatOverviewCount(overview.loginSummary?.successes24h)
							}}</strong>
						</div>
						<div>
							<span>24 小时失败</span
							><strong>{{
								formatOverviewCount(overview.loginSummary?.failures24h)
							}}</strong>
						</div>
					</div>
					<div v-if="trend.length" class="trend-legend" aria-hidden="true">
						<span><i class="trend-success" />成功</span
						><span><i class="trend-failure" />失败</span>
					</div>
					<div v-if="trend.length" class="trend-chart" aria-hidden="true">
						<div v-for="day in trend" :key="day.date" class="trend-day">
							<div class="trend-bars">
								<span
									class="trend-bar trend-success"
									:style="{ height: `${day.successHeight}%` }"
								/>
								<span
									class="trend-bar trend-failure"
									:style="{ height: `${day.failureHeight}%` }"
								/>
							</div>
							<span class="trend-date">{{
								day.date === '—' ? '—' : day.date.slice(5)
							}}</span>
						</div>
					</div>
					<table v-if="trend.length" class="trend-table">
						<caption>
							按{{
								timeZone
							}}统计的登录次数
						</caption>
						<thead>
							<tr>
								<th scope="col">日期</th>
								<th scope="col">成功</th>
								<th scope="col">失败</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="day in trend" :key="day.date">
								<th scope="row">{{ day.date }}</th>
								<td>{{ formatOverviewCount(day.successes) }}</td>
								<td>{{ formatOverviewCount(day.failures) }}</td>
							</tr>
						</tbody>
					</table>
					<p v-else class="incomplete-note">
						趋势数据未返回，不能将其视为 0 次登录。
					</p>
				</section>

				<section class="panel" aria-labelledby="runtime-title">
					<div class="section-heading">
						<h2 id="runtime-title">后端运行状态</h2>
						<span class="hint">当前服务实例</span>
					</div>
					<dl class="runtime-details">
						<div>
							<dt>运行时长</dt>
							<dd>{{ formatOverviewUptime(overview.runtime?.uptimeMs) }}</dd>
						</div>
						<div>
							<dt>数据库类型</dt>
							<dd>{{ overview.runtime?.databaseType || '—' }}</dd>
						</div>
						<div>
							<dt>数据库探测耗时</dt>
							<dd>{{ formatLatency(overview.runtime?.databaseLatencyMs) }}</dd>
						</div>
						<div>
							<dt>已用 JVM 堆内存</dt>
							<dd>
								{{ formatOverviewBytes(overview.runtime?.usedHeapBytes) }}
							</dd>
						</div>
						<div>
							<dt>JVM 最大堆内存</dt>
							<dd>{{ formatOverviewBytes(overview.runtime?.maxHeapBytes) }}</dd>
						</div>
						<div>
							<dt>运行中 Agent 任务</dt>
							<dd>
								{{ formatOverviewCount(overview.statuses?.runningAgentTasks) }}
							</dd>
						</div>
						<div>
							<dt>已启用自动化计划</dt>
							<dd>
								{{ formatOverviewCount(overview.statuses?.enabledSchedules) }}
							</dd>
						</div>
					</dl>
					<p class="hint runtime-note">
						内存数据仅代表当前 Java
						进程的堆内存，不是服务器总内存、磁盘容量或用户文件容量。探测耗时不是用户请求的平均响应延迟。
					</p>
					<div class="quick-links">
						<router-link to="/admin/user-list">成员管理</router-link
						><router-link to="/admin/storage-list">存储管理</router-link
						><router-link to="/admin/site-setting">站点设置</router-link>
					</div>
				</section>
			</div>

			<section class="panel" aria-labelledby="recent-logins-title">
				<div class="section-heading">
					<h2 id="recent-logins-title">最近登录尝试</h2>
					<router-link to="/admin/login-log">完整记录 →</router-link>
				</div>
				<p class="hint">
					最近 8
					条已记录的尝试。失败记录中的账号来自输入内容，不代表该用户一定存在。
				</p>
				<el-table
					v-if="Array.isArray(overview.recentLogins)"
					:data="recentLogins"
					empty-text="暂无已记录的登录尝试"
				>
					<el-table-column
						prop="username"
						label="登录账号"
						min-width="200"
						show-overflow-tooltip
					/>
					<el-table-column label="时间" min-width="200"
						><template #default="scope">{{
							formatOverviewDate(scope.row.createdAt, timeZone)
						}}</template></el-table-column
					>
					<el-table-column label="结果" width="100"
						><template #default="scope"
							><el-tag
								:type="
									scope.row.success === true
										? 'success'
										: scope.row.success === false
										? 'danger'
										: 'info'
								"
								>{{
									scope.row.success === true
										? '成功'
										: scope.row.success === false
										? '失败'
										: '未知'
								}}</el-tag
							></template
						></el-table-column
					>
				</el-table>
				<p v-else class="incomplete-note">最近登录记录未返回，请刷新重试。</p>
			</section>
		</template>

		<section class="panel workspace-panel" aria-labelledby="workspace-title">
			<div class="section-heading">
				<h2 id="workspace-title">工作空间概况</h2>
				<router-link to="/admin/tenant-list">管理工作空间 →</router-link>
			</div>
			<p class="hint">
				搜索全部空间，查看成员与存储规模；这里不展示成员的私人文件和任务内容。
			</p>
			<form class="workspace-filters" @submit.prevent="applyFilters">
				<label class="filter-field"
					><span>空间名称 / ID</span
					><el-input
						v-model="keyword"
						placeholder="输入名称或空间 ID"
						aria-label="搜索工作空间名称或 ID"
						maxlength="100"
						clearable
				/></label>
				<label class="filter-field status-filter"
					><span>状态</span
					><el-select
						v-model="enabled"
						aria-label="工作空间状态"
						@change="applyFilters"
						><el-option label="全部状态" value="" /><el-option
							label="启用"
							value="true" /><el-option label="停用" value="false" /></el-select
				></label>
				<div class="filter-actions">
					<el-button
						native-type="submit"
						type="primary"
						:loading="workspacesLoading"
						>查询</el-button
					><el-button :disabled="workspacesLoading" @click="resetFilters"
						>重置</el-button
					>
				</div>
			</form>
			<el-alert
				v-if="workspacesError"
				:title="workspacesError"
				type="error"
				:closable="false"
				show-icon
				class="workspace-error"
			/>
			<div v-if="workspacesError" class="retry-row">
				<el-button
					:loading="workspacesLoading"
					@click="loadWorkspaces(workspaceQuery)"
					>重新加载空间</el-button
				>
			</div>
			<el-table
				v-else
				v-loading="workspacesLoading"
				:data="workspacePage?.records || []"
				:empty-text="workspacesLoading ? '正在加载…' : '没有匹配的工作空间'"
				:aria-busy="workspacesLoading"
			>
				<el-table-column
					prop="name"
					label="名称"
					min-width="170"
					show-overflow-tooltip
				/>
				<el-table-column prop="id" label="ID" width="85" />
				<el-table-column label="状态" width="85"
					><template #default="scope"
						><el-tag
							:type="
								scope.row.enabled === true
									? 'success'
									: scope.row.enabled === false
									? 'info'
									: 'warning'
							"
							>{{
								scope.row.enabled === true
									? '启用'
									: scope.row.enabled === false
									? '停用'
									: '未知'
							}}</el-tag
						></template
					></el-table-column
				>
				<el-table-column
					label="负责人账号 ID"
					min-width="190"
					show-overflow-tooltip
					><template #default="scope">{{
						scope.row.ownerAccountId || '暂未分配'
					}}</template></el-table-column
				>
				<el-table-column label="成员" width="80"
					><template #default="scope">{{
						formatOverviewCount(scope.row.members)
					}}</template></el-table-column
				>
				<el-table-column label="管理员" width="90"
					><template #default="scope">{{
						formatOverviewCount(scope.row.workspaceAdmins)
					}}</template></el-table-column
				>
				<el-table-column label="待审核" width="90"
					><template #default="scope">{{
						formatOverviewCount(scope.row.pendingMembers)
					}}</template></el-table-column
				>
				<el-table-column label="存储源" width="90"
					><template #default="scope">{{
						formatOverviewCount(scope.row.storageSources)
					}}</template></el-table-column
				>
				<el-table-column label="操作" width="85" fixed="right"
					><template #default="scope"
						><router-link
							class="table-link"
							:aria-label="`管理空间 ${scope.row.name}`"
							:to="{
								path: '/admin/tenant-list',
								query: { workspaceId: String(scope.row.id) },
							}"
							>管理</router-link
						></template
					></el-table-column
				>
			</el-table>
			<div v-if="workspacePage" class="pagination-row">
				<span class="hint" aria-live="polite"
					>共 {{ formatOverviewCount(workspacePage.total) }} 个匹配空间</span
				>
				<el-pagination
					:current-page="workspacePage.page"
					:page-size="workspacePage.pageSize"
					:page-sizes="[10, 20, 50]"
					:total="workspacePage.total"
					:disabled="workspacesLoading"
					layout="sizes, prev, pager, next"
					@current-change="changePage"
					@size-change="changePageSize"
				/>
			</div>
		</section>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
	ArrowPathIcon,
	BuildingOffice2Icon,
	UsersIcon,
	ShieldCheckIcon,
	CircleStackIcon,
	LinkIcon,
	ClockIcon,
} from '@heroicons/vue/24/outline'
import {
	getGlobalOverviewReq,
	getGlobalWorkspacesReq,
} from '~/api/admin/admin-overview'
import {
	createOverviewRequestGate,
	formatOverviewBytes,
	formatOverviewCount,
	formatOverviewDate,
	formatOverviewUptime,
	normalizeOverviewWorkspaceQuery,
	overviewAttention,
	overviewFailureMessage,
	overviewLoginTrend,
	overviewLoginLoggingNote,
	overviewNumber,
} from '~/utils/admin-overview.mjs'

const overview = ref(null)
const overviewLoading = ref(false)
const overviewError = ref('')
const workspacePage = ref(null)
const workspacesLoading = ref(false)
const workspacesError = ref('')
const keyword = ref('')
const enabled = ref('')
const workspaceQuery = ref(normalizeOverviewWorkspaceQuery())
const overviewGate = createOverviewRequestGate()
const workspaceGate = createOverviewRequestGate()
const refreshing = computed(
	() => overviewLoading.value || workspacesLoading.value
)
const timeZone = computed(() => overview.value?.timeZone || 'Asia/Shanghai')
const attention = computed(() => overviewAttention(overview.value))
const trend = computed(() => overviewLoginTrend(overview.value?.dailyLogins))
const recentLogins = computed(() =>
	Array.isArray(overview.value?.recentLogins)
		? overview.value.recentLogins.slice(0, 8)
		: []
)
const metricIcons = {
	workspace: BuildingOffice2Icon,
	members: UsersIcon,
	admins: ShieldCheckIcon,
	storage: CircleStackIcon,
	shares: LinkIcon,
}
const metrics = computed(() => [
	{
		label: '工作空间',
		icon: 'workspace',
		tone: 'green',
		value: overview.value?.workspaces,
		to: '/admin/tenant-list',
		note: `启用 ${formatOverviewCount(
			overview.value?.statuses?.enabledWorkspaces
		)} · 停用 ${formatOverviewCount(
			overview.value?.statuses?.disabledWorkspaces
		)}`,
	},
	{
		label: '成员账号',
		icon: 'members',
		tone: 'amber',
		value: overview.value?.members,
		to: '/admin/user-list',
		note: `账号启用 ${formatOverviewCount(
			overview.value?.statuses?.activeMembers
		)} · 停用 ${formatOverviewCount(
			overview.value?.statuses?.disabledMembers
		)}（不含待审核 / 拒绝）`,
	},
	{
		label: '工作空间管理员',
		icon: 'admins',
		tone: 'green',
		value: overview.value?.workspaceAdmins,
		to: '/admin/user-list',
		note: '仅管理本空间；不是全局管理员',
	},
	{
		label: '存储源',
		icon: 'storage',
		tone: 'slate',
		value: overview.value?.storageSources,
		to: '/admin/storage-list',
		note: `已启用 ${formatOverviewCount(
			overview.value?.statuses?.enabledStorages
		)} · 所有空间汇总`,
	},
	{
		label: '分享记录',
		icon: 'shares',
		tone: 'slate',
		value: overview.value?.shares,
		to: '/admin/share-link',
		note: `已过期 ${formatOverviewCount(
			overview.value?.statuses?.expiredShares
		)} · 所有空间汇总`,
	},
])

function formatLatency(value) {
	const number = overviewNumber(value)
	return number === null
		? '—'
		: `${formatOverviewCount(Number(number.toFixed(1)))} ms`
}

async function loadOverview() {
	const request = overviewGate.begin()
	if (!request) return
	overviewLoading.value = true
	overviewError.value = ''
	overview.value = null
	try {
		const response = await getGlobalOverviewReq({
			signal: request.signal,
			timeout: 20000,
			showDefaultMsg: false,
		})
		if (!overviewGate.isCurrent(request)) return
		if (
			!response?.data ||
			typeof response.data !== 'object' ||
			Array.isArray(response.data)
		)
			throw new Error('Invalid overview response')
		overview.value = response.data
	} catch (failure) {
		if (overviewGate.isCurrent(request))
			overviewError.value = overviewFailureMessage(
				failure,
				'总览加载失败，请刷新重试。未获取的指标不会显示为 0 或健康。'
			)
	} finally {
		if (overviewGate.isCurrent(request)) overviewLoading.value = false
		overviewGate.finish(request)
	}
}

async function loadWorkspaces(params = workspaceQuery.value) {
	const request = workspaceGate.begin()
	if (!request) return
	const query = normalizeOverviewWorkspaceQuery(params)
	workspaceQuery.value = query
	workspacesLoading.value = true
	workspacesError.value = ''
	workspacePage.value = null
	try {
		const response = await getGlobalWorkspacesReq(query, {
			signal: request.signal,
			timeout: 20000,
			showDefaultMsg: false,
		})
		if (!workspaceGate.isCurrent(request)) return
		const data = response?.data
		if (
			!Array.isArray(data?.records) ||
			!Number.isInteger(data?.total) ||
			data.total < 0 ||
			!Number.isInteger(data?.page) ||
			data.page < 1 ||
			!Number.isInteger(data?.pageSize) ||
			data.pageSize < 1 ||
			data.pageSize > 100
		)
			throw new Error('Invalid workspace response')
		workspacePage.value = data
		workspaceQuery.value = {
			...query,
			page: data.page,
			pageSize: data.pageSize,
		}
	} catch (failure) {
		if (workspaceGate.isCurrent(request))
			workspacesError.value = overviewFailureMessage(
				failure,
				'工作空间加载失败，请重试。'
			)
	} finally {
		if (workspaceGate.isCurrent(request)) workspacesLoading.value = false
		workspaceGate.finish(request)
	}
}

function refreshAll() {
	if (refreshing.value) return
	return Promise.all([loadOverview(), loadWorkspaces()])
}

function applyFilters() {
	return loadWorkspaces({
		keyword: keyword.value,
		enabled: enabled.value,
		page: 1,
		pageSize: workspaceQuery.value.pageSize,
	})
}

function resetFilters() {
	keyword.value = ''
	enabled.value = ''
	return applyFilters()
}

function changePage(page) {
	return loadWorkspaces({ ...workspaceQuery.value, page })
}

function changePageSize(pageSize) {
	return loadWorkspaces({ ...workspaceQuery.value, page: 1, pageSize })
}

onMounted(refreshAll)
onBeforeUnmount(() => {
	overviewGate.dispose()
	workspaceGate.dispose()
})
</script>

<style scoped>
.global-overview {
	padding: 0 0 12px;
	color: var(--cloud-ink);
}
.heading,
.section-heading {
	display: flex;
	justify-content: space-between;
	gap: 16px;
	align-items: center;
}
.heading {
	margin-bottom: 22px;
	align-items: flex-start;
}
h1 {
	font-size: 26px;
	font-weight: 650;
	margin: 5px 0 8px;
	letter-spacing: -0.035em;
}
h2 {
	font-size: 16px;
	font-weight: 600;
	margin: 0;
}
.eyebrow {
	color: var(--cloud-primary);
	font-size: 12px;
	margin: 0;
	font-weight: 600;
	letter-spacing: 0.06em;
}
.heading-description,
.hint,
.updated-at {
	color: var(--cloud-muted);
}
.heading-description {
	margin: 0;
	font-size: 13px;
	line-height: 1.7;
}
.updated-at {
	font-size: 12px;
	margin: 8px 0 0;
}
.refresh-button {
	margin-top: 23px;
}
.refresh-button :deep(svg) {
	width: 15px;
	height: 15px;
	margin-right: 6px;
}
.metrics {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 12px;
	margin: 20px 0 22px;
}
.metric {
	position: relative;
	display: flex;
	flex-direction: column;
	gap: 8px;
	padding: 16px 18px;
	border: 1px solid var(--cloud-line);
	border-radius: 10px;
	background: var(--cloud-surface);
	color: inherit;
	text-decoration: none;
	min-width: 0;
	transition: border-color 150ms ease, background-color 150ms ease;
}
a.metric:hover {
	border-color: #8daea3;
	background: #fcfdfb;
}
.metric-heading {
	position: absolute;
	top: 14px;
	right: 15px;
	display: flex;
	align-items: center;
	gap: 8px;
}
.metric-icon {
	display: grid;
	place-items: center;
	width: 34px;
	height: 34px;
	border-radius: 9px;
	background: #e8f1ec;
	color: var(--cloud-primary);
}
.metric-icon svg {
	width: 18px;
	height: 18px;
}
.metric-amber .metric-icon {
	background: #f5eddc;
	color: #996b25;
}
.metric-slate .metric-icon {
	background: #edf0ed;
	color: #596b66;
}
.metric-clay .metric-icon {
	background: #f6eae3;
	color: #a66747;
}
.metric-label {
	padding-right: 37px;
	font-size: 12px;
	color: var(--cloud-muted);
}
.metric strong {
	font-size: 30px;
	font-weight: 650;
	line-height: 1.15;
	font-variant-numeric: tabular-nums;
	letter-spacing: -0.025em;
}
.metric small {
	color: var(--cloud-muted);
	line-height: 1.6;
	font-size: 11px;
}
.panel {
	padding: 20px;
	border: 1px solid var(--cloud-line);
	border-radius: 12px;
	background: var(--cloud-surface);
	margin-bottom: 20px;
	min-width: 0;
}
.section-heading a,
.attention-item a,
.quick-links a,
.table-link {
	color: var(--cloud-primary);
	font-size: 13px;
	text-decoration: none;
}
.section-heading a:hover,
.attention-item a:hover,
.quick-links a:hover,
.table-link:hover {
	text-decoration: underline;
}
.hint {
	font-size: 12px;
	line-height: 1.65;
}
.global-overview a:focus-visible {
	outline: 2px solid var(--cloud-primary);
	outline-offset: 3px;
	border-radius: 4px;
}
.metric:focus-visible {
	border-radius: 10px !important;
}
.section-heading + .hint {
	margin: 10px 0 16px;
}
.attention-grid {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
	gap: 12px;
	margin-top: 18px;
}
.attention-item {
	padding: 14px;
	background: #f5f6f1;
	border-radius: 8px;
	border-left: 2px solid #c8aa73;
}
.attention-summary {
	display: flex;
	justify-content: space-between;
	gap: 16px;
	font-size: 14px;
}
.attention-summary strong {
	color: #986929;
}
.attention-item p {
	font-size: 12px;
	color: var(--cloud-muted);
	line-height: 1.6;
	margin: 8px 0;
}
.empty-note,
.incomplete-note {
	color: var(--cloud-muted);
	font-size: 13px;
	line-height: 1.7;
	margin: 14px 0 0;
}
.incomplete-note {
	color: #986929;
}
.operations-grid {
	display: grid;
	grid-template-columns: 1.1fr 1fr;
	gap: 20px;
}
.log-note {
	color: #8c652c;
	background: #f8f2e7;
	padding: 10px 12px;
	font-size: 12px;
	line-height: 1.7;
	border-radius: 6px;
	margin-top: 14px;
}
.login-totals {
	display: flex;
	gap: 32px;
	margin: 18px 0;
	padding-bottom: 15px;
	border-bottom: 1px solid var(--cloud-line);
}
.login-totals div {
	display: flex;
	flex-direction: column;
	gap: 6px;
}
.login-totals span {
	color: var(--cloud-muted);
	font-size: 12px;
}
.login-totals strong {
	font-size: 23px;
	font-weight: 600;
	font-variant-numeric: tabular-nums;
}
.trend-chart {
	display: grid;
	grid-template-columns: repeat(7, minmax(0, 1fr));
	gap: 12px;
	margin-top: 8px;
}
.trend-legend {
	display: flex;
	gap: 18px;
	color: var(--cloud-muted);
	font-size: 12px;
	margin-bottom: 8px;
}
.trend-legend span {
	display: flex;
	align-items: center;
	gap: 6px;
}
.trend-legend i {
	width: 10px;
	height: 10px;
	display: block;
	border-radius: 2px;
}
.trend-day {
	text-align: center;
	min-width: 0;
}
.trend-bars {
	display: flex;
	align-items: flex-end;
	justify-content: center;
	gap: 5px;
	height: 105px;
	border-bottom: 1px solid var(--cloud-line);
}
.trend-bar {
	display: block;
	width: 16px;
	max-width: 35%;
	border-radius: 3px 3px 0 0;
}
.trend-success {
	background: #5f9482;
}
.trend-failure {
	background: #c79272;
}
.trend-date {
	font-size: 11px;
	display: block;
	color: var(--cloud-muted);
	margin-top: 8px;
}
.trend-table {
	border-collapse: collapse;
	width: 100%;
	font-size: 12px;
	margin-top: 16px;
}
.trend-table caption {
	text-align: left;
	color: var(--cloud-muted);
	margin-bottom: 7px;
}
.trend-table th,
.trend-table td {
	padding: 7px 8px;
	border-bottom: 1px solid var(--cloud-line);
	text-align: left;
}
.trend-table th {
	font-weight: 500;
	color: var(--cloud-muted);
}
.trend-table thead {
	background: #f4f5f1;
}
.runtime-details {
	margin: 20px 0 14px;
}
.runtime-details div {
	display: flex;
	justify-content: space-between;
	gap: 16px;
	padding: 11px 0;
	border-bottom: 1px solid var(--cloud-line);
	font-size: 13px;
}
.runtime-details dt {
	color: var(--cloud-muted);
}
.runtime-details dd {
	margin: 0;
	text-align: right;
	word-break: break-word;
}
.runtime-note {
	margin-top: 14px;
}
.quick-links {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
	padding-top: 10px;
}
.quick-links a {
	padding: 6px 10px;
	border: 1px solid var(--cloud-line);
	border-radius: 6px;
	font-size: 12px;
}
.quick-links a:hover {
	background: #e8f1ec;
	text-decoration: none;
}
.workspace-filters {
	display: flex;
	align-items: flex-end;
	flex-wrap: wrap;
	gap: 12px;
	margin: 18px 0;
}
.filter-field {
	display: flex;
	flex-direction: column;
	gap: 7px;
	flex: 1 1 220px;
	max-width: 340px;
	color: var(--cloud-muted);
	font-size: 12px;
}
.status-filter {
	max-width: 160px;
	flex-basis: 130px;
}
.filter-actions {
	display: flex;
	padding-bottom: 0;
}
.workspace-error {
	margin-bottom: 14px;
}
.retry-row {
	padding: 8px 0;
}
.pagination-row {
	display: flex;
	justify-content: space-between;
	align-items: center;
	flex-wrap: wrap;
	gap: 12px;
	margin-top: 18px;
}
@media (max-width: 1000px) {
	.operations-grid {
		grid-template-columns: 1fr;
		gap: 0;
	}
}
@media (max-width: 640px) {
	.global-overview {
		padding: 0;
	}
	.heading {
		align-items: flex-start;
	}
	h1 {
		font-size: 24px;
	}
	.panel {
		padding: 16px;
	}
	.section-heading {
		align-items: flex-start;
		flex-wrap: wrap;
		gap: 8px;
	}
	.metrics {
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 10px;
	}
	.metric {
		padding: 14px;
	}
	.metric strong {
		font-size: 26px;
	}
	.metric-heading {
		top: 12px;
		right: 11px;
	}
	.metric-icon {
		width: 28px;
		height: 28px;
		border-radius: 7px;
	}
	.metric-icon svg {
		width: 15px;
		height: 15px;
	}
	.metric-label {
		padding-right: 30px;
		font-size: 11px;
	}
	.filter-field,
	.status-filter {
		max-width: none;
	}
	.trend-chart {
		gap: 6px;
	}
	.pagination-row :deep(.el-pagination) {
		flex-wrap: wrap;
		row-gap: 10px;
	}
}
@media (prefers-reduced-motion: reduce) {
	.metric {
		transition: none;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 全局总览
</route>
