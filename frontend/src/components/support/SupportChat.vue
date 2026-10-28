<template>
	<section
		class="support-console"
		:aria-label="admin ? '人工客服工作台' : '联系人工客服'"
	>
		<header class="support-heading">
			<div>
				<p class="eyebrow">{{ admin ? '全局管理' : '需要更多帮助？' }}</p>
				<h1>{{ admin ? '人工客服工作台' : '联系人工客服' }}</h1>
				<p>
					{{
						admin
							? '接待成员的问题，消息按会话保存。'
							: 'AI 没有解决的问题，可以在这里联系全局管理员。'
					}}
				</p>
			</div>
			<router-link to="/">返回云盘</router-link>
		</header>
		<p v-if="!allowed" class="support-empty" role="status">
			{{
				admin
					? '仅全局管理员可以接待人工客服会话。'
					: '请使用成员或空间管理员账号登录，再联系人工客服。'
			}}
		</p>
		<template v-else>
			<div class="support-connection" role="status">
				<span
					class="connection-dot"
					:class="{ online: connectionState === 'connected' }"
				/>
				<span>{{ connectionLabel }}</span>
				<button
					v-if="connectionState !== 'connected'"
					type="button"
					@click="realtime.retry()"
				>
					重新连接
				</button>
				<button
					type="button"
					:disabled="listLoading || detailLoading"
					@click="refreshAll"
				>
					刷新消息
				</button>
			</div>
			<p class="support-privacy">
				{{
					admin
						? '仅处理成员主动提交的问题；消息已送达不代表成员已读。'
						: '仅发送你在此填写的内容，不会自动共享 AI 对话、文件或长期记忆。管理员可能暂时不在线，消息会保存等待回复。'
				}}
			</p>
			<div class="support-layout">
				<aside class="support-sidebar" aria-label="客服会话列表">
					<div class="list-heading">
						<h2>{{ admin ? '会话队列' : '我的会话' }}</h2>
						<label>
							<span class="sr-only">筛选会话状态</span>
							<select
								v-model="statusFilter"
								:disabled="sending"
								@change="changeFilter"
							>
								<option value="">全部状态</option>
								<option value="WAITING">等待接待</option>
								<option value="ACTIVE">沟通中</option>
								<option value="CLOSED">已结束</option>
							</select>
						</label>
					</div>
					<p v-if="listError" class="support-error" role="alert">
						{{ listError }}
					</p>
					<p v-if="listLoading" class="support-empty" role="status">
						正在读取会话…
					</p>
					<p v-else-if="!conversations.length" class="support-empty">
						暂无符合条件的会话。
					</p>
					<ul class="conversation-list">
						<li v-for="conversation in conversations" :key="conversation.id">
							<button
								type="button"
								:disabled="sending || actionBusy"
								:class="{ selected: selected?.id === conversation.id }"
								:aria-current="
									selected?.id === conversation.id ? 'true' : undefined
								"
								@click="selectConversation(conversation)"
							>
								<span class="conversation-subject">{{
									conversation.subject
								}}</span>
								<small v-if="admin"
									>{{
										conversation.ownerName ||
										conversation.ownerAccountId ||
										'成员'
									}}
									· {{ conversation.tenantName || '工作空间' }}</small
								>
								<span class="conversation-meta"
									><span>{{ supportStatusLabel(conversation.status) }}</span
									><time>{{ formatTime(conversation.updatedAt) }}</time></span
								>
							</button>
						</li>
					</ul>
					<nav class="support-pagination" aria-label="会话分页">
						<button
							type="button"
							:disabled="page <= 1 || listLoading || sending"
							@click="changePage(page - 1)"
						>
							上一页
						</button>
						<span
							>{{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</span
						>
						<button
							type="button"
							:disabled="page * pageSize >= total || listLoading || sending"
							@click="changePage(page + 1)"
						>
							下一页
						</button>
					</nav>
				</aside>
				<section class="support-chat" :aria-busy="detailLoading">
					<template v-if="selected">
						<header class="conversation-heading">
							<div>
								<h2>{{ selected.subject }}</h2>
								<p>
									{{ supportStatusLabel(selected.status)
									}}<span v-if="admin">
										· {{ selected.ownerAccountId || selected.ownerName }} ·
										{{ selected.tenantName || '工作空间' }}</span
									>
								</p>
							</div>
							<div class="conversation-actions">
								<button
									v-if="admin && selected.status === 'WAITING'"
									class="primary-button"
									type="button"
									:disabled="actionBusy || detailLoading || sending"
									@click="acceptConversation"
								>
									接待会话
								</button>
								<button
									v-if="selected.status !== 'CLOSED'"
									type="button"
									:disabled="actionBusy || detailLoading || sending"
									@click="confirmClose = selected.id"
								>
									结束会话
								</button>
							</div>
						</header>
						<div
							v-if="confirmClose === selected.id"
							class="support-confirm"
							role="alert"
						>
							<p>结束后不能继续发送消息，历史记录仍保留。确定结束吗？</p>
							<button
								type="button"
								:disabled="actionBusy"
								@click="closeConversation"
							>
								确认结束</button
							><button
								type="button"
								:disabled="actionBusy"
								@click="confirmClose = ''"
							>
								取消
							</button>
						</div>
						<p v-if="messageError" class="support-error" role="alert">
							{{ messageError }}
						</p>
						<div class="support-history" tabindex="0" aria-label="会话消息记录">
							<button
								v-if="historyMore && messages.length < 500"
								type="button"
								class="history-button"
								:disabled="detailLoading || sending"
								@click="loadOlder"
							>
								加载更早消息
							</button>
							<p v-if="detailLoading" class="support-empty" role="status">
								正在同步消息…
							</p>
							<p v-else-if="!messages.length" class="support-empty">
								暂无消息，发送问题开始沟通。
							</p>
							<ol
								class="message-list"
								aria-live="polite"
								aria-relevant="additions"
							>
								<li
									v-for="message in messages"
									:key="message.id"
									:class="{ 'is-staff': message.senderRole === 'ADMIN' }"
								>
									<div class="message-heading">
										<strong>{{
											message.senderRole === 'ADMIN'
												? '人工客服 · 全局管理员'
												: admin
												? selected.ownerName || '成员'
												: '你'
										}}</strong
										><time>{{ formatTime(message.createdAt) }}</time>
									</div>
									<p class="message-content">{{ message.content }}</p>
								</li>
							</ol>
							<p v-if="messages.length >= 500" class="support-note">
								本页最多显示 500 条近期消息，服务端历史仍保留。
							</p>
						</div>
						<p v-if="selected.status === 'WAITING'" class="support-note">
							{{
								admin
									? '请先接待此会话，再回复成员。'
									: '已进入客服队列，管理员接待后会在此回复；你仍可补充问题。'
							}}
						</p>
						<p v-if="selected.status === 'CLOSED'" class="support-note">
							会话已结束，仅可查看记录。{{
								admin ? '' : '如需继续求助，请发起新问题。'
							}}
						</p>
					</template>
					<p v-else class="support-empty">
						{{
							admin
								? '选择左侧会话，查看成员问题并接待。'
								: '填写你需要人工帮助的问题。'
						}}
					</p>
					<form
						v-if="!admin && (!selected || selected.status === 'CLOSED')"
						novalidate
						class="support-composer"
						@submit.prevent="submitMessage(true)"
					>
						<label for="support-new-question">发起人工求助</label>
						<textarea
							id="support-new-question"
							v-model="draft"
							style="resize: none"
							maxlength="2000"
							rows="4"
							:disabled="sending || actionBusy"
							placeholder="请简要说明问题和操作步骤。不要填写密码、验证码或 API 密钥。"
						/>
						<p v-if="sendError" class="support-error" role="alert">
							{{ sendError }}
						</p>
						<div class="composer-footer">
							<small>{{ draft.length }} / 2000 · 仅向全局管理员发送此内容</small
							><button
								type="submit"
								class="primary-button"
								:disabled="sending || !draft.trim()"
							>
								{{ sending ? '正在提交…' : '确认转人工' }}
							</button>
						</div>
					</form>
					<form
						v-else-if="selected && selected.status !== 'CLOSED'"
						novalidate
						class="support-composer"
						@submit.prevent="submitMessage(false)"
					>
						<label for="support-reply">{{
							admin ? '回复成员' : '补充消息'
						}}</label>
						<textarea
							id="support-reply"
							v-model="draft"
							style="resize: none"
							maxlength="2000"
							rows="3"
							:disabled="
								sending ||
								!selectedReady ||
								(admin && selected.status !== 'ACTIVE')
							"
							placeholder="输入消息，点击发送。请勿提供密码或验证码。"
						/>
						<p v-if="sendError" class="support-error" role="alert">
							{{ sendError }}
						</p>
						<div class="composer-footer">
							<small>{{ draft.length }} / 2000 · 已保存不等于已读</small
							><button
								type="submit"
								class="primary-button"
								:disabled="!canSend"
							>
								{{ sending ? '正在发送…' : '发送消息' }}
							</button>
						</div>
					</form>
					<p v-if="actionError" class="support-error" role="alert">
						{{ actionError }}
					</p>
					<p v-if="notice" class="support-note" role="status">{{ notice }}</p>
				</section>
			</div>
		</template>
	</section>
</template>

<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useStorage } from '@vueuse/core'
import useStorageConfigStore from '~/stores/storage-config'
import useGlobalConfigStore from '~/stores/global-config'
import { supportRequest } from '~/api/home/support'
import {
	createSupportRealtime,
	mergeSupportMessages,
	supportStatusLabel,
} from '~/utils/support-realtime.mjs'

const props = defineProps({ admin: { type: Boolean, default: false } })
const accountStore = useStorageConfigStore()
const globalConfig = useGlobalConfigStore()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
const allowed = computed(() => {
	const identity = accountStore.loginInfo
	return (
		identity.isLogin === true &&
		(props.admin
			? identity.role === 'ADMIN' && identity.isAdmin === true
			: ['USER', 'TENANT_ADMIN'].includes(identity.role))
	)
})
const identityKey = computed(() =>
	allowed.value
		? `${accountStore.loginInfo.username}\n${
				accountStore.loginInfo.tenantId ?? ''
		  }\n${accountStore.loginInfo.role}\n${credential.value}`
		: ''
)
const conversations = ref([]),
	selected = ref(null),
	messages = ref([]),
	total = ref(0)
const page = ref(1),
	pageSize = 20,
	statusFilter = ref(props.admin ? 'WAITING' : '')
const listLoading = ref(false),
	detailLoading = ref(false),
	selectedReady = ref(false)
const listError = ref(''),
	messageError = ref(''),
	sendError = ref(''),
	actionError = ref(''),
	notice = ref('')
const draft = ref(''),
	sending = ref(false),
	actionBusy = ref(false),
	confirmClose = ref(''),
	historyMore = ref(false)
const connectionState = ref('connecting')
const connectionLabel = computed(
	() =>
		({
			connected: '实时消息已连接',
			connecting: '正在连接实时消息…',
			reconnecting: '实时连接中断，正在重连…',
			offline: '实时连接暂不可用；仍可发送并手动刷新消息。',
			unsupported: '浏览器不支持实时连接；可发送并手动刷新。',
		}[connectionState.value] || '实时连接未就绪')
)
const canSend = computed(
	() =>
		allowed.value &&
		selectedReady.value &&
		!sending.value &&
		!actionBusy.value &&
		selected.value &&
		selected.value.status !== 'CLOSED' &&
		(!props.admin || selected.value.status === 'ACTIVE') &&
		draft.value.trim().length > 0 &&
		draft.value.length <= 2000
)
let epoch = 0,
	listVersion = 0,
	selectionVersion = 0,
	disposed = false,
	pendingSend = null,
	refreshTimer = null,
	pendingSelectedRefresh = false
const requests = new Set()
const validStatus = (value) => ['WAITING', 'ACTIVE', 'CLOSED'].includes(value)
const fresh = (version) => !disposed && version === epoch && allowed.value
const errorMessage = (error, fallback) =>
	error?.response?.data?.msg || error?.data?.msg || fallback
const formatTime = (value) => {
	const date = new Date(Number(value))
	return Number.isFinite(date.getTime())
		? date.toLocaleString('zh-CN', {
				month: '2-digit',
				day: '2-digit',
				hour: '2-digit',
				minute: '2-digit',
		  })
		: '时间未知'
}
async function request(method, path, data) {
	const controller = new AbortController()
	requests.add(controller)
	try {
		return await supportRequest(method, path, data, {
			signal: controller.signal,
		})
	} finally {
		requests.delete(controller)
	}
}
const realtime = createSupportRealtime({
	getTicket: (config) => supportRequest('post', '/socket-ticket', {}, config),
	baseUrl: () => globalConfig.studentCloudConfig.baseUrl,
	onState: (state) => {
		if (!disposed && allowed.value) connectionState.value = state
	},
	onChange: (conversationId) => {
		if (!allowed.value || disposed) return
		if (!conversationId || conversationId === selected.value?.id)
			pendingSelectedRefresh = true
		if (refreshTimer !== null) return
		refreshTimer = setTimeout(() => {
			refreshTimer = null
			loadList(!selected.value)
			if (pendingSelectedRefresh) {
				pendingSelectedRefresh = false
				refreshSelected()
			}
		}, 250)
	},
})

async function loadList(autoSelect = true) {
	if (!allowed.value || disposed) return
	const version = ++listVersion,
		session = epoch
	listLoading.value = true
	listError.value = ''
	try {
		const { data } = await request('get', '/conversations', {
			page: page.value,
			pageSize,
			status: statusFilter.value || undefined,
		})
		if (!fresh(session) || version !== listVersion) return
		if (
			!Array.isArray(data?.items) ||
			!Number.isFinite(data.total) ||
			data.total < 0
		)
			throw new Error('Invalid page')
		conversations.value = data.items.filter(
			(item) => typeof item.id === 'string' && validStatus(item.status)
		)
		total.value = data.total
		const updated = conversations.value.find(
			(item) => item.id === selected.value?.id
		)
		if (
			updated &&
			Number(updated.updatedAt) >= Number(selected.value.updatedAt)
		)
			selected.value = { ...updated }
		if (
			autoSelect &&
			!selected.value &&
			!draft.value &&
			!pendingSend &&
			!sending.value &&
			conversations.value.length
		)
			selectConversation(conversations.value[0])
	} catch (error) {
		if (fresh(session) && version === listVersion)
			listError.value = errorMessage(error, '会话列表加载失败，请刷新重试。')
	} finally {
		if (fresh(session) && version === listVersion) listLoading.value = false
	}
}

async function selectConversation(conversation) {
	if (!allowed.value || sending.value || actionBusy.value) return
	selectionVersion++
	selected.value = { ...conversation }
	messages.value = []
	detailLoading.value = false
	historyMore.value = false
	selectedReady.value = false
	confirmClose.value = ''
	draft.value = ''
	pendingSend = null
	sendError.value = actionError.value = notice.value = ''
	await refreshSelected(true)
}

async function refreshSelected(initial = false, older = false) {
	if (!selected.value || !allowed.value || disposed) return
	if (detailLoading.value) {
		pendingSelectedRefresh = true
		return
	}
	const session = epoch,
		selection = selectionVersion,
		id = selected.value.id
	detailLoading.value = true
	messageError.value = ''
	try {
		const query = { limit: 100 }
		if (older && messages.value.length) query.beforeId = messages.value[0].id
		else if (!initial && messages.value.length)
			query.afterId = messages.value[messages.value.length - 1].id
		const [detail, response] = await Promise.all([
			request('get', `/conversations/${encodeURIComponent(id)}`),
			request(
				'get',
				`/conversations/${encodeURIComponent(id)}/messages`,
				query
			),
		])
		if (
			!fresh(session) ||
			selection !== selectionVersion ||
			selected.value?.id !== id
		)
			return
		if (
			detail.data?.id !== id ||
			!validStatus(detail.data.status) ||
			!Array.isArray(response.data?.items)
		)
			throw new Error('Invalid conversation')
		selected.value = detail.data
		const items = response.data.items.filter(
			(message) =>
				message.conversationId === id && typeof message.content === 'string'
		)
		messages.value = mergeSupportMessages(initial ? [] : messages.value, items)
		if (initial || older) historyMore.value = response.data.hasMore === true
		else if (response.data.hasMore)
			notice.value = '还有新消息，点击刷新继续加载。'
		selectedReady.value = true
	} catch (error) {
		if (fresh(session) && selection === selectionVersion)
			messageError.value = errorMessage(error, '消息加载失败，请刷新重试。')
	} finally {
		if (fresh(session) && selection === selectionVersion) {
			detailLoading.value = false
			if (pendingSelectedRefresh) {
				pendingSelectedRefresh = false
				refreshSelected()
			}
		}
	}
}
const loadOlder = () => refreshSelected(false, true)
const refreshAll = () => {
	loadList(!selected.value)
	refreshSelected()
}
const changePage = (value) => {
	page.value = value
	loadList(false)
}
const changeFilter = () => {
	selectionVersion++
	selected.value = null
	messages.value = []
	selectedReady.value = false
	detailLoading.value = false
	page.value = 1
	loadList()
}
const clientId = () =>
	globalThis.crypto?.randomUUID?.() ||
	`support-${Date.now()}-${Math.random().toString(36).slice(2)}`
async function submitMessage(open) {
	if (
		!allowed.value ||
		sending.value ||
		(!open && !canSend.value) ||
		(open && props.admin)
	)
		return
	const content = draft.value.trim()
	if (!content || draft.value.length > 2000) return
	const session = epoch,
		target = open ? 'new' : selected.value.id
	if (
		!pendingSend ||
		pendingSend.target !== target ||
		pendingSend.content !== content
	)
		pendingSend = { target, content, clientMessageId: clientId() }
	const payload = { content, clientMessageId: pendingSend.clientMessageId }
	sending.value = true
	sendError.value = ''
	try {
		const response = await request(
			'post',
			open
				? '/conversations'
				: `/conversations/${encodeURIComponent(target)}/messages`,
			payload
		)
		if (!fresh(session)) return
		if (
			open
				? typeof response.data?.id !== 'string' ||
				  !validStatus(response.data.status)
				: !Number.isSafeInteger(response.data?.id) ||
				  response.data.id <= 0 ||
				  response.data.conversationId !== target ||
				  response.data.clientMessageId !== payload.clientMessageId
		)
			throw new Error('Message acknowledgement was not confirmed')
		draft.value = ''
		pendingSend = null
		notice.value = '消息已保存，等待对方回复；不代表已读。'
		if (open) {
			// selectConversation is intentionally locked while sending.
			sending.value = false
			await selectConversation(response.data)
		} else {
			messages.value = mergeSupportMessages(messages.value, [response.data])
			await refreshSelected()
		}
		loadList(false)
	} catch (error) {
		if (fresh(session))
			sendError.value = errorMessage(
				error,
				'发送状态未确认，草稿已保留。可先刷新确认，再重试同一条消息，不会重复保存。'
			)
	} finally {
		if (fresh(session)) sending.value = false
	}
}

async function changeConversation(action) {
	if (
		!selectedReady.value ||
		!selected.value ||
		!allowed.value ||
		actionBusy.value ||
		sending.value
	)
		return
	const id = selected.value.id,
		selection = selectionVersion,
		session = epoch
	actionBusy.value = true
	actionError.value = ''
	try {
		const { data } = await request(
			'post',
			`/conversations/${encodeURIComponent(id)}/${action}`,
			{}
		)
		if (!fresh(session) || selection !== selectionVersion) return
		selected.value = data
		confirmClose.value = ''
		loadList(false)
	} catch (error) {
		if (fresh(session) && selection === selectionVersion)
			actionError.value = errorMessage(
				error,
				'操作失败，请刷新会话状态后重试。'
			)
	} finally {
		if (fresh(session) && selection === selectionVersion)
			actionBusy.value = false
	}
}
const acceptConversation = () => {
	if (props.admin && selected.value?.status === 'WAITING')
		return changeConversation('accept')
}
const closeConversation = () => {
	if (confirmClose.value === selected.value?.id)
		return changeConversation('close')
}
function reset() {
	epoch++
	listVersion++
	selectionVersion++
	for (const controller of requests) controller.abort()
	requests.clear()
	realtime.stop()
	if (refreshTimer !== null) clearTimeout(refreshTimer)
	refreshTimer = null
	pendingSelectedRefresh = false
	conversations.value = messages.value = []
	selected.value = null
	draft.value =
		listError.value =
		messageError.value =
		sendError.value =
		actionError.value =
		notice.value =
		confirmClose.value =
			''
	listLoading.value =
		detailLoading.value =
		selectedReady.value =
		sending.value =
		actionBusy.value =
		historyMore.value =
			false
	page.value = 1
	total.value = 0
	pendingSend = null
}
watch(
	identityKey,
	() => {
		reset()
		if (allowed.value && !disposed) {
			loadList()
			realtime.start()
		}
	},
	{ immediate: true }
)
onBeforeUnmount(() => {
	disposed = true
	reset()
})
</script>

<style scoped>
.support-console {
	color: var(--cloud-ink, #242426);
}
.support-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 20px;
	margin-bottom: 20px;
}
.support-heading h1 {
	margin: 4px 0 8px;
	font-size: 26px;
	font-weight: 700;
	letter-spacing: -0.03em;
}
.support-heading p,
.support-privacy,
.support-note {
	color: var(--cloud-muted, #73737c);
	font-size: 13px;
	line-height: 1.7;
}
.eyebrow {
	font-size: 11px !important;
	letter-spacing: 0.08em;
}
.support-heading a {
	white-space: nowrap;
	text-decoration: underline;
	font-size: 13px;
}
.support-connection {
	display: flex;
	align-items: center;
	gap: 10px;
	flex-wrap: wrap;
	font-size: 12px;
}
.connection-dot {
	width: 7px;
	height: 7px;
	border-radius: 50%;
	background: #a3a3ad;
}
.connection-dot.online {
	background: #39816b;
}
.support-connection button {
	text-decoration: underline;
}
.support-privacy {
	margin: 12px 0 20px;
}
.support-layout {
	display: grid;
	grid-template-columns: 290px minmax(0, 1fr);
	gap: 18px;
	align-items: start;
}
.support-sidebar,
.support-chat {
	border: 1px solid var(--cloud-line, #dedee2);
	border-radius: 18px;
	background: var(--cloud-surface, #fff);
	min-width: 0;
}
.support-sidebar {
	overflow: hidden;
}
.list-heading {
	padding: 18px 16px;
	display: flex;
	gap: 10px;
	align-items: center;
	justify-content: space-between;
}
.list-heading h2 {
	font-size: 14px;
	margin: 0;
	font-weight: 650;
}
.list-heading select {
	max-width: 125px;
	padding: 5px 8px;
	border: 1px solid var(--cloud-line, #ddd);
	border-radius: 8px;
	font-size: 12px;
	background: transparent;
}
.conversation-list,
.message-list {
	list-style: none;
	margin: 0;
	padding: 0;
}
.conversation-list button {
	width: 100%;
	text-align: left;
	padding: 14px 16px;
	border-top: 1px solid var(--cloud-line, #eee);
	transition: background 0.16s ease;
}
.conversation-list button:hover,
.conversation-list button.selected {
	background: var(--cloud-soft, #f1f0f5);
}
.conversation-subject {
	display: block;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
	font-size: 13px;
	font-weight: 600;
}
.conversation-list small {
	display: block;
	font-size: 11px;
	color: var(--cloud-muted, #777);
	margin: 5px 0;
}
.conversation-meta {
	display: flex;
	justify-content: space-between;
	gap: 8px;
	margin-top: 8px;
	font-size: 11px;
	color: var(--cloud-muted, #777);
}
.support-pagination {
	padding: 14px;
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	border-top: 1px solid var(--cloud-line, #eee);
	font-size: 12px;
}
.support-pagination button {
	padding: 4px;
}
.support-chat {
	padding: 22px;
}
.conversation-heading {
	display: flex;
	justify-content: space-between;
	gap: 16px;
	align-items: flex-start;
	border-bottom: 1px solid var(--cloud-line, #eee);
	padding-bottom: 16px;
}
.conversation-heading h2 {
	margin: 0 0 8px;
	font-size: 16px;
	font-weight: 650;
	overflow-wrap: anywhere;
}
.conversation-heading p {
	font-size: 12px;
	color: var(--cloud-muted, #777);
}
.conversation-actions {
	display: flex;
	flex-wrap: wrap;
	justify-content: flex-end;
	gap: 8px;
	flex-shrink: 0;
	max-width: 190px;
}
.conversation-actions button,
.support-confirm button,
.composer-footer button {
	border: 1px solid var(--cloud-line, #ddd);
	padding: 8px 14px;
	border-radius: 10px;
	font-size: 12px;
}
.primary-button {
	color: #fff;
	background: var(--cloud-primary, #242426);
	border-color: transparent !important;
}
.support-history {
	min-height: 220px;
	max-height: 510px;
	overflow: auto;
	padding: 18px 1px;
}
.message-list {
	display: flex;
	flex-direction: column;
	gap: 18px;
}
.message-list li {
	max-width: 90%;
	border: 1px solid var(--cloud-line, #e8e8ec);
	padding: 13px 15px;
	border-radius: 14px 14px 14px 3px;
	background: var(--cloud-soft, #f6f6f8);
}
.message-list li.is-staff {
	margin-left: auto;
	background: #f1edf9;
	border-radius: 14px 14px 3px 14px;
	border-color: #e4dcf3;
}
.message-heading {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 12px;
	margin-bottom: 7px;
	font-size: 11px;
}
.message-heading strong {
	font-weight: 600;
}
.message-heading time {
	color: var(--cloud-muted, #777);
}
.message-content {
	white-space: pre-wrap;
	overflow-wrap: anywhere;
	line-height: 1.75;
	font-size: 13px;
	margin: 0;
}
.support-empty {
	padding: 25px 16px;
	color: var(--cloud-muted, #777);
	font-size: 13px;
	line-height: 1.7;
}
.support-composer {
	border-top: 1px solid var(--cloud-line, #eee);
	padding-top: 18px;
	margin-top: 16px;
}
.support-composer label {
	display: block;
	font-size: 13px;
	font-weight: 600;
	margin-bottom: 10px;
}
.support-composer textarea {
	width: 100%;
	display: block;
	padding: 12px;
	border: 1px solid var(--cloud-line, #dcdce0);
	border-radius: 12px;
	background: var(--cloud-surface, #fff);
	font: inherit;
	font-size: 13px;
	line-height: 1.7;
}
.support-composer textarea:focus {
	outline: 2px solid var(--cloud-accent, #7f61ce);
	outline-offset: 2px;
}
.composer-footer {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 12px;
	flex-wrap: wrap;
	margin-top: 12px;
}
.composer-footer small {
	color: var(--cloud-muted, #777);
	font-size: 11px;
}
.support-error {
	color: #a63737;
	background: #fff3f3;
	border-radius: 8px;
	padding: 10px 12px;
	font-size: 12px;
	line-height: 1.7;
	margin: 10px 0;
}
.support-confirm {
	padding: 12px;
	background: #faf4e9;
	border-radius: 10px;
	font-size: 12px;
	margin-top: 12px;
}
.support-confirm p {
	margin: 0 0 10px;
}
.support-confirm button {
	margin-right: 8px;
}
.history-button {
	display: block;
	margin: 0 auto 16px;
	font-size: 12px;
	text-decoration: underline;
}
button:disabled {
	opacity: 0.5;
	cursor: not-allowed;
}
button:focus-visible,
a:focus-visible,
select:focus-visible {
	outline: 2px solid var(--cloud-accent, #7f61ce);
	outline-offset: 3px;
}
@media (max-width: 900px) {
	.support-layout {
		grid-template-columns: 230px minmax(0, 1fr);
	}
	.support-chat {
		padding: 16px;
	}
	.conversation-heading {
		flex-direction: column;
	}
	.conversation-actions {
		max-width: none;
	}
}
@media (max-width: 640px) {
	.support-layout {
		grid-template-columns: minmax(0, 1fr);
	}
	.support-heading {
		align-items: flex-start;
	}
	.support-heading h1 {
		font-size: 22px;
	}
	.conversation-list {
		max-height: 190px;
		overflow: auto;
	}
	.support-history {
		max-height: 430px;
	}
	.message-list li {
		max-width: 96%;
	}
}
@media (prefers-reduced-motion: reduce) {
	.conversation-list button {
		transition: none;
	}
}
</style>
