<template>
	<section class="assistant-support" aria-label="小芽内的人工客服">
		<header class="assistant-support-heading">
			<div>
				<strong>人工客服</strong>
				<small>由全局管理员接待</small>
			</div>
			<button type="button" @click="emit('back')">返回 AI</button>
		</header>
		<p v-if="!allowed || invalidated" class="support-notice" role="status">
			登录身份已变化，请返回 AI 后重新确认转人工。
		</p>
		<template v-else>
			<div class="assistant-support-status" role="status">
				<span :class="{ 'is-online': connectionState === 'connected' }" />
				{{
					conversation ? supportStatusLabel(conversation.status) : '正在转接'
				}}
				<small>{{ connectionLabel }}</small>
				<button
					v-if="conversation && connectionState !== 'connected'"
					type="button"
					@click="realtime.retry()"
				>
					重连
				</button>
			</div>
			<p v-if="starting" class="support-notice" role="status">
				正在提交你确认的问题…
			</p>
			<div v-if="startError" class="support-error" role="alert">
				<p>{{ startError }}</p>
				<button type="button" :disabled="starting" @click="startConversation">
					重试转接
				</button>
			</div>
			<template v-if="conversation">
				<div class="assistant-support-toolbar">
					<button type="button" :disabled="syncing" @click="refreshMessages()">
						{{ syncing ? '同步中…' : '刷新消息' }}
					</button>
					<button
						v-if="conversation.status !== 'CLOSED'"
						type="button"
						:disabled="sending || closing"
						@click="confirmClose = !confirmClose"
					>
						结束会话
					</button>
				</div>
				<div v-if="confirmClose" class="support-confirm" role="alert">
					<p>结束后保留聊天记录，但不能继续发送。确定结束吗？</p>
					<button
						type="button"
						:disabled="closing || sending"
						@click="closeConversation"
					>
						确认结束
					</button>
					<button
						type="button"
						:disabled="closing"
						@click="confirmClose = false"
					>
						取消
					</button>
				</div>
				<p v-if="messageError" class="support-error" role="alert">
					{{ messageError }}
				</p>
				<div
					ref="historyRef"
					class="assistant-support-history"
					tabindex="0"
					aria-label="人工客服消息记录"
				>
					<button
						v-if="historyMore && messages.length < 500"
						type="button"
						:disabled="syncing"
						@click="refreshMessages(false, true)"
					>
						加载更早消息
					</button>
					<ol aria-live="polite" aria-relevant="additions">
						<li
							v-for="message in messages"
							:key="message.id"
							:class="{ 'is-member': message.senderRole !== 'ADMIN' }"
						>
							<div>
								<strong>{{
									message.senderRole === 'ADMIN'
										? '人工客服 · 全局管理员'
										: '你'
								}}</strong
								><time>{{ formatTime(message.createdAt) }}</time>
							</div>
							<p>{{ message.content }}</p>
						</li>
					</ol>
					<p v-if="!messages.length && !syncing" class="support-notice">
						暂时没有消息，点击刷新重试。
					</p>
					<p v-if="messages.length >= 500" class="support-notice">
						窗口最多显示 500 条近期消息，服务端历史仍保留。
					</p>
				</div>
				<p
					v-if="conversation.status === 'WAITING'"
					class="support-notice"
					role="status"
				>
					已进入客服队列，可继续补充问题。管理员暂未接待，回复会在此显示。
				</p>
				<p
					v-if="conversation.status === 'CLOSED'"
					class="support-notice"
					role="status"
				>
					人工会话已结束。返回 AI 后可重新发起求助。
				</p>
				<form
					v-else
					class="assistant-support-composer"
					@submit.prevent="sendMessage"
				>
					<label for="assistant-support-reply">发送给人工客服</label>
					<textarea
						id="assistant-support-reply"
						v-model="draft"
						maxlength="2000"
						rows="2"
						:disabled="sending || closing"
						placeholder="补充说明你的问题"
						@keydown.ctrl.enter.prevent="sendMessage"
					/>
					<div>
						<small>{{ draft.length }}/2000 · Ctrl+Enter 发送</small
						><button type="submit" class="primary" :disabled="!canSend">
							{{ sending ? '发送中…' : '发送' }}
						</button>
					</div>
				</form>
			</template>
			<p v-if="sendError" class="support-error" role="alert">{{ sendError }}</p>
			<p v-if="actionError" class="support-error" role="alert">
				{{ actionError }}
			</p>
			<p v-if="notice" class="support-notice" role="status">{{ notice }}</p>
			<p class="assistant-support-privacy">
				仅发送你确认的问题和在此输入的内容，不自动共享 AI 对话、附件或长期记忆。
			</p>
		</template>
	</section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useStorage } from '@vueuse/core'
