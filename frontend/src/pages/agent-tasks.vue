<template>
	<main class="agent-tasks-page">
		<header class="page-heading">
			<div>
				<p class="eyebrow">文件整理</p>
				<h1>自动化任务</h1>
				<p class="heading-description">
					选好目录，一次生成方案；确认后交给后台处理。
				</p>
			</div>
			<nav class="heading-links" aria-label="自动化任务快捷入口">
				<router-link to="/">返回云盘</router-link>
				<router-link to="/learning">课程资料库</router-link>
			</nav>
		</header>

		<section class="task-builder" :aria-busy="resources.sources.loading">
			<div class="section-heading">
				<div>
					<h2>你想做什么？</h2>
					<p>只处理选定目录，不递归子目录。</p>
				</div>
				<span class="preview-badge">先预览，再执行</span>
			</div>
			<div class="task-choices" role="group" aria-label="选择任务">
				<button
					v-for="option in taskOptions"
					:key="option.value"
					type="button"
					class="task-choice"
					:class="{ selected: form.type === option.value }"
					:aria-pressed="form.type === option.value"
					@click="form.type = option.value"
				>
					<span class="choice-symbol" aria-hidden="true">
						<svg v-if="option.icon === 'folder'" viewBox="0 0 24 24">
							<path
								d="M3 7V5a1 1 0 0 1 1-1h6l2 3h8a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7Z"
							/>
							<path d="M3 8h18" />
						</svg>
						<svg v-else-if="option.icon === 'calendar'" viewBox="0 0 24 24">
							<rect x="3" y="5" width="18" height="16" rx="2" />
							<path d="M7 3v4M17 3v4M3 11h18M7 15h4M7 18h7" />
						</svg>
						<svg v-else viewBox="0 0 24 24">
							<path
								d="M12 6c-3-3-7-3-10-2v15c4-1 7-1 10 2 3-3 6-3 10-2V4c-3-1-7-1-10 2ZM12 6v15"
							/>
						</svg>
					</span>
					<strong>{{ option.name }}</strong
					><span>{{ option.description }}</span>
				</button>
			</div>
			<p class="safety-note">
				仅支持本地云盘，不删除、不覆盖文件。生成方案无需配置模型。
			</p>
			<div v-if="resources.sources.error" class="inline-error" role="alert">
				<span>云盘加载失败，不影响查看已有任务。</span
				><el-button size="small" @click="loadSources">重新加载</el-button>
			</div>
			<p
				v-else-if="resources.sources.loading"
				class="resource-note"
				role="status"
			>
				正在读取可用云盘…
			</p>
			<p v-else-if="!sources.length" class="resource-note">
				暂无可用本地云盘，请先添加存储源或联系空间管理员。
			</p>
			<p v-if="contextNotice" class="context-note" role="status">
				{{ contextNotice }}
			</p>
			<el-form class="task-form" label-position="top" @submit.prevent="preview">
				<el-form-item label="处理哪个云盘？">
					<el-select
						v-model="form.storageKey"
						placeholder="选择本地云盘"
						:disabled="resources.sources.loading"
					>
						<el-option
							v-for="source in sources"
							:key="source.key"
							:value="source.key"
							:label="source.name"
						/>
					</el-select>
				</el-form-item>
				<el-form-item label="处理哪个目录？">
					<el-input
						v-model="form.path"
						maxlength="500"
						placeholder="例如 /学习资料"
					/>
				</el-form-item>
				<template v-if="form.type === 'IMPORT_LEARNING'">
					<el-form-item label="归档到课程">
						<el-select
							v-model="form.courseId"
							:loading="resources.courses.loading"
							placeholder="选择本人课程"
						>
							<el-option
								v-for="course in courses"
								:key="course.id"
								:value="course.id"
								:label="
									course.name + (course.semester ? ' · ' + course.semester : '')
								"
							/>
						</el-select>
						<div
							v-if="resources.courses.error"
							class="field-error"
							role="alert"
						>
							课程加载失败。<button type="button" @click="loadCourses(true)">
								重试
							</button>
						</div>
						<p
							v-else-if="resources.courses.loaded && !courses.length"
							class="field-note"
						>
							尚无课程，先去<router-link to="/learning">创建课程</router-link>。
						</p>
					</el-form-item>
					<el-form-item label="标签（选填）"
						><el-input
							v-model="form.tags"
							maxlength="200"
							placeholder="例如：期末复习, 数据库"
					/></el-form-item>
				</template>
				<div class="target-summary full-width" aria-label="当前任务目标">
					<span>{{ taskName }}</span
					><strong>{{ selectedSource?.name || '尚未选择云盘' }}</strong
					><code>{{ normalizedPath || '请输入有效目录' }}</code>
					<span v-if="form.type === 'IMPORT_LEARNING'"
						>→ {{ selectedCourse?.name || '尚未选择课程' }}</span
					>
					<small v-if="normalizedPath === '/'"
						>当前选择根目录，生成预览前请确认这是你要处理的位置。</small
					>
				</div>
				<div class="task-actions">
					<el-button
						native-type="submit"
						type="primary"
						:loading="busy"
						:disabled="!validForm || prefilling"
						>查看整理方案</el-button
					>
					<el-button
						:disabled="busy || !selectedSource || !normalizedPath || prefilling"
						@click="checkDuplicates"
						>只检查重复文件</el-button
					>
				</div>
			</el-form>
			<p v-if="actionError" class="inline-error" role="alert">
				{{ actionError }}
			</p>
		</section>

		<section v-if="duplicateReport" class="duplicate-report">
			<div class="section-heading">
				<h2>重复文件检查</h2>
				<el-button text @click="duplicateReport = null">收起结果</el-button>
			</div>
			<p>
				{{ duplicateTarget }} · 已比对
				{{ duplicateReport.checkedFiles }} 个文件，{{
					duplicateReport.skippedFiles
				}}
				个因大小或检查上限跳过。只报告，不删除。
			</p>
			<el-empty
				v-if="!duplicateReport.groups?.length"
				description="未发现内容相同的文件"
			/>
			<ul v-else>
				<li v-for="(group, index) in duplicateReport.groups" :key="index">
					{{ group.paths.join('、') }}（每个 {{ group.size }} 字节）
				</li>
			</ul>
		</section>

		<section class="recent-tasks" :aria-busy="resources.tasks.loading">
			<div class="section-heading">
				<h2>最近任务</h2>
				<el-button :loading="resources.tasks.loading" @click="refreshTasks"
					>刷新任务</el-button
				>
			</div>
			<div class="task-tabs" role="tablist" aria-label="任务状态">
				<button
					v-for="tab in taskTabs"
					:id="'task-tab-' + tab.value"
					:key="tab.value"
					type="button"
					role="tab"
					:aria-selected="taskTab === tab.value"
					aria-controls="task-panel"
					@click="taskTab = tab.value"
				>
					{{ tab.label }} <span>{{ groupedTasks[tab.value].length }}</span>
				</button>
			</div>
			<div v-if="resources.tasks.error" class="inline-error" role="alert">
				<span>任务加载失败，请重试。</span
				><el-button size="small" @click="refreshTasks">重新加载任务</el-button>
			</div>
			<p
				v-if="resources.tasks.loading && !plans.length"
				class="resource-note"
				role="status"
			>
				正在读取任务…
			</p>
			<div
				id="task-panel"
				role="tabpanel"
				:aria-labelledby="'task-tab-' + taskTab"
			>
				<el-empty
					v-if="
						!resources.tasks.loading &&
						!resources.tasks.error &&
						!visiblePlans.length
					"
					:description="emptyDescription"
				/>
				<AgentTaskCard
					v-for="plan in visiblePlans"
					:key="plan.id"
					:plan="plan"
					:allow-repeat="true"
					@updated="replacePlan"
					@created="addPlan"
					@repeat="repeatPlan"
				/>
			</div>
			<p class="resource-note">
				保留最近 30 条记录。重复使用方案会重新预览，不会直接执行旧任务。
			</p>
		</section>

		<details class="schedule-section" @toggle="scheduleToggle">
			<summary>
				每周自动生成方案<span>{{
					resources.schedules.loaded
						? schedules.length + ' 条规则'
						: '按需开启，文件改动仍由你确认'
				}}</span>
			</summary>
			<p>
				沿用上方选定的操作、云盘和目录。到点生成待确认方案，不会直接整理文件。
			</p>
			<div class="target-summary">
				<span>{{ taskName }}</span
				><strong>{{ selectedSource?.name || '尚未选择云盘' }}</strong
				><code>{{ normalizedPath || '尚未选择目录' }}</code
				><span v-if="form.type === 'IMPORT_LEARNING'"
					>→ {{ selectedCourse?.name || '尚未选择课程' }}</span
				>
			</div>
			<el-form
				class="task-form"
				label-position="top"
				@submit.prevent="createSchedule"
			>
				<el-form-item class="full-width" label="规则名称"
					><el-input
						v-model="scheduleForm.title"
						maxlength="100"
						@input="scheduleTitleEdited = true"
				/></el-form-item>
				<div class="schedule-time">
					<el-form-item label="星期"
						><el-select v-model="scheduleForm.dayOfWeek"
							><el-option
								v-for="(day, index) in weekdays"
								:key="day"
								:label="day"
								:value="index + 1" /></el-select
					></el-form-item>
					<el-form-item label="小时（北京时间）"
						><el-input-number
							v-model="scheduleForm.hourOfDay"
							:min="0"
							:max="23"
					/></el-form-item>
					<el-form-item label="分钟"
						><el-input-number
							v-model="scheduleForm.minuteOfHour"
							:min="0"
							:max="59"
					/></el-form-item>
				</div>
				<div class="task-actions">
					<el-button
						native-type="submit"
						:loading="busy"
						:disabled="!validForm || !scheduleForm.title.trim() || prefilling"
						>创建每周规则</el-button
					>
				</div>
			</el-form>
			<el-button
				v-if="resources.schedules.loaded"
				text
				:loading="resources.schedules.loading"
				@click="loadSchedules(true)"
				>刷新规则</el-button
			>
			<div v-if="resources.schedules.error" class="inline-error" role="alert">
				<span>规则加载失败，已创建的规则不会因此停止。</span
				><el-button size="small" @click="loadSchedules(true)"
					>重新加载规则</el-button
				>
			</div>
			<p
				v-else-if="resources.schedules.loading"
				class="resource-note"
				role="status"
			>
				正在读取规则…
			</p>
			<el-empty v-else-if="!schedules.length" description="暂无每周规则" />
			<ul v-else class="schedule-list">
				<li v-for="schedule in schedules" :key="schedule.id">
					<div class="schedule-heading">
						<strong>{{ schedule.title }}</strong
						><span
							class="schedule-status"
							:class="{ paused: !schedule.enabled }"
							>{{ schedule.enabled ? '已开启' : '已暂停' }}</span
						>
					</div>
					<div>
						{{ weekdays[schedule.dayOfWeek - 1] }}
						{{ String(schedule.hourOfDay).padStart(2, '0') }}:{{
							String(schedule.minuteOfHour).padStart(2, '0')
						}}
						· 北京时间
					</div>
					<div class="schedule-meta">
						{{ sourceName(schedule.storageKey) }} · {{ schedule.directoryPath }}
					</div>
					<div v-if="schedule.lastRunAt">
						上次：{{ beijingTime(schedule.lastRunAt)
						}}{{
							schedule.lastError
								? ' · ' + schedule.lastError
								: ' · ' + schedulePreviewStatus(schedule)
						}}
					</div>
					<div>下次：{{ beijingTime(schedule.nextRunAt) }}</div>
					<div v-if="schedule.retryAt">
						重试预览：{{ beijingTime(schedule.retryAt) }} ·
						{{ schedule.retryCount || 0 }} / 2
					</div>
					<el-button
						size="small"
						:disabled="busy || schedule.previewState === 'GENERATING'"
						@click="previewSchedule(schedule)"
					>
						立即生成预览
					</el-button>
					<el-button
						size="small"
						:disabled="busy"
						@click="toggleSchedule(schedule)"
						>{{ schedule.enabled ? '暂停' : '恢复' }}</el-button
					>
				</li>
			</ul>
		</details>
	</main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import AgentTaskCard from '~/components/ai/AgentTaskCard.vue'
