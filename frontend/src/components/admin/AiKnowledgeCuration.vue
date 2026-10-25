<template>
	<section class="knowledge-curation" aria-label="发布知识库">
		<h2>知识库整理</h2>
		<p>先核对并保存正确答案，再确认发布。AI 仅辅助润色，不会自动采信原回答。</p>
		<div class="curation-actions">
			<button type="button" :disabled="busy || !answer?.trim()" @click="refine">
				{{ operation === 'refine' ? '正在润色…' : 'AI 辅助润色' }}
			</button>
			<small>点击后仅将脱敏的问题与当前修订答案交给已配置的 AI 服务。</small>
		</div>
		<div v-if="preview !== null" class="curation-preview">
			<label
				>润色草稿 · 请人工复核<textarea
					v-model="preview"
					rows="6"
					maxlength="12000"
					:disabled="busy"
				/>
			</label>
			<button
				type="button"
				:disabled="busy || !preview.trim()"
				@click="applyPreview"
			>
				采用草稿，继续编辑
			</button>
			<small>采用后仍需点击“保存整理结果”；不会直接发布。</small>
		</div>
		<p v-if="error" class="curation-error" role="alert">{{ error }}</p>
		<p v-if="notice" class="curation-notice" role="status">{{ notice }}</p>
		<div class="publication-status">
			<span>{{
				loading ? '正在读取知识状态…' : state ? stateLabel : '知识状态暂不可用'
			}}</span>
			<button type="button" :disabled="busy || loading" @click="loadState">
				重新读取状态
			</button>
		</div>
		<label
			>发布范围<select v-model="scope" :disabled="busy">
				<template v-if="detail.courseId"
					><option value="COURSE">仅原反馈课程</option></template
				><template v-else
					><option value="TENANT">仅原反馈所属空间</option>
					<option value="GLOBAL">全站通用知识</option></template
				>
			</select></label
		>
		<p v-if="detail.courseId">
			课程反馈仅向仍有课程访问权限的成员开放，退出或移除后立即停止检索。
		</p>
		<label v-if="scope === 'GLOBAL'" class="global-confirm"
			><input
				v-model="confirmGlobal"
				type="checkbox"
				:disabled="busy"
			/>已核对：这是通用说明，不包含个人资料、私人文件内容或课程专属内容。</label
		>
		<p v-if="!savedCorrection">
			先将修订答案保存为“已整理”，重新打开后即可发布。
		</p>
		<div class="curation-actions">
			<button
				type="button"
				class="primary"
				:disabled="!canPublish"
				@click="publish"
			>
				确认发布到知识库
			</button>
			<button
				v-if="state?.state === 'PUBLISHED'"
				type="button"
				:disabled="busy"
				@click="withdraw"
			>
				撤回当前版本
			</button>
			<button type="button" :disabled="busy" @click="loadHistory">
				查看版本记录
			</button>
		</div>
		<ol v-if="history !== null" class="curation-history">
			<li v-for="revision in history" :key="revision.version">
				<strong
					>版本 {{ revision.version }} ·
					{{
						revision.scope === 'GLOBAL'
							? '全站'
							: revision.scope === 'COURSE'
							? '原课程'
							: '所属空间'
					}}</strong
				>
				<p>{{ revision.title }}</p>
				<details>
					<summary>查看已发布回答</summary>
					<pre>{{ revision.answer }}</pre>
				</details>
			</li>
			<li v-if="!history.length">暂无发布记录。</li>
		</ol>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useStorage } from '@vueuse/core'
import useStorageConfigStore from '~/stores/storage-config'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import {
	getAiKnowledgeStateReq,
	getAiKnowledgeHistoryReq,
	refineAiKnowledgeReq,
	publishAiKnowledgeReq,
	withdrawAiKnowledgeReq,
} from '~/api/admin/admin-ai-feedback'

