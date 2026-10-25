<template>
	<section
		class="ai-memory-panel"
		aria-label="长期记忆管理"
		@keydown.esc.stop="emit('close')"
	>
		<header class="memory-heading">
			<div>
				<h3>长期记忆</h3>
				<p>只属于当前账号，刷新或重启后仍保留</p>
			</div>
			<button
				type="button"
				class="memory-close"
				aria-label="关闭长期记忆"
				@click="emit('close')"
			>
				×
			</button>
		</header>
		<div class="memory-body" :aria-busy="state.busy">
			<p class="memory-note">
				保存你希望小芽记住的偏好或背景，也可以在聊天中说“请记住：……”。不会自动保存全部聊天、附件或工具结果。
			</p>
			<p class="memory-note">
				不要填写密码、授权码或 API
				密钥。清空对话不会删除长期记忆；旧记忆不能代替当前文件权限。
			</p>
			<p class="memory-note">
				删除记忆不会清除当前聊天，已发送的模型请求无法撤回。
			</p>
			<p v-if="!identityKey" role="status">请先登录，再管理你的记忆。</p>
			<template v-else>
				<div v-if="state.error" class="memory-error" role="alert">
					<span>{{ state.error }}</span>
					<button type="button" :disabled="state.busy" @click="manager.load()">
						刷新重试
					</button>
				</div>
				<p v-if="state.busy" class="memory-status" role="status">
					正在同步记忆……
				</p>
				<p v-else-if="state.notice" class="memory-status" role="status">
					{{ state.notice }}
				</p>
				<template v-if="state.loaded">
					<label class="memory-toggle">
						<span
							><strong>使用长期记忆</strong
							><small>关闭后不读取、不新增，已有条目不会被删除</small></span
						>
						<input
							type="checkbox"
							:checked="state.enabled"
							:disabled="locked || Boolean(editingId)"
							@change="manager.setEnabled($event.target.checked)"
						/>
					</label>
					<form class="memory-form" @submit.prevent="saveMemory">
						<label for="ai-memory-draft">{{
							editingId ? '编辑这条记忆' : '添加一条记忆'
						}}</label>
						<textarea
							id="ai-memory-draft"
							v-model="draft"
							rows="3"
							:maxlength="state.maxChars"
							:disabled="locked || !state.enabled"
							placeholder="例如：我正在学习 Java，讲解时请给出简短示例。"
							@input="manager.clearFormError()"
						/>
						<p v-if="state.formError" class="memory-form-error" role="alert">
							{{ state.formError }}
						</p>
						<p v-if="editStale" class="memory-form-error" role="alert">
							这条记忆已更新，请取消编辑，查看最新内容后重新编辑；草稿保留供复制。
						</p>
						<div class="memory-form-footer">
							<small>{{ draft.length }} / {{ state.maxChars }}</small>
							<button
								v-if="editingId"
								type="button"
								:disabled="state.busy"
								@click="cancelEdit"
							>
								取消编辑
							</button>
							<button type="submit" class="memory-primary" :disabled="!canSave">
								{{ editingId ? '保存修改' : '保存记忆' }}
							</button>
						</div>
					</form>
					<div class="memory-list-heading">
						<h4>已保存 {{ state.items.length }} / {{ state.maxEntries }}</h4>
						<button
							type="button"
							:disabled="locked || !state.items.length || Boolean(editingId)"
							@click="manager.askClear()"
						>
							清空全部
						</button>
					</div>
					<label class="memory-search" for="ai-memory-filter">
						<span>查找已保存的记忆</span>
						<input
							id="ai-memory-filter"
							v-model="filterQuery"
							type="search"
							maxlength="100"
							placeholder="在当前账号记忆中筛选，不请求模型"
						/>
						<small v-if="filterQuery.trim()"
							>匹配 {{ filteredItems.length }} /
							{{ state.items.length }} 条；清空全部仍会删除全部条目。</small
						>
					</label>
					<div v-if="state.confirmClear" class="memory-confirm" role="alert">
						<p>
							确定删除当前账号的全部长期记忆？此操作无法撤销，不会删除云盘文件。
						</p>
						<button
							type="button"
							class="memory-danger"
							:disabled="state.busy"
							@click="manager.clear()"
						>
							确认清空
						</button>
						<button
							type="button"
							:disabled="state.busy"
							@click="manager.cancelClear()"
						>
							取消
						</button>
					</div>
					<p v-if="!state.items.length" class="memory-empty">
						还没有记忆。只保存你主动提供的信息，不会根据聊天猜测。
					</p>
					<p v-else-if="!filteredItems.length" class="memory-empty">
						没有匹配的记忆，试试其他关键词。
					</p>
					<ul v-else class="memory-list">
						<li v-for="item in filteredItems" :key="item.id">
							<p>{{ item.content }}</p>
							<button
								type="button"
								:disabled="locked || !state.enabled || Boolean(editingId)"
								aria-label="编辑这条记忆"
								@click="beginEdit(item)"
							>
								编辑
							</button>
							<button
								type="button"
								:disabled="locked || Boolean(editingId)"
								aria-label="删除这条记忆"
								@click="manager.forget(item.id)"
							>
								删除
							</button>
						</li>
					</ul>
				</template>
			</template>
		</div>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { aiMemoryRequest } from '~/api/home/ai-memory'