import { agentTaskRequest } from '~/api/home/agent-tasks'
import { agentScheduleRequest } from '~/api/home/agent-schedules'
import { schedulePreviewStatus } from '~/utils/agent-schedule.mjs'
import { learningRequest, learningStorageList } from '~/api/home/learning'
import {
	normalizeAutomationDirectory,
	parseAutomationContext,
} from '~/utils/automation-context.mjs'
import {
	taskOptions,
	localSources,
	targetFromContext,
	authorizedTaskForm,
	groupTasks,
	suggestedScheduleTitle,
	createWorkbenchResources,
} from '~/utils/agent-workbench.mjs'

const route = useRoute()
const sources = ref([])
const courses = ref([])
const plans = ref([])
const schedules = ref([])
const resourceState = () => ({ loading: false, loaded: false, error: null })
const resources = reactive({
	sources: resourceState(),
	tasks: resourceState(),
	courses: resourceState(),
	schedules: resourceState(),
})
const form = reactive({
	type: 'ORGANIZE_FILES',
	storageKey: '',
	path: '/',
	courseId: null,
	tags: '',
})
const weekdays = [
	'星期一',
	'星期二',
	'星期三',
	'星期四',
	'星期五',
	'星期六',
	'星期日',
]
const scheduleForm = reactive({
	title: '',
	dayOfWeek: 7,
	hourOfDay: 21,
	minuteOfHour: 0,
})
const scheduleTitleEdited = ref(false)
const contextNotice = ref('')
const duplicateReport = ref(null)
const duplicateTarget = ref('')
const busy = ref(false)
const prefilling = ref(false)
const actionError = ref('')
const taskTab = ref('pending')
const taskTabs = [
	{ value: 'pending', label: '待确认' },
	{ value: 'running', label: '处理中' },
	{ value: 'history', label: '历史' },
]
const currentTime = ref(Date.now())
let disposed = false
let contextApplied = false
let repeatGeneration = 0
let actionController
let expiryTimer
const selectedSource = computed(() =>
	sources.value.find((item) => String(item.key) === String(form.storageKey))
)
const selectedCourse = computed(() =>
	courses.value.find((item) => String(item.id) === String(form.courseId))
)
const normalizedPath = computed(() => normalizeAutomationDirectory(form.path))
const validForm = computed(() =>
	authorizedTaskForm(
		form,
		sources.value,
		courses.value,
		normalizeAutomationDirectory
	)
)
const taskName = computed(
	() => taskOptions.find((option) => option.value === form.type)?.name || '整理'
)
const groupedTasks = computed(() => groupTasks(plans.value, currentTime.value))
const visiblePlans = computed(() => groupedTasks.value[taskTab.value])
const emptyDescription = computed(
	() =>
		({
			pending: '暂无待确认方案，先选择目录生成预览',
			running: '没有正在处理的任务',
			history: '暂无已完成或结束的任务',
		}[taskTab.value])
)

