<template>
	<section class="feedback-page" aria-label="AI 反馈整理">
		<header class="feedback-heading">
			<div>
				<h1>AI 反馈</h1>
				<p>核对成员提交的问题，整理可复用的正确答案。</p>
			</div>
			<button
				type="button"
				class="primary"
				:disabled="!allowed || exporting"
				@click="exportReviewed"
			>
				{{ exporting ? '正在导出…' : '导出已整理问答' }}
			</button>
		</header>
		<p class="feedback-boundary">
			反馈内容由成员主动提交，原 AI
			回答未经核验。保存和导出不会自动训练模型，也不会直接写入知识库。
		</p>
		<p v-if="!allowed" class="feedback-error" role="alert">
			仅全局管理员可以查看和整理 AI 反馈。
		</p>
		<template v-else>
			<div class="feedback-toolbar">
				<label
					>处理状态<select
						v-model="statusFilter"
						:disabled="saving"
						@change="loadList(1)"
					>
						<option value="OPEN">待整理</option>
						<option value="RESOLVED">已整理</option>
						<option value="DISMISSED">无需处理</option>
						<option value="">全部状态</option>
					</select></label
				><span>{{
					listing
						? '正在读取…'
						: pageData
						? `共 ${pageData.total} 条反馈`
						: '反馈数量暂不可用'
				}}</span
				><button
					type="button"
					:disabled="listing || saving"
					@click="loadList(page)"
				>
					刷新
				</button>
			</div>
			<p v-if="listError" class="feedback-error" role="alert">
				{{ listError }}
			</p>
			<p v-if="exportError" class="feedback-error" role="alert">
				{{ exportError }}
			</p>
			<p v-if="notice" class="feedback-notice" role="status">{{ notice }}</p>
			<div class="feedback-table-wrap" :aria-busy="listing">
				<table v-if="pageData?.items.length">
					<thead>
						<tr>
							<th>反馈问题</th>
							<th>提交成员</th>
							<th>提交时间</th>
							<th>状态</th>
							<th><span class="sr-only">操作</span></th>
						</tr>
					</thead>
					<tbody>
						<tr v-for="item in pageData.items" :key="item.id">
							<td class="question-cell">
								<span>{{ item.questionPreview }}</span>
							</td>
							<td>
								<strong>{{ item.nickname || item.accountId || '成员' }}</strong
								><small>{{ item.tenantName || '所属空间未提供' }}</small>
							</td>
							<td>{{ formatTime(item.createdAt) }}</td>
							<td>
								<span
									class="feedback-status"
									:class="`status-${item.status.toLowerCase()}`"
									>{{ statusLabel(item.status) }}</span
								>
							</td>
							<td>
								<button
									type="button"
									:disabled="saving"
									@click="openDetail(item.id)"
								>
									查看与整理
								</button>
							</td>
						</tr>
					</tbody>
				</table>
				<p v-else class="feedback-empty" role="status">
					{{
						listing
							? '正在读取反馈…'
							: listError
							? '暂时无法显示反馈，请点击刷新重试。'
							: '当前状态下暂无反馈。'
					}}
				</p>
			</div>
			<nav v-if="pageData" class="feedback-pagination" aria-label="AI反馈分页">
				<button
					type="button"
					:disabled="listing || saving || page <= 1"
					@click="loadList(page - 1)"
				>
					上一页</button
				><span
					>{{ page }} /
					{{ Math.max(1, Math.ceil(pageData.total / pageSize)) }}</span
				><button
					type="button"
					:disabled="listing || saving || page * pageSize >= pageData.total"
					@click="loadList(page + 1)"
				>
					下一页
				</button>
			</nav>
			<p class="export-note">
				导出最近整理的最多 500
				条问答，仅包含问题和修订答案，不附带账号、页面路径及管理备注。问答正文中的个人信息仍需人工检查。
			</p>
		</template>
		<el-drawer
			:model-value="!!selectedId"
			title="整理反馈"
			size="min(680px, 100vw)"
			append-to-body
			destroy-on-close
			class="feedback-detail-drawer"
			:z-index="2400"
			:before-close="closeDetail"
			:close-on-click-modal="false"
			:close-on-press-escape="!saving"
			:show-close="!saving"
		>
			<section class="feedback-detail" aria-label="反馈详情">
				<div class="feedback-detail-body">
					<p v-if="detailLoading" role="status">正在读取原始反馈…</p>
					<p v-if="detailError" class="feedback-error" role="alert">
						{{ detailError }}
					</p>
					<button
						v-if="detailError && !detail"
						type="button"
						@click="openDetail(selectedId)"
					>
						重新读取详情</button
					><template v-if="detail && draft"
						><p class="feedback-submitter">
							{{ detail.nickname || detail.accountId || '成员' }} ·
							{{ detail.tenantName || '所属空间未提供' }} ·
							{{ formatTime(detail.createdAt) }}
						</p>
						<h2>成员的问题</h2>
						<pre class="feedback-source-question">{{ detail.question }}</pre>
						<details class="feedback-original">
							<summary>原 AI 回答 · 待核验</summary>
							<pre>{{ detail.answer }}</pre>
						</details>
						<p v-if="detail.pagePath" class="feedback-path">
							提交页面：<code>{{ detail.pagePath }}</code>
						</p>
						<div class="feedback-edit">
							<label
								>处理结果<select v-model="draft.status" :disabled="saving">
									<option value="OPEN">待整理</option>
									<option value="RESOLVED">已整理</option>
									<option value="DISMISSED">无需处理</option>
								</select></label
							><label
								>修订后的正确答案<textarea
									v-model="draft.correctedAnswer"
									maxlength="12000"
									rows="8"
									:disabled="saving"
									placeholder="核对资料后填写，不会自动沿用原 AI 回答"
								/></label
							><small
								>{{ draft.correctedAnswer.length }}/12000 ·
								标记“已整理”时必须填写正确答案。</small
							><label
								>管理备注<textarea
									v-model="draft.note"
									maxlength="2000"
									rows="3"
									:disabled="saving"
									placeholder="可选，仅供管理员整理时参考，不随问答导出"
								/>
							</label>
							<p v-if="saveError" class="feedback-error" role="alert">
								{{ saveError }}
							</p>
						</div>
						<AiKnowledgeCuration
							:detail="detail"
							:answer="draft.correctedAnswer"
							:saved-status="draft.status"
							@apply="draft.correctedAnswer = $event"
						/>
					</template>
				</div>
				<footer>
					<small>只保存人工整理结果，不自动发布或训练。</small>
					<div>
						<button type="button" :disabled="saving" @click="closeDetail()">
							关闭</button
						><button
							v-if="draft"
							type="button"
							class="primary"
							:disabled="!canSave"
							@click="saveDetail"
						>
							{{ saving ? '正在保存…' : '保存整理结果' }}
						</button>
					</div>
				</footer>
			</section>
		</el-drawer>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useStorage } from '@vueuse/core'