import useStorageConfigStore from '~/stores/storage-config'
import useGlobalConfigStore from '~/stores/global-config'
import { supportRequest } from '~/api/home/support'
import {
	createSupportRealtime,
	mergeSupportMessages,
	supportStatusLabel,
} from '~/utils/support-realtime.mjs'

// Mount only after the member confirms this exact issue. Mounting never copies AI history.
const props = defineProps({
	initialIssue: { type: String, default: '' },
	clientMessageId: { type: String, default: '' },
	identityKey: { type: String, default: '' },
})
const emit = defineEmits(['back', 'started', 'closed'])
const store = useStorageConfigStore()
const globalConfig = useGlobalConfigStore()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
const allowed = computed(
	() =>
		store.loginInfo.isLogin === true &&
		['USER', 'TENANT_ADMIN'].includes(store.loginInfo.role) &&
		!!credential.value
)
const currentIdentity = computed(
	() =>
		`${store.loginInfo.username ?? ''}\n${store.loginInfo.tenantId ?? ''}\n${
			store.loginInfo.role ?? ''
		}\n${credential.value}\n${props.identityKey}`
)
const invalidated = ref(false),
	conversation = ref(null),
	messages = ref([])
const starting = ref(false),
	syncing = ref(false),
	sending = ref(false),
	closing = ref(false)
const startError = ref(''),
	messageError = ref(''),
	sendError = ref(''),
	actionError = ref(''),
	notice = ref('')
const draft = ref(''),
	confirmClose = ref(false),
	historyMore = ref(false),
	historyRef = ref(null)
const connectionState = ref('connecting')
const connectionLabel = computed(
	() =>
		({
			connected: '实时已连接',
			connecting: '连接中…',
			reconnecting: '正在重连…',
			offline: '实时离线，可发送并刷新',
			unsupported: '可发送并手动刷新',
		}[connectionState.value] || '未连接')
)
const canSend = computed(
	() =>
		allowed.value &&
		!invalidated.value &&
		conversation.value &&
		conversation.value.status !== 'CLOSED' &&
		!sending.value &&
		!closing.value &&
		draft.value.trim().length > 0 &&
		draft.value.length <= 2000
)
const confirmedIssue = props.initialIssue.trim()
const confirmedClientId = props.clientMessageId
let epoch = 0,
	disposed = false,
	boundIdentity = null,
	pendingSend = null,
	refreshTimer = null,
	refreshAgain = false
const requests = new Set()
const validStatus = (status) => ['WAITING', 'ACTIVE', 'CLOSED'].includes(status)
const fresh = (version) =>
	!disposed &&
	!invalidated.value &&
	allowed.value &&
	version === epoch &&
	boundIdentity === currentIdentity.value
const errorMessage = (error, fallback) =>
	error?.response?.data?.msg || error?.data?.msg || fallback