const loader = createWorkbenchResources({
	requests: {
		sources: async (config) =>
			localSources(
				(await learningStorageList({ showDefaultMsg: false, ...config })).data
			),
		tasks: async (config) =>
			(await agentTaskRequest('get', '', undefined, config)).data,
		courses: async (config) =>
			(await learningRequest('get', '/courses', undefined, config)).data,
		schedules: async (config) =>
			(await agentScheduleRequest('get', '', undefined, config)).data,
	},
	onChange(name, state) {
		resources[name] = state
		if (!state.loaded || state.loading || state.error) return
		const list = Array.isArray(state.value) ? state.value : []
		if (name === 'sources') {
			sources.value = list
			if (!contextApplied) applyRouteContext()
			else if (!selectedSource.value) form.storageKey = ''
		} else if (name === 'courses') {
			courses.value = list.filter(
				(course) =>
					Number.isInteger(Number(course?.id)) && Number(course.id) > 0
			)
			if (!selectedCourse.value)
				form.courseId = courses.value.length === 1 ? courses.value[0].id : null
		} else if (name === 'tasks') plans.value = list.slice(0, 30)
		else if (name === 'schedules') schedules.value = list
	},
})
const loadSources = () => loader.load('sources', { force: true })
const refreshTasks = () => loader.refreshTasks()
const loadCourses = (force = false) => loader.load('courses', { force })
const loadSchedules = (force = false) => loader.load('schedules', { force })