import useStorageConfigStore from '~/stores/storage-config'
import AiKnowledgeCuration from '~/components/admin/AiKnowledgeCuration.vue'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import {
	listAiFeedbackReq,
	getAiFeedbackReq,
	saveAiFeedbackReq,
	exportAiFeedbackReq,
} from '~/api/admin/admin-ai-feedback'

const store = useStorageConfigStore()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
const allowed = computed(
	() => store.loginInfo?.isLogin === true && store.loginInfo?.role === 'ADMIN'
)
const identity = computed(
	() =>
		`${store.loginInfo?.isLogin}:${store.loginInfo?.role}:${store.loginInfo?.username}:${credential.value}`
)
const pageSize = 20
const page = ref(1),
	statusFilter = ref('OPEN'),
	pageData = ref(null)
const listing = ref(false),
	detailLoading = ref(false),
	saving = ref(false),
	exporting = ref(false)
const listError = ref(''),
	detailError = ref(''),
	saveError = ref(''),
	exportError = ref(''),
	notice = ref('')
const selectedId = ref(''),
	detail = ref(null),
	draft = ref(null)
const listRequests = createLatestRequest(),
	detailRequests = createLatestRequest(),
	saveRequests = createLatestRequest(),
	exportRequests = createLatestRequest()
let disposed = false
const states = new Set(['OPEN', 'RESOLVED', 'DISMISSED'])
const validId = (id) => typeof id === 'string' && /^[a-f0-9]{32}$/.test(id)
const statusLabel = (status) =>
	({ OPEN: '待整理', RESOLVED: '已整理', DISMISSED: '无需处理' }[status] ||
	'状态未知')
const errorText = (error, fallback) =>
	error?.response?.data?.msg || error?.data?.msg || fallback
const fresh = (request, boundIdentity) =>
	!disposed &&
	allowed.value &&
	identity.value === boundIdentity &&
	request.isCurrent()
