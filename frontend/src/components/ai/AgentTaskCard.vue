<template>
	<article class="agent-task-card" :aria-busy="busy || confirming">
		<header>
			<div>
				<strong>{{ task.title }}</strong>
				<p class="task-summary">
					{{ summary.label }} · {{ summary.count }} 个文件
				</p>
			</div>
			<span class="task-status" :class="`status-${status.toLowerCase()}`">{{
				taskStatusLabel(status)
			}}</span>
		</header>
		<p v-if="summary.location" class="task-location">{{ summary.location }}</p>
		<p v-if="summary.target" class="task-target">{{ summary.target }}</p>
		<p v-if="status === 'PENDING'" class="task-expiry">
			{{ expiryLabel }} · 核对下面的步骤后再确认
		</p>
		<p class="task-progress" role="status">
			成功 {{ progress.succeeded }} · 失败 {{ progress.failed }} · 未执行
			{{ progress.remaining }}
		</p>
		<p v-if="status === 'RUNNING'" class="task-notice" role="status">
			后台处理中，状态会自动更新；离开页面不会取消任务。
		</p>
		<p v-if="warning" class="task-warning" role="alert">{{ warning }}</p>
		<p v-if="pollError" class="task-warning" role="alert">{{ pollError }}</p>
		<p v-if="retryCreated" class="task-notice" role="status">
			已生成失败项的新预览，请在<router-link to="/agent-tasks"
				>任务页</router-link
			>核对后确认。
		</p>
		<details class="task-details" :open="expanded" @toggle="onDetailsToggle">
			<summary>查看步骤与逐项结果（{{ summary.count }} 项）</summary>
			<ol class="task-steps">
				<li v-for="(step, index) in task.steps" :key="index">
					<strong>{{ taskStepLabel(step.action) }}</strong>
					<div>{{ step.storageKey }} · {{ step.sourcePath }}</div>
					<div
						v-if="step.action === 'MOVE_FILE' || step.action === 'RENAME_FILE'"
					>
						→ {{ step.targetPath }}
					</div>
					<div v-else-if="step.action === 'INDEX_MATERIAL'">
						→ 课程 #{{ step.courseId }} · 标签：{{ step.tags || '无' }}
					</div>
					<small>{{ stepResult(index) }}</small>
				</li>
			</ol>
		</details>
		<p v-if="status === 'PENDING'" class="task-safety">
			仅处理预览文件；不覆盖、不删除。失败即停止，已完成操作不自动回滚。
		</p>
		<details v-if="task.notice" class="task-boundaries">
			<summary>范围与执行规则</summary>
			<p class="task-notice">{{ task.notice }}</p>
		</details>
		<div class="task-actions">
			<el-button
				v-if="status === 'PENDING'"
				size="small"
				type="primary"
				:loading="busy"
				:disabled="confirming || !canConfirm"
				@click="confirm"
				>确认执行</el-button
			>
			<el-button
				v-if="status === 'PENDING'"
				size="small"
				:disabled="busy || confirming"
				@click="update('cancel')"
				>取消任务</el-button
			>
			<el-button size="small" :disabled="busy || confirming" @click="update()"
				>刷新状态</el-button
			>
			<el-button
				v-if="canRetry"
				size="small"
				:disabled="busy || confirming"
				@click="retryFailed"
				>重新预览失败项</el-button
			>
			<el-button
				v-if="allowRepeat && repeatContext"
				size="small"
				:disabled="busy || confirming"
				@click="repeat"
				>再建同类任务</el-button
			>
		</div>
	</article>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useEventBus } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentTaskRequest } from '~/api/home/agent-tasks'
import {
	taskStatus,
	taskStatusLabel,
	taskProgress,
	createTaskPoller,
} from '~/utils/agent-task.mjs'
import {
	deriveRepeatTask,
	taskCardSummary,
	taskCardWarning,
	taskStepLabel,
	completedFileChanges,
} from '~/utils/agent-task-card.mjs'