function applyRouteContext() {
	const context = parseAutomationContext(route.query)
	const sourceRequested = Object.hasOwn(route.query, 'storageKey')
	const pathRequested = Object.hasOwn(route.query, 'path')
	const target = targetFromContext(context, sources.value, {
		sourceRequested,
		pathRequested,
	})
	Object.assign(form, {
		type: target.type,
		storageKey: target.storageKey,
		path: target.path,
		courseId: null,
		tags: '',
	})
	contextApplied = true
	contextNotice.value =
		pathRequested && !context.path
			? '传入的目录无效，请重新输入目录后生成方案。'
			: sourceRequested
			? target.matched
				? '已带入当前云盘和目录，请核对后生成方案。'
				: '指定云盘不可用或无权限，请重新选择云盘与目录。'
			: sources.value.length === 1
			? '已选中唯一可用本地云盘，请核对目录。'
			: ''
}
function replacePlan(plan) {
	plans.value = plans.value.map((item) => (item.id === plan.id ? plan : item))
}
function addPlan(plan) {
	plans.value = [
		plan,
		...plans.value.filter((item) => item.id !== plan.id),
	].slice(0, 30)
	currentTime.value = Date.now()
	taskTab.value = 'pending'
}
async function run(action, isMutation = true) {
	if (busy.value || disposed) return
	busy.value = true
	actionError.value = ''
	actionController = new AbortController()
	try {
		await action({ signal: actionController.signal, timeout: 15000 })
	} catch (error) {
		if (!disposed) {
			const detail =
				error?.data?.msg || error?.response?.data?.msg || '请求未完成。'
			actionError.value = isMutation
				? `${detail} 请先刷新任务或规则列表检查，再尝试；请勿重复提交。`
				: detail
		}
	} finally {
		if (!disposed) busy.value = false
	}
}
function checkedForm() {
	const target = validForm.value
	if (!target) throw new Error('请选择有效的云盘、目录与课程')
	return target
}
function preview() {
	const payload = validForm.value
	if (!payload) return
	return run(async (config) => {
		const response = await agentTaskRequest('post', '', payload, config)
		if (disposed) return
		addPlan(response.data)
		ElMessage.success('方案已生成，确认后才会执行')
	})
}
function checkDuplicates() {
	if (!selectedSource.value || !normalizedPath.value) return
	const target = selectedSource.value.name + ' · ' + normalizedPath.value
	const query = new URLSearchParams({
		storageKey: String(selectedSource.value.key),
		path: normalizedPath.value,
	})
	return run(async (config) => {
		const response = await agentTaskRequest(
			'get',
			'/duplicates?' + query.toString(),
			undefined,
			config
		)
		if (!disposed) {
			duplicateReport.value = response.data
			duplicateTarget.value = target
		}
	}, false)
}
function createSchedule() {
	if (!validForm.value || !scheduleForm.title.trim()) return
	const payload = {
		...checkedForm(),
		...scheduleForm,
		title: scheduleForm.title.trim(),
	}
	return run(async (config) => {
		const response = await agentScheduleRequest('post', '', payload, config)
		if (disposed) return
		schedules.value = [response.data, ...schedules.value]
		ElMessage.success('规则已开启；到点生成方案，文件改动仍需确认')
	})
}
function toggleSchedule(schedule) {
	return run(async (config) => {
		const response = await agentScheduleRequest(
			'post',
			'/' +
				encodeURIComponent(schedule.id) +
				'/enabled?value=' +
				!schedule.enabled,
			undefined,
			config
		)
		if (!disposed)
			schedules.value = schedules.value.map((item) =>
				item.id === schedule.id ? response.data : item
			)
	})
}
function previewSchedule(schedule) {
	return run(async (config) => {
		const response = await agentScheduleRequest(
			'post',
			'/' + encodeURIComponent(schedule.id) + '/preview',
			undefined,
			config
		)
		if (disposed) return
		schedules.value = schedules.value.map((item) =>
			item.id === schedule.id ? response.data.schedule : item
		)
		addPlan(response.data.task)
		ElMessage.success('预览已准备好，已有待确认方案会复用；文件改动仍需确认')
	})
}
function scheduleToggle(event) {
	if (event.target.open) loadSchedules()
}
async function repeatPlan(context) {
	const sequence = ++repeatGeneration
	if (
		!context ||
		!taskOptions.some((option) => option.value === context.type) ||
		!normalizeAutomationDirectory(context.path)
	)
		return
	prefilling.value = true
	try {
		if (context.type === 'IMPORT_LEARNING') await loadCourses()
		if (disposed || sequence !== repeatGeneration) return
		const payload = authorizedTaskForm(
			context,
			sources.value,
			courses.value,
			normalizeAutomationDirectory
		)
		if (!payload) {
			ElMessage.warning('旧方案的云盘或课程当前不可用，请重新选择')
			return
		}
		Object.assign(form, payload)
		contextNotice.value =
			'已填入旧方案的设置。请核对目录后重新生成预览，不会直接执行。'
		duplicateReport.value = null
		document.querySelector('.task-builder')?.scrollIntoView({
			behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
				? 'auto'
				: 'smooth',
			block: 'start',
		})
	} finally {
		if (!disposed && sequence === repeatGeneration) prefilling.value = false
	}
}
function sourceName(key) {
	return (
		sources.value.find((source) => String(source.key) === String(key))?.name ||
		key
	)
}
function beijingTime(value) {
	return value
		? new Date(value).toLocaleString('zh-CN', {
				timeZone: 'Asia/Shanghai',
				hour12: false,
		  })
		: '尚未安排'
}
watch(
	() => route.query,
	() => {
		repeatGeneration++
		prefilling.value = false
		if (resources.sources.loaded) applyRouteContext()
		else contextApplied = false
	}
)
watch(
	() => form.type,
	(type) => {
		if (type === 'IMPORT_LEARNING') loadCourses()
	}
)
watch(
	() => [form.type, form.path],
	() => {
		if (!scheduleTitleEdited.value)
			scheduleForm.title = suggestedScheduleTitle(form.type, form.path)
	},
	{ immediate: true }
)
function schedulePendingExpiry() {
	clearTimeout(expiryTimer)
	currentTime.value = Date.now()
	const next = plans.value
		.filter(
			(plan) => plan.status === 'PENDING' && plan.expiresAt > currentTime.value
		)
		.map((plan) => plan.expiresAt)
	if (next.length)
		expiryTimer = setTimeout(
			schedulePendingExpiry,
			Math.min(Math.min(...next) - currentTime.value + 50, 2147483647)
		)
}
watch(plans, schedulePendingExpiry)
onMounted(() => loader.initialize())
onBeforeUnmount(() => {
	disposed = true
	repeatGeneration++
	loader.dispose()
	actionController?.abort()
	clearTimeout(expiryTimer)
})
</script>