const formatTime = (value) => {
	const date = new Date(Number(value))
	return Number.isFinite(date.getTime())
		? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
		: ''
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
		if (fresh(epoch)) connectionState.value = state
	},
	onChange: (id) => {
		if (
			!fresh(epoch) ||
			!conversation.value ||
			(id && id !== conversation.value.id) ||
			refreshTimer !== null
		)
			return
		refreshTimer = setTimeout(() => {
			refreshTimer = null
			refreshMessages()
		}, 200)
	},
})
async function startConversation() {
	if (!fresh(epoch) || starting.value || conversation.value) return
	if (
		!confirmedIssue ||
		confirmedIssue.length > 2000 ||
		!/^[A-Za-z0-9_-]{1,80}$/.test(confirmedClientId)
	) {
		startError.value = '求助信息不完整，请返回 AI 后重新确认。'
		return
	}
	const version = epoch
	starting.value = true
	startError.value = ''
	try {
		const { data } = await request('post', '/conversations', {
			content: confirmedIssue,
			clientMessageId: confirmedClientId,
		})
		if (!fresh(version)) return
		if (typeof data?.id !== 'string' || !validStatus(data.status))
			throw new Error('Invalid conversation acknowledgement')
		conversation.value = data
		emit('started', data)
		realtime.start()
		await refreshMessages(true)
	} catch (error) {
		if (fresh(version))
			startError.value = errorMessage(
				error,
				'转接状态未确认。可以重试同一问题，不会重复保存。'
			)
	} finally {
		if (fresh(version)) starting.value = false
	}
}
async function refreshMessages(initial = false, older = false) {
	if (!fresh(epoch) || !conversation.value) return
	if (syncing.value) {
		refreshAgain = true
		return
	}
	const version = epoch,
		id = conversation.value.id
	const scrollAtEnd =
		!historyRef.value ||
		historyRef.value.scrollHeight -
			historyRef.value.scrollTop -
			historyRef.value.clientHeight <
			80
	syncing.value = true
	messageError.value = ''
	try {
		const query = { limit: 100 }
		if (older && messages.value.length) query.beforeId = messages.value[0].id
		else if (!initial && messages.value.length)
			query.afterId = messages.value[messages.value.length - 1].id
		const [detail, result] = await Promise.all([
			request('get', `/conversations/${encodeURIComponent(id)}`),
			request(
				'get',
				`/conversations/${encodeURIComponent(id)}/messages`,
				query
			),
		])
		if (!fresh(version) || conversation.value?.id !== id) return
		if (
			detail.data?.id !== id ||
			!validStatus(detail.data.status) ||
			!Array.isArray(result.data?.items)
		)
			throw new Error('Invalid support history')
		// Closing is terminal even if an earlier HTTP snapshot arrives afterwards.
		const wasClosed = conversation.value.status === 'CLOSED'
		conversation.value = {
			...detail.data,
			status: wasClosed ? 'CLOSED' : detail.data.status,
		}
		if (!wasClosed && conversation.value.status === 'CLOSED')
			emit('closed', conversation.value)
		messages.value = mergeSupportMessages(
			initial ? [] : messages.value,
			result.data.items.filter(
				(item) => item.conversationId === id && typeof item.content === 'string'
			)
		)
		if (initial || older) historyMore.value = result.data.hasMore === true
		else if (result.data.hasMore)
			notice.value = '还有新消息，点击刷新继续加载。'
		if (conversation.value.status === 'CLOSED') confirmClose.value = false
		await nextTick()
		if (fresh(version) && !older && scrollAtEnd && historyRef.value)
			historyRef.value.scrollTop = historyRef.value.scrollHeight
	} catch (error) {
		if (fresh(version))
			messageError.value = errorMessage(error, '消息同步失败，请点击刷新重试。')
	} finally {
		if (fresh(version)) {
			syncing.value = false
			if (refreshAgain) {
				refreshAgain = false
				refreshMessages()
			}
		}
	}
}
const clientId = () =>
	globalThis.crypto?.randomUUID?.() ||
	`support-${Date.now()}-${Math.random().toString(36).slice(2)}`