const validDetail = (data, id) =>
	data?.id === id &&
	states.has(data.status) &&
	typeof data.question === 'string' &&
	typeof data.answer === 'string' &&
	(data.correctedAnswer == null || typeof data.correctedAnswer === 'string') &&
	(data.note == null || typeof data.note === 'string')
const canSave = computed(
	() =>
		allowed.value &&
		!!detail.value &&
		!!draft.value &&
		!detailLoading.value &&
		!saving.value &&
		states.has(draft.value.status) &&
		draft.value.correctedAnswer.length <= 12000 &&
		draft.value.note.length <= 2000 &&
		(draft.value.status !== 'RESOLVED' || !!draft.value.correctedAnswer.trim())
)
function formatTime(value) {
	const date = new Date(typeof value === 'number' ? value : Number(value))
	return Number.isFinite(date.getTime()) && Number(value) > 0
		? date.toLocaleString('zh-CN', { hour12: false })
		: '时间未知'
}

async function loadList(targetPage = 1) {
	if (
		!allowed.value ||
		!Number.isInteger(targetPage) ||
		targetPage < 1 ||
		targetPage > 10000 ||
		(statusFilter.value && !states.has(statusFilter.value))
	)
		return
	const request = listRequests.begin(),
		boundIdentity = identity.value,
		filter = statusFilter.value
	page.value = targetPage
	pageData.value = null
	listing.value = true
	listError.value = ''
	try {
		const response = await listAiFeedbackReq(
			{ page: targetPage, pageSize, status: filter },
			{ signal: request.signal }
		)
		if (!fresh(request, boundIdentity)) return
		const result = response.data
		if (
			!Array.isArray(result?.items) ||
			!Number.isSafeInteger(result.total) ||
			result.total < 0 ||
			result.page !== targetPage ||
			result.pageSize !== pageSize ||
			result.items.length > pageSize ||
			!result.items.every(
				(item) =>
					validId(item.id) &&
					states.has(item.status) &&
					typeof item.questionPreview === 'string'
			)
		)
			throw new Error('Invalid feedback list')
		const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
		if (targetPage > lastPage) {
			await loadList(lastPage)
			return
		}
		pageData.value = result
	} catch (error) {
		if (fresh(request, boundIdentity))
			listError.value = errorText(error, '反馈读取失败，请点击刷新重试。')
	} finally {
		if (fresh(request, boundIdentity)) listing.value = false
	}
}

async function openDetail(id) {
	if (!allowed.value || saving.value || !validId(id)) return
	const request = detailRequests.begin(),
		boundIdentity = identity.value
	selectedId.value = id
	detail.value = draft.value = null
	detailError.value = saveError.value = ''
	detailLoading.value = true
	try {
		const response = await getAiFeedbackReq(id, { signal: request.signal })
		if (!fresh(request, boundIdentity) || selectedId.value !== id) return
		if (!validDetail(response.data, id))
			throw new Error('Invalid feedback detail')
		detail.value = response.data
		draft.value = {
			status: response.data.status,
			correctedAnswer: response.data.correctedAnswer || '',
			note: response.data.note || '',
		}
	} catch (error) {
		if (fresh(request, boundIdentity))
			detailError.value = errorText(error, '详情读取失败，请重新读取。')
	} finally {
		if (fresh(request, boundIdentity)) detailLoading.value = false
	}
}
function closeDetail(done) {
	if (saving.value) return
	detailRequests.cancel()
	selectedId.value = ''
	detail.value = draft.value = null
	detailLoading.value = false
	detailError.value = saveError.value = ''
	if (typeof done === 'function') done()
}
async function saveDetail() {
	if (!canSave.value || !validId(selectedId.value)) return
	const id = selectedId.value,
		boundIdentity = identity.value,
		request = saveRequests.begin()
	const payload = {
		status: draft.value.status,
		correctedAnswer: draft.value.correctedAnswer,
		note: draft.value.note,
	}
	saving.value = true
	saveError.value = ''
	try {
		const response = await saveAiFeedbackReq(id, payload, {
			signal: request.signal,
		})
		if (!fresh(request, boundIdentity) || selectedId.value !== id) return
		if (!validDetail(response.data, id))
			throw new Error('Invalid feedback acknowledgement')
		saving.value = false
		notice.value = '整理结果已保存。'
		closeDetail()
		await loadList(page.value)
	} catch (error) {
		if (fresh(request, boundIdentity))
			saveError.value = errorText(
				error,
				'保存结果未确认，草稿已保留。请关闭后重新读取核对，勿反复提交。'
			)
	} finally {
		if (fresh(request, boundIdentity)) saving.value = false
	}
}
async function exportReviewed() {
	if (!allowed.value || exporting.value) return
	const request = exportRequests.begin(),
		boundIdentity = identity.value
	exporting.value = true
	exportError.value = notice.value = ''
	try {
		const response = await exportAiFeedbackReq({ signal: request.signal })
		if (!fresh(request, boundIdentity)) return
		if (
			!Array.isArray(response.data) ||
			response.data.length > 500 ||
			!response.data.every(
				(row) =>
					typeof row.question === 'string' &&
					typeof row.answer === 'string' &&
					row.answer.trim()
			)
		)
			throw new Error('Invalid feedback export')
		if (!response.data.length) {
			notice.value = '暂无已整理的问答可导出。'
			return
		}
		const rows = response.data.map(({ question, answer }) =>
			JSON.stringify({ question, answer })
		)
		const url = URL.createObjectURL(
			new Blob([rows.join('\n') + '\n'], {
				type: 'application/x-ndjson;charset=utf-8',
			})
		)
		const link = document.createElement('a')
		try {
			link.href = url
			link.download = 'ai-reviewed-feedback.jsonl'
			document.body.append(link)
			link.click()
		} finally {
			link.remove()
			URL.revokeObjectURL(url)
		}
		notice.value = `已导出 ${rows.length} 条人工整理问答，请核查内容后再使用。`
	} catch (error) {
		if (fresh(request, boundIdentity))
			exportError.value = errorText(error, '导出失败，请重试。')
	} finally {
		if (fresh(request, boundIdentity)) exporting.value = false
	}
}