<style scoped>
.agent-tasks-page {
	max-width: 1100px;
	margin: auto;
	padding: 36px 24px 80px;
	color: var(--cloud-ink, #273936);
}
.page-heading {
	display: flex;
	gap: 20px;
	align-items: center;
	justify-content: space-between;
	margin-bottom: 24px;
}
h1 {
	font-size: 27px;
	font-weight: 650;
	margin: 4px 0 8px;
}
h2 {
	font-size: 17px;
	font-weight: 600;
	margin: 0;
}
.eyebrow {
	font-size: 12px;
	letter-spacing: 0.12em;
	color: var(--cloud-primary, #277566);
}
.heading-description {
	font-size: 13px;
	color: var(--cloud-muted, #6d7974);
	line-height: 1.7;
}
.heading-links {
	display: flex;
	gap: 9px;
	flex-wrap: wrap;
}
.heading-links a {
	padding: 10px 13px;
	border: 1px solid var(--cloud-line, #e1e6df);
	background: var(--cloud-surface, #fff);
	border-radius: 9px;
	font-size: 13px;
}
.heading-links a:hover {
	background: var(--cloud-soft, #e8f1ec);
}
.task-builder,
.schedule-section,
.recent-tasks,
.duplicate-report {
	padding: 24px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 12px;
	background: var(--cloud-surface, #fff);
	margin-bottom: 18px;
}
.section-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 12px;
}
.section-heading p {
	font-size: 12px;
	color: var(--cloud-muted, #6d7974);
	margin: 7px 0 0;
}
.preview-badge {
	flex-shrink: 0;
	font-size: 11px;
	padding: 5px 9px;
	border-radius: 6px;
	color: var(--cloud-primary-dark, #1e5e52);
	background: var(--cloud-soft, #e8f1ec);
}
.task-choices {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 12px;
	margin-top: 22px;
}
.task-choice {
	display: grid;
	gap: 8px;
	text-align: left;
	padding: 18px 16px;
	border-radius: 10px;
	border: 1px solid var(--cloud-line, #e1e6df);
	background: #fafbf8;
	cursor: pointer;
	transition: border-color 0.15s, background 0.15s;
	color: inherit;
}
.task-choice:hover {
	border-color: #9cafaa;
}
.task-choice.selected {
	border-color: var(--cloud-primary, #277566);
	background: var(--cloud-soft, #e8f1ec);
	box-shadow: inset 0 0 0 1px var(--cloud-primary, #277566);
}
.task-choice strong {
	font-size: 14px;
}
.task-choice > span:last-child {
	font-size: 12px;
	line-height: 1.5;
	color: var(--cloud-muted, #6d7974);
}
.choice-symbol {
	width: 29px;
	height: 29px;
	color: var(--cloud-primary, #277566);
}
.choice-symbol svg {
	width: 100%;
	height: 100%;
	fill: none;
	stroke: currentColor;
	stroke-width: 1.5;
	stroke-linecap: round;
	stroke-linejoin: round;
}
.safety-note {
	font-size: 12px;
	color: var(--cloud-muted, #6d7974);
	margin-top: 18px;
	padding-left: 10px;
	border-left: 2px solid var(--cloud-accent, #ba7657);
	line-height: 1.7;
}
.task-form {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	column-gap: 18px;
	max-width: 850px;
	margin: 20px 0 0;
}
.task-form :deep(.el-select) {
	width: 100%;
}
.task-form :deep(.el-form-item) {
	min-width: 0;
}
.full-width,
.task-actions,
.schedule-time {
	grid-column: 1 / -1;
}
.task-actions {
	display: flex;
	gap: 10px;
	flex-wrap: wrap;
}
.task-actions :deep(.el-button + .el-button) {
	margin-left: 0;
}
.target-summary {
	display: flex;
	align-items: center;
	gap: 8px 12px;
	flex-wrap: wrap;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 8px;
	padding: 12px 14px;
	margin: 0 0 18px;
	font-size: 12px;
	background: #f8faf6;
}
.target-summary > span {
	color: var(--cloud-muted, #6d7974);
}
.target-summary code {
	font-family: inherit;
	overflow-wrap: anywhere;
}
.target-summary small {
	flex-basis: 100%;
	color: #8a654b;
	line-height: 1.6;
}
.inline-error {
	display: flex;
	align-items: center;
	gap: 12px;
	flex-wrap: wrap;
	padding: 10px 12px;
	margin: 12px 0;
	background: #fff6ed;
	color: #8a5a3c;
	border-radius: 7px;
	font-size: 12px;
}
.resource-note,
.context-note,
.field-note {
	color: var(--cloud-muted, #6d7974);
	font-size: 12px;
	line-height: 1.8;
}
.context-note {
	color: var(--cloud-primary, #277566);
}
.field-note {
	margin: 5px 0 0;
}
.field-error {
	font-size: 12px;
	color: #996044;
}
.field-error button {
	color: var(--cloud-primary, #277566);
	text-decoration: underline;
	margin-left: 5px;
}
a {
	color: var(--cloud-primary, #277566);
}
.agent-tasks-page a:focus-visible,
.agent-tasks-page button:focus-visible,
.schedule-section summary:focus-visible {
	outline: 2px solid var(--cloud-primary, #277566);
	outline-offset: 3px;
}
.task-tabs {
	display: flex;
	gap: 6px;
	margin: 18px 0;
	padding-bottom: 8px;
	border-bottom: 1px solid var(--cloud-line, #e1e6df);
}
.task-tabs button {
	padding: 8px 12px;
	font-size: 13px;
	border-radius: 7px;
	color: var(--cloud-muted, #6d7974);
	cursor: pointer;
}
.task-tabs button[aria-selected='true'] {
	background: var(--cloud-soft, #e8f1ec);
	color: var(--cloud-primary-dark, #1e5e52);
	font-weight: 600;
}
.task-tabs span {
	margin-left: 5px;
	font-variant-numeric: tabular-nums;
	font-size: 11px;
}
.schedule-section summary {
	cursor: pointer;
	font-size: 16px;
	font-weight: 600;
}
.schedule-section summary::marker {
	color: var(--cloud-primary, #277566);
}
.schedule-section summary span {
	font-size: 12px;
	font-weight: 400;
	margin-left: 12px;
	color: var(--cloud-muted, #6d7974);
}
.schedule-section > p,
.duplicate-report > p {
	font-size: 12px;
	line-height: 1.8;
	color: var(--cloud-muted, #6d7974);
	margin-top: 16px;
}
.schedule-time {
	display: flex;
	gap: 16px;
	flex-wrap: wrap;
}
.schedule-time :deep(.el-form-item) {
	flex: 1 1 150px;
}
.schedule-time :deep(.el-input-number) {
	width: 100%;
}
.schedule-list {
	padding: 0;
	list-style: none;
	margin-top: 20px;
}
.schedule-list li {
	margin: 12px 0 0;
	padding: 16px;
	border-radius: 9px;
	border: 1px solid var(--cloud-line, #e1e6df);
	background: #f8faf6;
	font-size: 12px;
	line-height: 1.8;
	overflow-wrap: anywhere;
}
.schedule-list li > div:not(:first-child) {
	color: var(--cloud-muted, #6d7974);
}
.schedule-heading {
	display: flex;
	align-items: center;
	gap: 12px;
}
.schedule-list .el-button {
	margin-top: 10px;
}
.schedule-meta {
	display: flex;
	gap: 10px;
	flex-wrap: wrap;
	align-items: center;
}
.schedule-status {
	color: var(--cloud-primary, #277566);
	font-size: 10px;
	padding: 0 7px;
	background: var(--cloud-soft, #e8f1ec);
	border-radius: 4px;
}
.schedule-status.paused {
	background: #f0ece4;
	color: #8a7760;
}
.duplicate-report ul {
	padding-left: 20px;
	font-size: 13px;
	line-height: 1.8;
	overflow-wrap: anywhere;
}
@media (max-width: 640px) {
	.agent-tasks-page {
		padding: 22px 12px 70px;
	}
	.page-heading {
		align-items: flex-start;
		flex-direction: column;
		gap: 14px;
	}
	.task-builder,
	.schedule-section,
	.recent-tasks,
	.duplicate-report {
		padding: 18px 14px;
	}
	.task-form {
		grid-template-columns: minmax(0, 1fr);
	}
	.task-choices {
		grid-template-columns: minmax(0, 1fr);
		gap: 8px;
	}
	.task-choice {
		grid-template-columns: 29px 1fr;
		gap: 4px 12px;
		padding: 12px;
	}
	.task-choice .choice-symbol {
		grid-row: 1 / 3;
	}
	.task-choice > span:last-child {
		grid-column: 2;
	}
	.task-actions {
		flex-direction: column;
		align-items: stretch;
	}
	.section-heading {
		flex-wrap: wrap;
	}
	.task-tabs button {
		padding: 8px 10px;
	}
	.schedule-section summary span {
		display: block;
		margin: 6px 0 0 16px;
	}
}
@media (prefers-reduced-motion: reduce) {
	.task-choice {
		transition: none;
	}
}
</style>