async function sendMessage() {
	if (!canSend.value) return
	const version = epoch,
		id = conversation.value.id,
		content = draft.value.trim()
	if (!pendingSend || pendingSend.content !== content || pendingSend.id !== id)
		pendingSend = { id, content, clientMessageId: clientId() }
	const payload = { content, clientMessageId: pendingSend.clientMessageId }
	sending.value = true
	sendError.value = notice.value = ''
	try {
		const { data } = await request(
			'post',
			`/conversations/${encodeURIComponent(id)}/messages`,
			payload
		)
		if (!fresh(version) || conversation.value?.id !== id) return
		if (
			!Number.isSafeInteger(data?.id) ||
			data.id <= 0 ||
			data.conversationId !== id ||
			data.clientMessageId !== payload.clientMessageId
		)
			throw new Error('Invalid message acknowledgement')
		messages.value = mergeSupportMessages(messages.value, [data])
		draft.value = ''
		pendingSend = null
		notice.value = '消息已保存，等待管理员回复；不代表已读。'
		await refreshMessages()
	} catch (error) {
		if (fresh(version))
			sendError.value = errorMessage(
				error,
				'发送状态未确认，草稿已保留。请刷新或重试同一条消息，不会重复保存。'
			)
	} finally {
		if (fresh(version)) sending.value = false
	}
}
async function closeConversation() {
	if (
		!fresh(epoch) ||
		!confirmClose.value ||
		!conversation.value ||
		sending.value ||
		closing.value
	)
		return
	const version = epoch,
		id = conversation.value.id
	closing.value = true
	actionError.value = ''
	try {
		const { data } = await request(
			'post',
			`/conversations/${encodeURIComponent(id)}/close`,
			{}
		)
		if (!fresh(version) || conversation.value?.id !== id) return
		if (data?.id !== id || data.status !== 'CLOSED')
			throw new Error('Invalid close acknowledgement')
		conversation.value = data
		confirmClose.value = false
		emit('closed', data)
	} catch (error) {
		if (fresh(version))
			actionError.value = errorMessage(
				error,
				'结束会话未确认，请刷新状态后重试。'
			)
	} finally {
		if (fresh(version)) closing.value = false
	}
}
function clearPrivateState() {
	epoch++
	for (const controller of requests) controller.abort()
	requests.clear()
	realtime.stop()
	if (refreshTimer !== null) clearTimeout(refreshTimer)
	refreshTimer = null
	refreshAgain = false
	conversation.value = null
	messages.value = []
	draft.value =
		startError.value =
		messageError.value =
		sendError.value =
		actionError.value =
		notice.value =
			''
	starting.value =
		syncing.value =
		sending.value =
		closing.value =
		confirmClose.value =
		historyMore.value =
			false
	pendingSend = null
}
watch(
	[currentIdentity, allowed],
	() => {
		if (boundIdentity !== null) {
			if (boundIdentity !== currentIdentity.value || !allowed.value) {
				clearPrivateState()
				invalidated.value = true
			}
			return
		}
		// A new identity may never inherit another account's confirmed issue.
		boundIdentity = currentIdentity.value
		if (allowed.value) startConversation()
	},
	{ immediate: true, flush: 'sync' }
)
onBeforeUnmount(() => {
	disposed = true
	clearPrivateState()
})
</script>