const props = defineProps({
	plan: { type: Object, required: true },
	allowRepeat: { type: Boolean, default: false },
})
const emit = defineEmits(['updated', 'created', 'repeat'])
const task = ref(props.plan)
const busy = ref(false)
const confirming = ref(false)
const expanded = ref(taskStatus(props.plan) === 'PENDING')
const now = ref(Date.now())
const pollError = ref('')
const retryCreated = ref(false)
const status = computed(() => {
	const value = taskStatus(task.value, now.value)
	return typeof value === 'string' ? value : 'UNKNOWN'
})
const progress = computed(() => taskProgress(task.value))
const summary = computed(() => taskCardSummary(task.value))
const warning = computed(() => taskCardWarning(task.value, status.value))
const repeatContext = computed(() => deriveRepeatTask(task.value, now.value))
const canConfirm = computed(
	() =>
		status.value === 'PENDING' &&
		Number.isFinite(task.value.expiresAt) &&
		task.value.expiresAt > now.value
)
const expiryLabel = computed(() =>
	Number.isFinite(task.value.expiresAt)
		? `确认截止 ${new Date(task.value.expiresAt).toLocaleString()}`
		: '确认时间不可用，请刷新状态'
)
const canRetry = computed(
	() =>
		['PARTIAL', 'FAILED'].includes(status.value) &&
		task.value.results?.filter((result) => result.success === false).length ===
			1
)
let disposed = false
let actionController
let pollingKey
let expiryTimer
const fileChanges = useEventBus('cloud-files-changed')
function acceptTask(value) {
	const changes = completedFileChanges(task.value, value)
	task.value = value
	now.value = Date.now()
	for (const change of changes) fileChanges.emit(change)
}
const poller = createTaskPoller({
	load: async (id, signal) =>
		(await agentTaskRequest('get', `/${id}`, undefined, { signal })).data,
	onUpdate: (value) => {
		if (disposed) return
		acceptTask(value)
		pollError.value = ''
		emit('updated', value)
	},
	onError: () => {
		if (disposed) return
		pollError.value = '自动刷新暂时失败，后台任务不会取消。请刷新状态。'
	},
})
function syncPolling() {
	if (disposed || busy.value) return
	const key = `${task.value.id}\n${status.value}`
	if (pollingKey === key) return
	pollingKey = key
	poller.start(task.value)
}
function pausePolling() {
	pollingKey = undefined
	poller.stop()
}
watch(
	() => props.plan,
	(value) => {
		if (value.id !== task.value.id) {
			actionController?.abort()
			actionController = undefined
			busy.value = false
			retryCreated.value = false
			expanded.value = taskStatus(value) === 'PENDING'
			pollError.value = ''
		}
		acceptTask(value)
	},
	{ immediate: true }
)
watch(() => `${task.value.id}\n${status.value}`, syncPolling, {
	immediate: true,
})
watch(
	() => `${task.value.id}\n${status.value}\n${task.value.expiresAt}`,
	() => {
		clearTimeout(expiryTimer)
		if (status.value !== 'PENDING') return
		expanded.value = true
		if (canConfirm.value)
			expiryTimer = setTimeout(() => {
				now.value = Date.now()
			}, task.value.expiresAt - now.value + 50)
	},
	{ immediate: true }
)
onBeforeUnmount(() => {
	disposed = true
	clearTimeout(expiryTimer)
	pausePolling()
	actionController?.abort()
})
function onDetailsToggle(event) {
	expanded.value = event.target.open
}
function stepResult(index) {
	const result = task.value.results?.find((item) => item.index === index)
	return result
		? `${result.success ? '成功' : '失败'}：${result.message}`
		: '尚未执行'
}
function repeat() {
	if (disposed || busy.value || confirming.value || !props.allowRepeat) return
	const context = deriveRepeatTask(task.value, Date.now())
	if (context) emit('repeat', context)
}
async function confirm() {
	if (disposed || busy.value || confirming.value || !canConfirm.value) return
	const id = task.value.id
	expanded.value = true
	confirming.value = true
	try {
		await ElMessageBox.confirm(
			`将执行上述 ${task.value.steps.length} 个步骤。失败后停止，已完成步骤不会自动回滚。确定执行？`,
			'确认自动化任务',
			{
				type: 'warning',
				confirmButtonText: '确认执行',
				cancelButtonText: '返回检查',
			}
		)
		if (disposed || task.value.id !== id) return
		now.value = Date.now()
		if (canConfirm.value) await update('confirm')
	} catch (error) {
		if (!disposed && error !== 'cancel' && error !== 'close')
			ElMessage.error('确认失败，请刷新任务状态')
	} finally {
		confirming.value = false
	}
}
async function update(action) {
	if (disposed || busy.value) return
	if (
		action &&
		(!['confirm', 'cancel'].includes(action) || status.value !== 'PENDING')
	)
		return
	const id = task.value.id
	const controller = new AbortController()
	busy.value = true
	pausePolling()
	actionController = controller
	try {
		const response = await agentTaskRequest(
			action ? 'post' : 'get',
			`/${id}${action ? '/' + action : ''}`,
			action === 'confirm' ? { confirmed: true } : undefined,
			{ signal: controller.signal }
		)
		if (disposed || controller.signal.aborted || task.value.id !== id) return
		acceptTask(response.data)
		pollError.value = ''
		emit('updated', task.value)
	} catch (error) {
		if (disposed || controller.signal.aborted || task.value.id !== id) return
		ElMessage.error(
			error?.data?.msg ||
				error?.response?.data?.msg ||
				'请求未完成。后台可能仍在执行，请刷新同一任务，不要重复创建。'
		)
	} finally {
		if (actionController === controller) {
			actionController = undefined
			busy.value = false
			syncPolling()
		}
	}
}
async function retryFailed() {
	if (disposed || busy.value || confirming.value || !canRetry.value) return
	const id = task.value.id
	const controller = new AbortController()
	busy.value = true
	actionController = controller
	try {
		const response = await agentTaskRequest('post', `/${id}/retry`, undefined, {
			signal: controller.signal,
		})
		if (disposed || controller.signal.aborted || task.value.id !== id) return
		retryCreated.value = true
		emit('created', response.data)
		ElMessage.success('已生成失败项的新预览；尚未执行')
	} catch (error) {
		if (disposed || controller.signal.aborted || task.value.id !== id) return
		ElMessage.error(
			error?.data?.msg ||
				error?.response?.data?.msg ||
				'未能确认新预览结果。请先刷新任务列表检查，再尝试'
		)
	} finally {
		if (actionController === controller) {
			actionController = undefined
			busy.value = false
		}
	}
}
</script>