import {
	createAiMemoryManager,
	filterMemoryItems,
} from '~/utils/ai-memory-panel.mjs'

const props = defineProps({ identityKey: { type: String, default: '' } })
const emit = defineEmits(['close'])
const draft = ref('')
const editingId = ref('')
const editingOriginal = ref('')
const filterQuery = ref('')
const state = ref({
	items: [],
	busy: false,
	loaded: false,
	error: '',
	notice: '',
})
const manager = createAiMemoryManager({
	request: aiMemoryRequest,
	onChange: (value) => {
		state.value = value
		if (value.identityDenied) {
			editingId.value = ''
			editingOriginal.value = ''
			draft.value = ''
			filterQuery.value = ''
		}
		if (
			editingId.value &&
			value.loaded &&
			!value.busy &&
			!value.items.some((item) => item.id === editingId.value)
		) {
			editingId.value = ''
			editingOriginal.value = ''
			draft.value = ''
		}
	},
})
state.value = manager.state()
const filteredItems = computed(() =>
	filterMemoryItems(state.value.items, filterQuery.value)
)
const locked = computed(
	() =>
		state.value.busy || Boolean(state.value.error) || state.value.confirmClear
)
const editStale = computed(() => {
	if (!editingId.value || !state.value.loaded) return false
	const current = state.value.items.find((item) => item.id === editingId.value)
	return Boolean(current && current.content !== editingOriginal.value)
})
const canSave = computed(
	() =>
		state.value.loaded &&
		!locked.value &&
		!editStale.value &&
		state.value.enabled &&
		draft.value.trim().length > 0 &&
		draft.value.trim().length <= state.value.maxChars &&
		(Boolean(editingId.value) ||
			state.value.items.length < state.value.maxEntries)
)

watch(
	() => props.identityKey,
	(identity) => {
		draft.value = ''
		editingId.value = ''
		editingOriginal.value = ''
		filterQuery.value = ''
		void manager.setIdentity(identity)
	},
	{ immediate: true, flush: 'sync' }
)
onBeforeUnmount(() => {
	draft.value = ''
	editingId.value = ''
	editingOriginal.value = ''
	filterQuery.value = ''
	manager.dispose()
})
function beginEdit(item) {
	if (locked.value || !state.value.enabled || editingId.value) return
	editingId.value = item.id
	editingOriginal.value = item.content
	draft.value = item.content
	manager.clearFormError()
}
function cancelEdit() {
	if (state.value.busy) return
	editingId.value = ''
	editingOriginal.value = ''
	draft.value = ''
	manager.clearFormError()
}
async function saveMemory() {
	if (!canSave.value) return
	const identity = props.identityKey
	const editing = editingId.value
	const saved = editing
		? await manager.update(editing, draft.value, editingOriginal.value)
		: await manager.remember(draft.value)
	if (saved && props.identityKey === identity && editingId.value === editing) {
		draft.value = ''
		editingId.value = ''
		editingOriginal.value = ''
	}
}
</script>