function clearPrivateState() {
	listRequests.cancel()
	detailRequests.cancel()
	saveRequests.cancel()
	exportRequests.cancel()
	pageData.value = detail.value = draft.value = null
	selectedId.value = ''
	listError.value =
		detailError.value =
		saveError.value =
		exportError.value =
		notice.value =
			''
	listing.value = detailLoading.value = saving.value = exporting.value = false
}
watch(
	identity,
	() => {
		clearPrivateState()
		page.value = 1
		statusFilter.value = 'OPEN'
		if (allowed.value) loadList()
	},
	{ immediate: true, flush: 'sync' }
)
onBeforeUnmount(() => {
	disposed = true
	clearPrivateState()
})
</script>

<style scoped>
.feedback-page {
	padding: 24px;
	color: var(--cloud-ink, #27272b);
}
.feedback-heading,
.feedback-toolbar,
.feedback-pagination,
.feedback-detail footer,
.feedback-detail footer > div {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
}
h1 {
	margin: 0 0 7px;
	font-size: 22px;
	font-weight: 650;
}
.feedback-heading p,
.feedback-boundary,
.export-note,
.feedback-submitter,
.feedback-path,
footer small {
	color: var(--cloud-muted, #707078);
	font-size: 12px;
	line-height: 1.7;
}
.feedback-heading p {
	margin: 0;
}
.feedback-boundary {
	margin: 20px 0;
	padding: 12px 14px;
	background: var(--cloud-soft, #f6f6f7);
	border-radius: 10px;
}
.feedback-toolbar {
	justify-content: flex-start;
	margin: 20px 0 14px;
}
.feedback-toolbar label {
	display: flex;
	align-items: center;
	gap: 10px;
	font-size: 13px;
}
.feedback-toolbar > span {
	margin-left: auto;
	font-size: 12px;
	color: var(--cloud-muted, #707078);
}
button,
select,
textarea {
	font: inherit;
	font-size: 12px;
	color: inherit;
	border: 1px solid var(--cloud-line, #dedee3);
	border-radius: 8px;
	background: var(--cloud-surface, #fff);
}
button {
	padding: 8px 12px;
	cursor: pointer;
	white-space: nowrap;
}
select {
	padding: 8px 30px 8px 10px;
}
textarea {
	width: 100%;
	min-height: 80px;
	padding: 11px;
	line-height: 1.7;
	resize: vertical;
}
button:disabled {
	opacity: 0.5;
	cursor: not-allowed;
}
button:focus-visible,
select:focus-visible,
textarea:focus-visible,
summary:focus-visible {
	outline: 2px solid var(--cloud-accent, #7955dd);
	outline-offset: 3px;
}
button:not(:disabled):hover {
	border-color: var(--cloud-muted, #707078);
}
.primary {
	color: white;
	background: var(--cloud-primary, #262629);
	border-color: var(--cloud-primary, #262629);
}
.feedback-table-wrap {
	overflow-x: auto;
	border: 1px solid var(--cloud-line, #dedee3);
	border-radius: 12px;
}
table {
	width: 100%;
	border-collapse: collapse;
	text-align: left;
	font-size: 12px;
}
th {
	color: var(--cloud-muted, #707078);
	background: var(--cloud-soft, #f6f6f7);
	font-weight: 500;
	white-space: nowrap;
}
th,
td {
	padding: 14px 16px;
	border-bottom: 1px solid var(--cloud-line, #e7e7eb);
}
tbody tr:last-child td {
	border-bottom: 0;
}
td {
	vertical-align: middle;
}
td strong {
	display: block;
	font-weight: 500;
}
td small {
	display: block;
	color: var(--cloud-muted, #707078);
	margin-top: 4px;
}
.question-cell {
	min-width: 220px;
	width: 40%;
}
.question-cell > span {
	display: -webkit-box;
	-webkit-line-clamp: 2;
	-webkit-box-orient: vertical;
	overflow: hidden;
	line-height: 1.7;
	overflow-wrap: anywhere;
}
.feedback-status {
	display: inline-block;
	padding: 3px 7px;
	border-radius: 6px;
	color: var(--cloud-muted, #707078);
	background: var(--cloud-soft, #f6f6f7);
	white-space: nowrap;
}
.status-open {
	color: #8c642a;
	background: #fff6e8;
}
.status-resolved {
	color: #2b7161;
	background: #edf7f2;
}
.feedback-empty {
	padding: 34px 16px;
	margin: 0;
	text-align: center;
	color: var(--cloud-muted, #707078);
	font-size: 13px;
}
.feedback-pagination {
	justify-content: flex-end;
	margin-top: 16px;
	font-size: 12px;
}
.feedback-error,
.feedback-notice {
	padding: 10px 12px;
	border-radius: 8px;
	font-size: 12px;
	line-height: 1.7;
}
.feedback-error {
	color: #a43232;
	background: #fff1f0;
}
.feedback-notice {
	color: #2b7161;
	background: #edf7f2;
}
.export-note {
	margin-top: 16px;
}
.feedback-detail {
	height: 100%;
	min-height: 0;
	display: flex;
	flex-direction: column;
}
.feedback-detail-body {
	flex: 1;
	min-height: 0;
	overflow-y: auto;
	padding: 20px 24px;
}
.feedback-detail h2 {
	margin: 20px 0 10px;
	font-size: 14px;
	font-weight: 600;
}
.feedback-detail pre {
	font-family: inherit;
	font-size: 13px;
	line-height: 1.8;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
	margin: 0;
}
.feedback-source-question {
	padding: 12px 14px;
	border-radius: 9px;
	background: var(--cloud-soft, #f6f6f7);
}
.feedback-original {
	margin: 18px 0;
	border-bottom: 1px solid var(--cloud-line, #dedee3);
	padding-bottom: 16px;
}
.feedback-original summary {
	cursor: pointer;
	font-size: 12px;
	color: var(--cloud-muted, #707078);
}
.feedback-original pre {
	margin-top: 12px;
}
.feedback-edit {
	display: grid;
	gap: 14px;
	margin-top: 24px;
}
.feedback-edit label {
	display: grid;
	gap: 8px;
	font-size: 13px;
}
.feedback-edit small {
	font-size: 11px;
	color: var(--cloud-muted, #707078);
}
.feedback-detail footer {
	flex-shrink: 0;
	padding: 16px 24px;
	border-top: 1px solid var(--cloud-line, #dedee3);
}
.feedback-detail footer small {
	max-width: 44%;
}
.feedback-detail :is(h2, pre, p, code) {
	overflow-wrap: anywhere;
}
:global(.feedback-detail-drawer .el-drawer__body) {
	padding: 0;
	display: flex;
	min-height: 0;
}
:global(.feedback-detail-drawer .el-drawer__header) {
	margin: 0;
	padding: 20px 24px;
	border-bottom: 1px solid var(--cloud-line, #dedee3);
}
@media (max-width: 640px) {
	.feedback-page {
		padding: 16px;
	}
	.feedback-heading {
		align-items: flex-start;
		flex-direction: column;
	}
	.feedback-toolbar {
		flex-wrap: wrap;
	}
	.feedback-detail-body {
		padding: 16px;
	}
	.feedback-detail footer {
		flex-direction: column;
		align-items: stretch;
		padding: 14px 16px;
	}
	.feedback-detail footer small {
		max-width: none;
	}
	.feedback-detail footer > div {
		justify-content: flex-end;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: AI反馈
</route>