<style scoped>
.agent-task-card {
	min-width: 0;
	margin: 12px 0;
	padding: 16px;
	border: 1px solid var(--line, #e0e5df);
	border-radius: 12px;
	background: var(--surface, #fff);
	color: var(--text, #243630);
	font-size: 13px;
	overflow-wrap: anywhere;
}
header {
	display: flex;
	gap: 12px;
	justify-content: space-between;
	align-items: flex-start;
	flex-wrap: wrap;
}
header > div {
	min-width: 0;
}
header strong {
	font-size: 14px;
}
.task-status {
	flex-shrink: 0;
	padding: 3px 8px;
	border-radius: 6px;
	color: #287563;
	background: #edf5f1;
	font-size: 12px;
}
.status-failed,
.status-partial,
.status-interrupted {
	color: #995536;
	background: #fff2e9;
}
.task-summary,
.task-location,
.task-target,
.task-expiry,
.task-notice,
.task-safety {
	margin: 6px 0;
	color: var(--muted, #63716b);
	line-height: 1.6;
}
.task-summary {
	font-size: 12px;
}
.task-progress {
	margin: 10px 0;
	font-variant-numeric: tabular-nums;
}
.task-warning {
	padding: 9px 11px;
	border-left: 3px solid #c78658;
	border-radius: 4px;
	color: #855335;
	background: #fff7ef;
	line-height: 1.7;
}
.task-details,
.task-boundaries {
	margin: 10px 0;
}
summary {
	width: fit-content;
	max-width: 100%;
	color: var(--primary, #287563);
	cursor: pointer;
	line-height: 1.8;
}
summary:focus-visible {
	outline: 2px solid #287563;
	outline-offset: 3px;
	border-radius: 3px;
}
.task-boundaries summary,
.task-safety {
	font-size: 12px;
}
.task-steps {
	padding-left: 22px;
	max-height: 280px;
	overflow: auto;
}
.task-steps li {
	margin: 10px 0;
	line-height: 1.6;
}
.task-steps small {
	color: var(--muted, #63716b);
}
.task-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	margin-top: 12px;
}
.task-actions :deep(.el-button + .el-button) {
	margin-left: 0;
}
</style>