<style scoped>
.assistant-support {
	display: flex;
	flex: 1;
	flex-direction: column;
	min-height: 0;
	padding: 14px 16px;
	gap: 10px;
	color: var(--cloud-ink, #27272a);
	background: var(--cloud-soft, #f2f2f4);
	overflow: auto;
}
.assistant-support-heading,
.assistant-support-toolbar,
.assistant-support-status,
.assistant-support-composer > div,
.assistant-support-history li > div {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
}
.assistant-support-heading > div {
	display: grid;
	gap: 3px;
}
.assistant-support-heading strong {
	font-size: 14px;
}
.assistant-support small,
.assistant-support time {
	font-size: 11px;
	color: var(--cloud-muted, #71717a);
}
.assistant-support button {
	border: 1px solid var(--cloud-line, #dddde2);
	border-radius: 9px;
	padding: 6px 10px;
	background: var(--cloud-surface, #fff);
	color: var(--cloud-ink, #3f3f46);
	font: inherit;
	font-size: 12px;
	cursor: pointer;
}
.assistant-support button:disabled {
	cursor: default;
	opacity: 0.5;
}
.assistant-support button:focus-visible,
.assistant-support textarea:focus-visible {
	outline: 2px solid var(--cloud-accent, #7c69b8);
	outline-offset: 2px;
}
.assistant-support-status {
	justify-content: flex-start;
	font-size: 12px;
}
.assistant-support-status > span {
	width: 6px;
	height: 6px;
	flex: 0 0 auto;
	border-radius: 50%;
	background: #9b9ba3;
}
.assistant-support-status > .is-online {
	background: #43816d;
}
.assistant-support-status > small {
	margin-left: auto;
}
.assistant-support-toolbar {
	justify-content: flex-end;
}
.assistant-support-toolbar button {
	padding: 4px 8px;
	background: transparent;
}
.assistant-support-history {
	flex: 1;
	min-height: 130px;
	overflow: auto;
	overscroll-behavior: contain;
}
.assistant-support-history ol {
	display: flex;
	flex-direction: column;
	list-style: none;
	gap: 12px;
	padding: 4px 0;
	margin: 0;
}
.assistant-support-history li {
	max-width: 92%;
	border: 1px solid var(--cloud-line, #dedee3);
	background: var(--cloud-surface, #fff);
	border-radius: 12px 12px 12px 3px;
	padding: 10px 12px;
	overflow-wrap: anywhere;
}
.assistant-support-history li.is-member {
	align-self: flex-end;
	border-color: var(--cloud-primary, #323235);
	background: var(--cloud-primary, #323235);
	color: #fff;
	border-radius: 12px 12px 3px 12px;
}
.assistant-support-history li strong {
	font-size: 10px;
	font-weight: 500;
	opacity: 0.78;
}
.assistant-support-history li time {
	font-size: 10px;
	flex: 0 0 auto;
}
.assistant-support-history li.is-member time {
	color: #cccbd2;
}
.assistant-support-history li p {
	white-space: pre-wrap;
	margin: 7px 0 0;
	font-size: 13px;
	line-height: 1.65;
}
.support-notice,
.support-error,
.assistant-support-privacy {
	margin: 0;
	font-size: 11px;
	line-height: 1.6;
}
.support-notice,
.assistant-support-privacy {
	color: var(--cloud-muted, #71717a);
}
.support-error {
	color: #9f3434;
}
.support-error p {
	margin: 0 0 6px;
}
.support-confirm {
	background: var(--cloud-surface, #fff);
	padding: 10px;
	border-radius: 10px;
	font-size: 12px;
}
.support-confirm p {
	margin: 0 0 8px;
}
.support-confirm button + button {
	margin-left: 6px;
}
.assistant-support-composer {
	display: grid;
	gap: 7px;
	border-top: 1px solid var(--cloud-line, #dcdce1);
	padding-top: 10px;
}
.assistant-support-composer label {
	font-size: 12px;
}
.assistant-support-composer textarea {
	box-sizing: border-box;
	width: 100%;
	resize: vertical;
	min-height: 65px;
	max-height: 150px;
	padding: 9px;
	border: 1px solid var(--cloud-line, #d6d6de);
	border-radius: 10px;
	color: inherit;
	font: inherit;
	font-size: 13px;
}
.assistant-support button.primary {
	color: #fff;
	background: var(--cloud-primary, #29292b);
	border-color: var(--cloud-primary, #29292b);
	min-width: 65px;
}
.assistant-support-privacy {
	border-top: 1px solid var(--cloud-line, #dedee3);
	padding-top: 8px;
	font-size: 10px;
}
@media (prefers-reduced-motion: no-preference) {
	.assistant-support-history li {
		animation: support-enter 0.16s ease-out;
	}
	@keyframes support-enter {
		from {
			opacity: 0;
			transform: translateY(3px);
		}
		to {
			opacity: 1;
			transform: none;
		}
	}
}
</style>