<style scoped>
.ai-memory-panel {
	position: absolute;
	inset: var(--ai-agent-header-height, 64px) 0 0;
	z-index: 8;
	display: flex;
	flex-direction: column;
	border-top: 1px solid var(--cloud-line);
	border-radius: 0 0 var(--cloud-radius) var(--cloud-radius);
	background: var(--cloud-surface);
	color: var(--cloud-ink);
}
.memory-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 12px;
	padding: 18px 20px 12px;
	border-bottom: 1px solid var(--cloud-line);
}
.memory-heading h3 {
	margin: 0;
	font-size: 16px;
}
.memory-heading p {
	margin: 5px 0 0;
	font-size: 12px;
	color: var(--cloud-muted);
}
.memory-close {
	font-size: 24px;
	line-height: 24px;
}
.memory-body {
	overflow-y: auto;
	padding: 14px 20px 20px;
}
.memory-body p {
	line-height: 1.65;
}
.memory-note,
.memory-empty {
	font-size: 12px;
	color: var(--cloud-muted);
}
.memory-note {
	margin: 0 0 8px;
}
.memory-toggle {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	padding: 14px 0;
}
.memory-toggle strong {
	font-size: 13px;
}
.memory-toggle small {
	display: block;
	margin-top: 4px;
	color: var(--cloud-muted);
	font-size: 12px;
}
.memory-toggle input {
	width: 18px;
	height: 18px;
	flex-shrink: 0;
	accent-color: var(--cloud-primary);
}
.memory-form {
	margin: 8px 0 20px;
}
.memory-form > label {
	display: block;
	margin-bottom: 8px;
	font-size: 13px;
	font-weight: 600;
}
.memory-form textarea {
	box-sizing: border-box;
	width: 100%;
	resize: vertical;
	min-height: 80px;
	max-height: 180px;
	padding: 10px 12px;
	border: 1px solid var(--cloud-line);
	border-radius: 12px;
	font: inherit;
	font-size: 13px;
	line-height: 1.65;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
}
.memory-form textarea:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.memory-form-footer,
.memory-list-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	margin-top: 8px;
}
.memory-form-footer small {
	color: var(--cloud-muted);
}
.memory-form-error {
	margin: 8px 0;
	color: #a0452a;
	font-size: 12px;
}
.memory-search {
	display: grid;
	gap: 6px;
	margin-top: 12px;
	font-size: 12px;
	color: var(--cloud-muted);
}
.memory-search input {
	box-sizing: border-box;
	width: 100%;
	padding: 8px 10px;
	border: 1px solid var(--cloud-line);
	border-radius: 10px;
	color: var(--cloud-ink);
	background: var(--cloud-surface);
	font: inherit;
}
.memory-search input:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.memory-list-heading h4 {
	margin: 0;
	font-size: 13px;
}
.memory-list {
	margin: 10px 0 0;
	padding: 0;
	list-style: none;
}
.memory-list li {
	display: flex;
	align-items: flex-start;
	gap: 12px;
	padding: 12px 0;
	border-top: 1px solid var(--cloud-line);
}
.memory-list li p {
	margin: 0;
	flex: 1;
	min-width: 0;
	font-size: 13px;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
}
.memory-list li button {
	flex-shrink: 0;
}
button {
	padding: 5px 8px;
	border: 0;
	border-radius: 999px;
	background: transparent;
	color: var(--cloud-accent);
	font: inherit;
	font-size: 12px;
	cursor: pointer;
}
button:hover:not(:disabled) {
	background: var(--cloud-lilac);
}
button:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
button:disabled {
	opacity: 0.45;
	cursor: not-allowed;
}
.memory-primary {
	padding: 8px 14px;
	color: #fff;
	background: var(--cloud-primary);
}
.memory-primary:hover:not(:disabled) {
	background: var(--cloud-primary-dark);
}
.memory-error,
.memory-confirm {
	margin: 12px 0;
	padding: 10px 12px;
	border: 1px solid #e7cdbd;
	border-radius: 8px;
	background: #fcf6f0;
	font-size: 12px;
}
.memory-error {
	display: flex;
	align-items: center;
	gap: 8px;
}
.memory-error span {
	flex: 1;
	min-width: 0;
	overflow-wrap: anywhere;
}
.memory-status {
	font-size: 12px;
	color: var(--cloud-muted);
}
.memory-confirm p {
	margin: 0 0 8px;
}
.memory-danger {
	color: #a0452a;
}
</style>