const props = defineProps({
	detail: { type: Object, required: true },
	answer: { type: String, default: '' },
	savedStatus: { type: String, default: '' },
})
const emit = defineEmits(['apply'])
const store = useStorageConfigStore()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
const identity = computed(
	() =>
		`${store.loginInfo?.isLogin}:${store.loginInfo?.role}:${store.loginInfo?.username}:${credential.value}`
)
const allowed = computed(
	() => store.loginInfo?.isLogin === true && store.loginInfo?.role === 'ADMIN'
)
const state = ref(null),
	loading = ref(false),
	operation = ref(''),
	error = ref(''),
	notice = ref(''),
	preview = ref(null),
	scope = ref('TENANT'),
	confirmGlobal = ref(false),
	history = ref(null)
const busy = computed(() => operation.value !== '')
const savedCorrection = computed(
	() =>
		props.detail.status === 'RESOLVED' &&
		props.savedStatus === 'RESOLVED' &&
		!!props.detail.correctedAnswer?.trim() &&
		props.answer.trim() === props.detail.correctedAnswer.trim()
)
const canPublish = computed(
	() =>
		!!(
			allowed.value &&
			!busy.value &&
			!loading.value &&
			state.value &&
			savedCorrection.value &&
			(props.detail.courseId
				? scope.value === 'COURSE'
				: scope.value === 'TENANT' ||
				  (scope.value === 'GLOBAL' && confirmGlobal.value))
		)
)
const stateLabel = computed(
	() =>
		({
			PUBLISHED: `已发布 · 版本 ${state.value?.version}`,
			WITHDRAWN: `已撤回 · 版本 ${state.value?.version}`,
			UNPUBLISHED: '尚未发布',
		}[state.value?.state] || '状态未知')
)
const requests = createLatestRequest(),
	stateRequests = createLatestRequest()
let disposed = false
const context = () =>
	`${identity.value}:${props.detail.id}:${props.detail.updatedAt}`
const fresh = (request, bound) =>
	!disposed && allowed.value && context() === bound && request.isCurrent()
const validState = (value) =>
	value?.feedbackId === props.detail.id &&
	Number.isSafeInteger(value.version) &&
	value.version >= 0 &&
	['PUBLISHED', 'WITHDRAWN', 'UNPUBLISHED'].includes(value.state)
const fail = (e, fallback) => e?.response?.data?.msg || e?.data?.msg || fallback

async function loadState() {
	if (!allowed.value) return
	const request = stateRequests.begin(),
		bound = context()
	loading.value = true
	state.value = null
	try {
		const response = await getAiKnowledgeStateReq(props.detail.id, {
			signal: request.signal,
		})
		if (!fresh(request, bound)) return
		if (!validState(response.data)) throw new Error('Invalid state')
		state.value = response.data
	} catch (e) {
		if (fresh(request, bound))
			error.value = fail(e, '知识状态读取失败，请重新读取。')
	} finally {
		if (fresh(request, bound)) loading.value = false
	}
}
async function run(kind, action, accept) {
	if (busy.value || !allowed.value) return
	const request = requests.begin(),
		bound = context()
	operation.value = kind
	error.value = notice.value = ''
	try {
		const response = await action(request.signal)
		if (fresh(request, bound)) accept(response.data)
	} catch (e) {
		if (fresh(request, bound))
			error.value = fail(
				e,
				'操作结果未确认，请重新读取状态后核对，勿反复提交。'
			)
	} finally {
		if (fresh(request, bound)) operation.value = ''
	}
}
async function refine() {
	if (!props.answer.trim() || props.answer.length > 12000) return
	await run(
		'refine',
		(signal) =>
			refineAiKnowledgeReq(
				{
					feedbackId: props.detail.id,
					expectedUpdatedAt: props.detail.updatedAt,
					administratorAnswer: props.answer,
				},
				{ signal }
			),
		(data) => {
			if (
				typeof data?.answer !== 'string' ||
				!data.answer.trim() ||
				data.answer.length > 12000 ||
				data.requiresReview !== true ||
				data.sourceUpdatedAt !== props.detail.updatedAt
			)
				throw new Error('Invalid preview')
			preview.value = data.answer
		}
	)
}
function applyPreview() {
	if (busy.value || !allowed.value || !preview.value?.trim()) return
	emit('apply', preview.value)
	preview.value = null
	notice.value = '已采用为编辑草稿，请核对并保存。'
}
async function publish() {
	if (!canPublish.value) return
	await run(
		'publish',
		(signal) =>
			publishAiKnowledgeReq(
				{
					entries: [
						{
							feedbackId: props.detail.id,
							expectedUpdatedAt: props.detail.updatedAt,
							expectedVersion: state.value.version,
							title: props.detail.question.slice(0, 160),
							answer: props.answer.trim(),
							scope: scope.value,
							confirmGlobal: scope.value === 'GLOBAL' && confirmGlobal.value,
						},
					],
				},
				{ signal }
			),
		(data) => {
			if (!Array.isArray(data) || data.length !== 1 || !validState(data[0]))
				throw new Error('Invalid publication')
			state.value = data[0]
			history.value = null
			notice.value = '已发布，之后的相关问答可检索此条知识。'
		}
	)
}
async function withdraw() {
	if (state.value?.state !== 'PUBLISHED') return
	await run(
		'withdraw',
		(signal) =>
			withdrawAiKnowledgeReq(
				{ feedbackId: props.detail.id, expectedVersion: state.value.version },
				{ signal }
			),
		(data) => {
			if (!validState(data) || data.state !== 'WITHDRAWN')
				throw new Error('Invalid withdrawal')
			state.value = data
			notice.value = '已撤回，后续检索将不再使用此版本。'
		}
	)
}
async function loadHistory() {
	await run(
		'history',
		(signal) => getAiKnowledgeHistoryReq(props.detail.id, { signal }),
		(data) => {
			if (
				!Array.isArray(data) ||
				data.length > 50 ||
				!data.every(
					(row) =>
						row.feedbackId === props.detail.id &&
						Number.isSafeInteger(row.version) &&
						typeof row.answer === 'string'
				)
			)
				throw new Error('Invalid history')
			history.value = data
		}
	)
}
function clear() {
	requests.cancel()
	stateRequests.cancel()
	state.value = history.value = preview.value = null
	operation.value = error.value = notice.value = ''
	loading.value = false
	scope.value = props.detail.courseId ? 'COURSE' : 'TENANT'
	confirmGlobal.value = false
}
watch(
	() => [identity.value, props.detail.id, props.detail.updatedAt],
	() => {
		clear()
		if (allowed.value) loadState()
	},
	{ immediate: true, flush: 'sync' }
)
watch(scope, () => {
	confirmGlobal.value = false
})
onBeforeUnmount(() => {
	disposed = true
	clear()
})
</script>

<style scoped>
.knowledge-curation {
	margin-top: 24px;
	padding: 18px;
	border: 1px solid var(--cloud-line, #dedee3);
	border-radius: 12px;
	font-size: 12px;
	line-height: 1.7;
}
h2 {
	margin: 0 0 8px;
	font-size: 14px;
}
p,
small {
	color: var(--cloud-muted, #707078);
}
label {
	display: grid;
	gap: 8px;
	margin: 14px 0;
}
button,
select,
textarea {
	font: inherit;
	color: inherit;
	border: 1px solid var(--cloud-line, #dedee3);
	border-radius: 8px;
	background: var(--cloud-surface, #fff);
	padding: 8px 10px;
}
button {
	cursor: pointer;
}
button:disabled {
	opacity: 0.5;
	cursor: not-allowed;
}
textarea {
	width: 100%;
	resize: vertical;
}
.primary {
	color: #fff;
	background: var(--cloud-primary, #262629);
}
.curation-actions,
.publication-status {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 10px;
	margin: 12px 0;
}
.global-confirm {
	display: flex;
	align-items: flex-start;
	gap: 8px;
}
.curation-preview {
	padding: 12px;
	background: var(--cloud-soft, #f6f6f7);
	border-radius: 10px;
}
.curation-error {
	color: #a43232;
}
.curation-notice {
	color: #2b7161;
}
.curation-history {
	padding-left: 20px;
}
pre {
	font: inherit;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
}
:is(button, select, textarea, input, summary):focus-visible {
	outline: 2px solid var(--cloud-accent, #7955dd);
	outline-offset: 3px;
}
</style>
