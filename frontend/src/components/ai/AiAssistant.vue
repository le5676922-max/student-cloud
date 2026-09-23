<template>
	<Teleport to="body">
		<div v-if="showAssistant" class="ai-agent-root">
			<Transition name="ai-panel">
				<section
					v-if="isOpen"
					class="ai-agent-panel"
					:class="{
						'is-file-over': isAgentFileOver,
						'is-resizing': isPanelResizing,
					}"
					:style="panelStyle"
					role="dialog"
					aria-label="小芽 AI 助手"
					@keydown.esc="closePanel"
					@dragenter.prevent.stop="handleAgentDragEnter"
					@dragover.prevent.stop="handleAgentDragOver"
					@dragleave.prevent.stop="handleAgentDragLeave"
					@drop.prevent.stop="handleAgentFileDrop"
				>
					<el-tooltip content="拖动调整聊天窗口大小" placement="right">
						<button
							type="button"
							class="ai-agent-resize-handle"
							aria-label="调整聊天窗口大小"
							@mousedown.stop="startPanelResize"
						>
							<i-lucide-grip />
						</button>
					</el-tooltip>
					<Transition name="ai-drop-dock">
						<div
							v-if="isAgentFileOver || isCatchingFile"
							class="ai-agent-drop-dock"
							:class="{ 'is-catching': isCatchingFile }"
							aria-live="polite"
						>
							<span class="ai-agent-drop-dock-mascot" aria-hidden="true">
								<AiMascotSprite
									compact
									:interactive="false"
									:action="mascotFileAction"
								/>
							</span>
							<span class="ai-agent-drop-dock-icon" aria-hidden="true">
								<i-lucide-check v-if="isCatchingFile" />
								<i-lucide-file-up v-else />
							</span>
							<span class="ai-agent-drop-dock-copy">
								<strong>{{
									isCatchingFile ? '接住了' : '松手交给小芽'
								}}</strong>
								<small>{{
									isCatchingFile ? '正在整理文件' : '稍后再选择存储或分析'
								}}</small>
							</span>
						</div>
					</Transition>
					<header class="ai-agent-header">
						<div class="ai-agent-title">
							<span class="ai-agent-title-icon" aria-hidden="true">
								<AiMascotSprite compact :interactive="false" />
							</span>
							<span>小芽</span>
						</div>

						<div class="ai-agent-actions">
							<el-tooltip content="清空对话" placement="bottom">
								<button
									type="button"
									class="ai-agent-icon-button"
									:disabled="
										isLoading || isAgentUploading || messages.length === 1
									"
									aria-label="清空对话"
									@click="clearMessages"
								>
									<i-lucide-trash-2 />
								</button>
							</el-tooltip>
							<el-tooltip content="关闭" placement="bottom">
								<button
									type="button"
									class="ai-agent-icon-button"
									aria-label="关闭助手"
									@click="closePanel"
								>
									<i-lucide-x />
								</button>
							</el-tooltip>
						</div>
					</header>

					<div
						ref="messageListRef"
						class="ai-agent-messages"
						aria-live="polite"
					>
						<div
							v-for="message in messages"
							:key="message.id"
							class="ai-agent-message-row"
							:class="`is-${message.role}`"
						>
							<span class="ai-agent-avatar" aria-hidden="true">
								<i-lucide-bot v-if="message.role === 'assistant'" />
								<i-lucide-user v-else />
							</span>
							<div
								class="ai-agent-message"
								:class="{ 'is-error': message.error }"
							>
								<div v-if="message.tools?.length" class="ai-agent-tools">
									<span
										v-for="tool in message.tools"
										:key="`${message.id}-${tool.name}-${tool.summary}`"
										class="ai-agent-tool"
										:class="{ 'is-failed': !tool.success }"
									>
										<i-lucide-check-circle v-if="tool.success" />
										<i-lucide-alert-circle v-else />
										{{ tool.summary }}
									</span>
								</div>
								<div
									v-if="message.role === 'assistant'"
									class="ai-agent-response"
									v-html="message.renderedContent"
								></div>
								<template v-else>
									<div
										v-if="message.attachments?.length"
										class="ai-agent-message-files"
									>
										<span v-for="file in message.attachments" :key="file.name">
											<i-lucide-file-text />
											{{ file.name }}
										</span>
									</div>
									{{ message.content }}
								</template>
								<details v-if="message.citations?.length" class="ai-material-citations">
									<summary>本轮资料依据（{{ message.citations.length }} 个片段）</summary>
									<article v-for="citation in message.citations" :key="citation.reference">
										<strong>[{{ citation.reference }}] {{ citation.name }} · 文本片段 {{ citation.segment }}</strong>
										<p>{{ citation.excerpt }}</p>
										<router-link :to="{ path: '/learning', query: { courseId: citation.courseId } }">查看所属课程</router-link>
									</article>
								</details>
							</div>
						</div>

						<div v-if="isLoading" class="ai-agent-message-row is-assistant">
							<span class="ai-agent-avatar" aria-hidden="true">
								<i-lucide-bot />
							</span>
							<div
								class="ai-agent-message ai-agent-loading"
								aria-label="Agent 正在处理"
							>
								<span></span>
								<span></span>
								<span></span>
								<small>正在分析并查询</small>
							</div>
						</div>
						<div
							v-if="uploadIntent"
							class="ai-agent-message-row is-assistant is-upload-action"
						>
							<span class="ai-agent-avatar" aria-hidden="true">
								<i-lucide-sprout />
							</span>
							<div class="ai-agent-upload-card">
								<div class="ai-agent-upload-card-title">
									<div>
										<strong
											>我接住了 {{ uploadIntent.files.length }} 个文件</strong
										>
										<small
											>共 {{ formatFileSize(uploadIntent.totalSize) }}</small
										>
									</div>
									<i-lucide-files />
								</div>

								<div class="ai-agent-upload-file-summary">
									<span
										v-for="file in uploadIntent.files.slice(0, 3)"
										:key="`${file.name}-${file.size}-${file.lastModified}`"
									>
										<i-lucide-file />
										{{ file.name }}
									</span>
									<small v-if="uploadIntent.files.length > 3">
										还有 {{ uploadIntent.files.length - 3 }} 个文件
									</small>
								</div>

								<template v-if="uploadIntent.phase === 'choose-purpose'">
									<p>你希望我怎么处理这些文件？</p>
									<div class="ai-agent-upload-actions">
										<button
											type="button"
											class="ai-agent-action-button is-primary"
											@click="prepareCloudUpload"
										>
											<i-lucide-upload-cloud />
											存入云盘
										</button>
										<button
											type="button"
											class="ai-agent-action-button"
											@click="analyzeDroppedFiles"
										>
											<i-lucide-sparkles />
											分析文件
										</button>
										<button
											type="button"
											class="ai-agent-action-button is-quiet"
											@click="cancelUploadIntent"
										>
											<i-lucide-x />
											取消
										</button>
									</div>
								</template>

								<div
									v-else-if="uploadIntent.phase === 'loading-targets'"
									class="ai-agent-upload-loading"
								>
									<i-lucide-loader-2 />
									正在检查你有权限使用的云盘
								</div>

								<template v-else-if="uploadIntent.phase === 'configure'">
									<label class="ai-agent-upload-field">
										<span>存储到</span>
										<el-select
											v-model="uploadIntent.storageKey"
											placeholder="选择云盘"
										>
											<el-option
												v-for="target in uploadIntent.targets"
												:key="target.key"
												:label="target.name"
												:value="target.key"
											/>
										</el-select>
									</label>
									<label class="ai-agent-upload-field">
										<span>目标目录</span>
										<el-input
											v-model="uploadIntent.path"
											placeholder="例如：/课程资料"
											maxlength="255"
										/>
									</label>
									<p class="ai-agent-upload-notice">
										执行前会再次校验上传权限；同名文件按目标存储源规则处理。
									</p>
									<p v-if="uploadIntent.error" class="ai-agent-upload-error">
										{{ uploadIntent.error }}
									</p>
									<div class="ai-agent-upload-actions">
										<button
											type="button"
											class="ai-agent-action-button is-primary"
											:disabled="!uploadIntent.storageKey"
											@click="confirmCloudUpload"
										>
											<i-lucide-check />
											确认上传
										</button>
										<button
											type="button"
											class="ai-agent-action-button is-quiet"
											@click="uploadIntent.phase = 'choose-purpose'"
										>
											<i-lucide-arrow-left />
											返回
										</button>
									</div>
								</template>

								<div
									v-else-if="uploadIntent.phase === 'uploading'"
									class="ai-agent-upload-progress"
								>
									<div>
										<span>正在存储 {{ uploadIntent.currentFile }}</span>
										<strong>
											{{ uploadIntent.completed }}/{{
												uploadIntent.files.length
											}}
										</strong>
									</div>
									<el-progress
										:percentage="agentUploadProgress"
										:stroke-width="8"
										:show-text="false"
									/>
								</div>
							</div>
						</div>
					</div>

					<footer class="ai-agent-composer">
						<input
							ref="fileInputRef"
							type="file"
							class="ai-agent-file-input"
							:accept="ATTACHMENT_ACCEPT"
							multiple
							@change="selectAttachments"
						/>
						<div v-if="selectedFiles.length" class="ai-agent-selected-files">
							<span
								v-for="(file, index) in selectedFiles"
								:key="`${file.name}-${file.size}-${file.lastModified}`"
							>
								<i-lucide-file-text />
								<span class="ai-agent-selected-file-name">{{ file.name }}</span>
								<small>{{ formatFileSize(file.size) }}</small>
								<button
									type="button"
									:aria-label="`移除 ${file.name}`"
									@click="removeAttachment(index)"
								>
									<i-lucide-x />
								</button>
							</span>
						</div>
						<div class="ai-agent-composer-row">
							<el-tooltip
								content="添加附件，内容将发送给 AI 分析"
								placement="top"
							>
								<button
									type="button"
									class="ai-agent-attach-button"
									:disabled="
										isLoading ||
										isAgentUploading ||
										selectedFiles.length >= MAX_ATTACHMENT_FILES
									"
									aria-label="添加附件"
									@click="openAttachmentPicker"
								>
									<i-lucide-paperclip />
								</button>
							</el-tooltip>
							<el-input
								ref="inputRef"
								v-model="inputMessage"
								type="textarea"
								resize="none"
								maxlength="2000"
								:disabled="isLoading || isAgentUploading"
								:autosize="{ minRows: 2, maxRows: 4 }"
								placeholder="输入问题，或添加附件"
								aria-label="输入问题"
								@keydown.enter.exact.prevent="sendMessage"
							/>
							<el-tooltip content="发送" placement="top">
								<button
									type="button"
									class="ai-agent-send-button"
									:disabled="!canSend"
									aria-label="发送消息"
									@click="sendMessage"
								>
									<i-lucide-send />
								</button>
							</el-tooltip>
						</div>
					</footer>
				</section>
			</Transition>

			<el-tooltip
				v-if="!isOpen"
				content="小芽"
				placement="left"
				:disabled="isAgentFileOver || isCatchingFile"
			>
				<button
					type="button"
					class="ai-agent-floating-button"
					:class="{
						'is-dragging': isDragging,
						'is-hovered': isMascotHovered,
						'is-file-over': isAgentFileOver,
						'is-catching': isCatchingFile,
					}"
					:style="floatingButtonStyle"
					aria-label="打开小芽"
					@click="handleFloatingClick"
					@pointerenter="handleMascotEnter"
					@pointerleave="handleMascotLeave"
					@pointerdown="startFloatingDrag"
					@pointermove="moveFloatingButton"
					@pointerup="finishFloatingDrag"
					@pointercancel="finishFloatingDrag"
					@dragenter.prevent.stop="handleAgentDragEnter"
					@dragover.prevent.stop="handleAgentDragOver"
					@dragleave.prevent.stop="handleAgentDragLeave"
					@drop.prevent.stop="handleAgentFileDrop"
				>
					<span class="ai-agent-mascot-stage" aria-hidden="true">
						<span
							v-if="isAgentFileOver || isCatchingFile"
							class="ai-agent-catch-file"
							:class="{ 'is-catching': isCatchingFile }"
						>
							<i-lucide-file />
						</span>
						<AiMascotSprite
							:action="mascotFileAction"
							:hovered="isMascotHovered"
							:dragging="isDragging"
							:drag-direction="dragDirection"
						/>
					</span>
					<span
						v-if="isAgentFileOver"
						class="ai-agent-drop-hint"
						aria-hidden="true"
					>
						<i-lucide-mouse-pointer-2 />
						<span>松手</span>
					</span>
				</button>
			</el-tooltip>
		</div>
	</Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import {
	chatWithAiAgentFilesReq,
	chatWithAiAgentReq,
} from '~/api/home/ai-agent'
import AiMascotSprite from '~/components/ai/AiMascotSprite.vue'
import {
	clampPanelSize as clampStoredPanelSize,
	restorePanelSize as restoreStoredPanelSize,
} from '~/components/ai/ai-assistant-panel.mjs'
import { getSourceListReq, loadStorageConfigReq } from '~/api/home/home'
import useFileUpload from '~/composables/file/useFileUpload'
import useStorageConfigStore from '~/stores/storage-config'

const WELCOME_MESSAGE =
	'你好，我是小芽，学生云盘的 AI 助手。你可以把桌面文件拖给我，再选择存入云盘或交给我分析。'
const MAX_ATTACHMENT_FILES = 3
const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
const MAX_AGENT_UPLOAD_FILES = 20
const SUPPORTED_ATTACHMENT_EXTENSIONS = new Set([
	'txt',
	'md',
	'markdown',
	'json',
	'csv',
	'xml',
	'yaml',
	'yml',
	'properties',
	'log',
	'java',
	'kt',
	'js',
	'jsx',
	'ts',
	'tsx',
	'vue',
	'css',
	'scss',
	'less',
	'html',
	'htm',
	'sql',
	'py',
	'go',
	'rs',
	'c',
	'h',
	'cpp',
	'hpp',
	'sh',
	'ps1',
	'pdf',
	'docx',
	'xls',
	'xlsx',
])
const ATTACHMENT_ACCEPT = Array.from(SUPPORTED_ATTACHMENT_EXTENSIONS)
	.map((extension) => `.${extension}`)
	.join(',')
const FLOATING_POSITION_KEY = 'student-cloud-ai-position'
const PANEL_SIZE_KEY = 'student-cloud-ai-panel-size'
const FLOATING_BUTTON_WIDTH = 96
const FLOATING_BUTTON_HEIGHT = 104
const VIEWPORT_PADDING = 12
const DRAG_THRESHOLD = 4
const ALLOWED_ASSISTANT_TAGS = new Set([
	'p',
	'br',
	'strong',
	'em',
	'del',
	'ul',
	'ol',
	'li',
	'code',
	'pre',
	'blockquote',
	'h3',
	'h4',
	'a',
	'hr',
	'table',
	'thead',
	'tbody',
	'tr',
	'th',
	'td',
])

const route = useRoute()
const router = useRouter()
const storageConfigStore = useStorageConfigStore()
const { silentUploadFile } = useFileUpload()

const isOpen = ref(false)
const isLoading = ref(false)
const inputMessage = ref('')
const inputRef = ref()
const fileInputRef = ref()
const selectedFiles = ref([])
const uploadIntent = ref(null)
const isAgentFileOver = ref(false)
const isCatchingFile = ref(false)
const messageListRef = ref()
const floatingPosition = ref(null)
const panelSize = ref(null)
const isDragging = ref(false)
const isMascotHovered = ref(false)
const dragDirection = ref('right')
const isPanelResizing = ref(false)
let dragState = null
let panelResizeState = null
let fileDragDepth = 0
let catchAnimationTimer = null
let ignoreNextClick = false
const messages = ref([
	createMessage('assistant', WELCOME_MESSAGE, { welcome: true }),
])

const mascotFileAction = computed(() => {
	if (isCatchingFile.value) {
		return 'catch'
	}
	return isAgentFileOver.value ? 'receive' : ''
})

const showAssistant = computed(() => {
	const hiddenPage = ['/login', '/install', '/sso'].some((path) =>
		route.path.startsWith(path)
	)
	return (
		!hiddenPage && Boolean(storageConfigStore.loginInfo.isLogin || readToken())
	)
})

const isAgentUploading = computed(
	() => uploadIntent.value?.phase === 'uploading'
)

const canSend = computed(
	() =>
		(inputMessage.value.trim().length > 0 || selectedFiles.value.length > 0) &&
		!isLoading.value &&
		!isAgentUploading.value
)

const agentUploadProgress = computed(() => {
	const intent = uploadIntent.value
	if (!intent?.files?.length) {
		return 0
	}
	return Math.round((intent.completed / intent.files.length) * 100)
})

const floatingButtonStyle = computed(() => {
	if (!floatingPosition.value) {
		return undefined
	}
	return {
		left: `${floatingPosition.value.x}px`,
		top: `${floatingPosition.value.y}px`,
		right: 'auto',
		bottom: 'auto',
	}
})

const panelStyle = computed(() => {
	if (!panelSize.value) {
		return undefined
	}
	return {
		width: `${panelSize.value.width}px`,
		height: `${panelSize.value.height}px`,
	}
})

watch(
	() => messages.value.length,
	() => scrollToBottom()
)

watch(showAssistant, (visible) => {
	if (!visible) {
		isOpen.value = false
	}
})

onMounted(() => {
	restoreFloatingPosition()
	restorePanelSize()
	window.addEventListener('resize', keepAssistantInViewport)
})

onBeforeUnmount(() => {
	window.removeEventListener('resize', keepAssistantInViewport)
	window.removeEventListener('mousemove', movePanelResize)
	window.removeEventListener('mouseup', finishPanelResize)
	if (catchAnimationTimer) {
		window.clearTimeout(catchAnimationTimer)
	}
})

function renderAssistantContent(content) {
	const parsed = marked.parse(String(content || ''), {
		breaks: true,
		gfm: true,
	})
	return sanitizeAssistantHtml(parsed)
}

function sanitizeAssistantHtml(html) {
	const documentNode = new DOMParser().parseFromString(
		`<div id="ai-response-root">${html}</div>`,
		'text/html'
	)
	const root = documentNode.querySelector('#ai-response-root')

	root?.querySelectorAll('*').forEach((element) => {
		const tagName = element.tagName.toLowerCase()
		if (!ALLOWED_ASSISTANT_TAGS.has(tagName)) {
			element.replaceWith(
				documentNode.createTextNode(element.textContent || '')
			)
			return
		}

		const href = tagName === 'a' ? element.getAttribute('href') : null
		Array.from(element.attributes).forEach((attribute) =>
			element.removeAttribute(attribute.name)
		)

		if (tagName === 'a' && isSafeAssistantLink(href)) {
			element.setAttribute('href', href)
			element.setAttribute('target', '_blank')
			element.setAttribute('rel', 'noopener noreferrer')
		}
	})

	return root?.innerHTML || ''
}

function isSafeAssistantLink(href) {
	if (!href) {
		return false
	}
	try {
		const url = new URL(href, window.location.origin)
		return url.protocol === 'http:' || url.protocol === 'https:'
	} catch {
		return false
	}
}
function createMessage(role, content, options = {}) {
	return {
		id: `${Date.now()}-${Math.random()}`,
		role,
		content,
		renderedContent:
			role === 'assistant' ? renderAssistantContent(content) : undefined,
		...options,
	}
}

function readToken() {
	const localToken = window.localStorage.getItem('tongligong-student-cloud-token')
	if (localToken) {
		return localToken
	}
	const tokenCookie = document.cookie
		.split('; ')
		.find((item) => item.startsWith('tongligong-student-cloud-token='))
	return tokenCookie?.split('=')[1]
}

function handleFloatingClick() {
	if (ignoreNextClick) {
		return
	}
	openPanel()
}

function handleMascotEnter() {
	isMascotHovered.value = true
}

function handleMascotLeave() {
	isMascotHovered.value = false
}
function startFloatingDrag(event) {
	if (event.button !== 0) {
		return
	}

	const rect = event.currentTarget.getBoundingClientRect()
	dragState = {
		pointerId: event.pointerId,
		startX: event.clientX,
		startY: event.clientY,
		lastClientX: event.clientX,
		originX: rect.left,
		originY: rect.top,
		moved: false,
		target: event.currentTarget,
	}
	isDragging.value = true
	event.currentTarget.setPointerCapture?.(event.pointerId)
}

function moveFloatingButton(event) {
	if (!dragState || dragState.pointerId !== event.pointerId) {
		return
	}

	const movementX = event.clientX - dragState.lastClientX
	if (Math.abs(movementX) > 1) {
		dragDirection.value = movementX < 0 ? 'left' : 'right'
	}
	dragState.lastClientX = event.clientX

	const deltaX = event.clientX - dragState.startX
	const deltaY = event.clientY - dragState.startY
	if (!dragState.moved && Math.hypot(deltaX, deltaY) < DRAG_THRESHOLD) {
		return
	}

	dragState.moved = true
	floatingPosition.value = clampFloatingPosition(
		dragState.originX + deltaX,
		dragState.originY + deltaY
	)
	event.preventDefault()
}

function finishFloatingDrag(event) {
	if (!dragState || dragState.pointerId !== event.pointerId) {
		return
	}

	if (dragState.moved) {
		ignoreNextClick = true
		saveFloatingPosition()
		window.setTimeout(() => {
			ignoreNextClick = false
		}, 0)
	}
	dragState.target.releasePointerCapture?.(event.pointerId)
	dragState = null
	isDragging.value = false
}

function clampFloatingPosition(x, y) {
	const maxX = Math.max(
		VIEWPORT_PADDING,
		window.innerWidth - FLOATING_BUTTON_WIDTH - VIEWPORT_PADDING
	)
	const maxY = Math.max(
		VIEWPORT_PADDING,
		window.innerHeight - FLOATING_BUTTON_HEIGHT - VIEWPORT_PADDING
	)
	return {
		x: Math.min(Math.max(x, VIEWPORT_PADDING), maxX),
		y: Math.min(Math.max(y, VIEWPORT_PADDING), maxY),
	}
}

function restoreFloatingPosition() {
	try {
		const savedPosition = JSON.parse(
			window.localStorage.getItem(FLOATING_POSITION_KEY)
		)
		if (
			Number.isFinite(savedPosition?.x) &&
			Number.isFinite(savedPosition?.y)
		) {
			floatingPosition.value = clampFloatingPosition(
				savedPosition.x,
				savedPosition.y
			)
		}
	} catch {
		window.localStorage.removeItem(FLOATING_POSITION_KEY)
	}
}

function saveFloatingPosition() {
	if (floatingPosition.value) {
		window.localStorage.setItem(
			FLOATING_POSITION_KEY,
			JSON.stringify(floatingPosition.value)
		)
	}
}

function keepFloatingButtonInViewport() {
	if (!floatingPosition.value) {
		return
	}
	floatingPosition.value = clampFloatingPosition(
		floatingPosition.value.x,
		floatingPosition.value.y
	)
	saveFloatingPosition()
}

function startPanelResize(event) {
	if (window.innerWidth <= 640) {
		return
	}
	if (event.button !== 0) {
		return
	}

	const panel = event.currentTarget.closest('.ai-agent-panel')
	const rect = panel?.getBoundingClientRect()
	if (!rect) {
		return
	}

	panelResizeState = {
		pointerId: event.pointerId,
		startX: event.clientX,
		startY: event.clientY,
		width: rect.width,
		height: rect.height,
	}
	isPanelResizing.value = true
	window.addEventListener('mousemove', movePanelResize)
	window.addEventListener('mouseup', finishPanelResize)
	event.preventDefault()
}

function movePanelResize(event) {
	if (!panelResizeState) {
		return
	}

	panelSize.value = clampPanelSize(
		panelResizeState.width - (event.clientX - panelResizeState.startX),
		panelResizeState.height - (event.clientY - panelResizeState.startY)
	)
	event.preventDefault()
}

function finishPanelResize() {
	if (!panelResizeState) {
		return
	}

	window.removeEventListener('mousemove', movePanelResize)
	window.removeEventListener('mouseup', finishPanelResize)
	panelResizeState = null
	isPanelResizing.value = false
	savePanelSize()
}

function clampPanelSize(width, height) {
	return clampStoredPanelSize(width, height, {
		width: window.innerWidth,
		height: window.innerHeight,
		padding: VIEWPORT_PADDING,
	})
}

function restorePanelSize() {
	try {
		const savedSize = JSON.parse(window.localStorage.getItem(PANEL_SIZE_KEY))
		const restoredSize = restoreStoredPanelSize(savedSize, {
			width: window.innerWidth,
			height: window.innerHeight,
			padding: VIEWPORT_PADDING,
		})
		if (restoredSize) {
			panelSize.value = restoredSize
		}
	} catch {
		window.localStorage.removeItem(PANEL_SIZE_KEY)
	}
}

function savePanelSize() {
	if (panelSize.value) {
		window.localStorage.setItem(PANEL_SIZE_KEY, JSON.stringify(panelSize.value))
	}
}

function keepAssistantInViewport() {
	keepFloatingButtonInViewport()
	if (panelSize.value) {
		panelSize.value = clampPanelSize(
			panelSize.value.width,
			panelSize.value.height
		)
		savePanelSize()
	}
}

function openPanel() {
	isOpen.value = true
	nextTick(() => {
		scrollToBottom()
		inputRef.value?.focus()
	})
}

function closePanel() {
	isOpen.value = false
}

function clearMessages() {
	messages.value = [
		createMessage('assistant', WELCOME_MESSAGE, { welcome: true }),
	]
	inputMessage.value = ''
	selectedFiles.value = []
	uploadIntent.value = null
	isAgentFileOver.value = false
	fileDragDepth = 0
	if (fileInputRef.value) {
		fileInputRef.value.value = ''
	}
}

function isFileDrag(event) {
	return Array.from(event.dataTransfer?.types || []).includes('Files')
}

function handleAgentDragEnter(event) {
	if (!isFileDrag(event)) {
		return
	}
	fileDragDepth += 1
	isAgentFileOver.value = true
}

function handleAgentDragOver(event) {
	if (!isFileDrag(event)) {
		return
	}
	isAgentFileOver.value = true
	if (event.dataTransfer) {
		event.dataTransfer.dropEffect = 'copy'
	}
}

function handleAgentDragLeave() {
	if (!isAgentFileOver.value) {
		return
	}
	fileDragDepth = Math.max(0, fileDragDepth - 1)
	if (fileDragDepth === 0) {
		isAgentFileOver.value = false
	}
}

function handleAgentFileDrop(event) {
	fileDragDepth = 0
	isAgentFileOver.value = false

	const droppedFiles = Array.from(event.dataTransfer?.files || [])
	if (droppedFiles.length === 0) {
		return
	}
	if (uploadIntent.value?.phase === 'uploading') {
		ElMessage.warning('小芽正在上传上一批文件，请稍候')
		return
	}

	const files = droppedFiles.slice(0, MAX_AGENT_UPLOAD_FILES)
	if (droppedFiles.length > MAX_AGENT_UPLOAD_FILES) {
		ElMessage.warning(`小芽每次最多处理 ${MAX_AGENT_UPLOAD_FILES} 个文件`)
	}

	const revealPanelAfterCatch = !isOpen.value
	isCatchingFile.value = true
	if (catchAnimationTimer) {
		window.clearTimeout(catchAnimationTimer)
	}
	catchAnimationTimer = window.setTimeout(() => {
		isCatchingFile.value = false
		catchAnimationTimer = null
		if (revealPanelAfterCatch) {
			openPanel()
		}
		scrollToBottom()
	}, 680)

	uploadIntent.value = {
		files,
		totalSize: files.reduce((total, file) => total + file.size, 0),
		phase: 'choose-purpose',
		targets: [],
		storageKey: '',
		path: '/',
		completed: 0,
		currentFile: '',
		error: '',
	}
	if (!revealPanelAfterCatch) {
		scrollToBottom()
	}
}

async function prepareCloudUpload() {
	const intent = uploadIntent.value
	if (!intent) {
		return
	}

	intent.phase = 'loading-targets'
	intent.error = ''
	try {
		const response = await getSourceListReq()
		const sources = Array.isArray(response.data) ? response.data : []
		const targetResults = await Promise.all(
			sources.map(async (source) => {
				try {
					const configResponse = await loadStorageConfigReq({
						storageKey: source.key,
						path: '/',
					})
					if (configResponse.data?.permission?.upload !== true) {
						return null
					}
					return {
						key: source.key,
						name: source.name,
						rootConfig: configResponse.data,
					}
				} catch {
					return null
				}
			})
		)
		const targets = targetResults.filter(Boolean)
		intent.targets = targets
		intent.phase = 'configure'
		if (targets.length === 0) {
			intent.error = '当前账户没有可上传的存储源，请联系管理员授权。'
			return
		}

		const currentStorageKey = String(route.params.storageKey || '')
		const defaultTarget =
			targets.find((target) => target.key === currentStorageKey) || targets[0]
		intent.storageKey = defaultTarget.key
		intent.path =
			defaultTarget.key === currentStorageKey ? currentRouteDirectory() : '/'
	} catch (error) {
		intent.phase = 'configure'
		intent.error = extractAgentError(error, '无法读取可用存储源')
	}
}

async function analyzeDroppedFiles() {
	const intent = uploadIntent.value
	if (!intent || isLoading.value) {
		return
	}

	const supportedFiles = intent.files
		.filter((file) => {
			const extension = file.name.split('.').pop()?.toLowerCase()
			return (
				SUPPORTED_ATTACHMENT_EXTENSIONS.has(extension) &&
				file.size <= MAX_ATTACHMENT_BYTES
			)
		})
		.slice(0, MAX_ATTACHMENT_FILES)

	if (supportedFiles.length === 0) {
		ElMessage.warning('这些文件不适合作为 AI 附件，可以选择存入云盘')
		return
	}
	if (supportedFiles.length < intent.files.length) {
		ElMessage.warning('AI 分析最多读取 3 个受支持且不超过 5 MB 的文件')
	}

	selectedFiles.value = supportedFiles
	uploadIntent.value = null
	inputMessage.value = '请分析这些附件，并用简洁的方式告诉我主要内容。'
	await nextTick()
	await sendMessage()
}

function cancelUploadIntent() {
	uploadIntent.value = null
	isAgentFileOver.value = false
	fileDragDepth = 0
}

async function confirmCloudUpload() {
	const intent = uploadIntent.value
	if (!intent || intent.phase !== 'configure' || !intent.storageKey) {
		return
	}
	if (!readToken()) {
		intent.error = '登录状态已失效，请重新登录后再上传。'
		return
	}

	intent.error = ''
	let targetPath
	try {
		targetPath = normalizeAgentUploadPath(intent.path)
	} catch (error) {
		intent.error = error.message
		return
	}

	const target = intent.targets.find((item) => item.key === intent.storageKey)
	if (!target) {
		intent.error = '请选择一个有上传权限的存储源。'
		return
	}

	try {
		const configResponse = await loadStorageConfigReq({
			storageKey: target.key,
			path: targetPath,
		})
		const folderConfig = configResponse.data
		if (folderConfig?.permission?.upload !== true) {
			throw new Error('当前账户没有目标目录的上传权限')
		}

		intent.phase = 'uploading'
		intent.completed = 0
		intent.currentFile = ''
		await router.push(buildStorageRoute(target.key, targetPath))
		storageConfigStore.updateFolderConfig(folderConfig)

		const failures = []
		for (const file of intent.files) {
			intent.currentFile = file.name
			try {
				await silentUploadFile(file, {
					storageKey: target.key,
					path: targetPath,
					folderConfig,
					showLoading: false,
					showFeedback: false,
				})
			} catch (error) {
				failures.push({ file, error })
			}
			intent.completed += 1
		}

		const successCount = intent.files.length - failures.length
		const summary = failures.length
			? `已将 ${successCount} 个文件存入“${target.name}”的 ${targetPath}，另有 ${failures.length} 个文件上传失败。`
			: `已将 ${successCount} 个文件存入“${target.name}”的 ${targetPath}。`
		messages.value.push(
			createMessage('assistant', summary, {
				tools: [
					{
						name: 'upload_files',
						success: failures.length === 0,
						summary: failures.length
							? `上传完成：成功 ${successCount}，失败 ${failures.length}`
							: `已上传 ${successCount} 个文件`,
					},
				],
			})
		)
		uploadIntent.value = null
	} catch (error) {
		intent.phase = 'configure'
		intent.error = extractAgentError(error, '上传前权限校验失败')
	}
}

function currentRouteDirectory() {
	const fullpath = route.params.fullpath
	if (Array.isArray(fullpath) && fullpath.length > 0) {
		return `/${fullpath.join('/')}`
	}
	if (typeof fullpath === 'string' && fullpath) {
		return `/${fullpath}`
	}
	return '/'
}

function normalizeAgentUploadPath(value) {
	let path = String(value || '/')
		.trim()
		.split(String.fromCharCode(92))
		.join('/')
	if (!path.startsWith('/')) {
		path = `/${path}`
	}
	path = path.replace(/\/+/g, '/')
	const segments = path.split('/').filter(Boolean)
	if (segments.some((segment) => segment === '.' || segment === '..')) {
		throw new Error('目标目录不能包含 . 或 ..')
	}
	if (segments.some((segment) => /[?#\0]/.test(segment))) {
		throw new Error('目标目录包含不支持的字符')
	}
	const normalized = segments.length ? `/${segments.join('/')}` : '/'
	if (normalized.length > 255) {
		throw new Error('目标目录不能超过 255 个字符')
	}
	return normalized
}

function buildStorageRoute(storageKey, path) {
	const encodedStorageKey = encodeURIComponent(storageKey)
	const encodedPath = path
		.split('/')
		.filter(Boolean)
		.map((segment) => encodeURIComponent(segment))
		.join('/')
	return encodedPath
		? `/${encodedStorageKey}/${encodedPath}`
		: `/${encodedStorageKey}`
}

function extractAgentError(error, fallback) {
	return (
		error?.data?.msg || error?.response?.data?.msg || error?.message || fallback
	)
}

function openAttachmentPicker() {
	fileInputRef.value?.click()
}

function selectAttachments(event) {
	const incomingFiles = Array.from(event.target.files || [])
	for (const file of incomingFiles) {
		if (selectedFiles.value.length >= MAX_ATTACHMENT_FILES) {
			ElMessage.warning(`每次最多添加 ${MAX_ATTACHMENT_FILES} 个附件`)
			break
		}
		if (file.size > MAX_ATTACHMENT_BYTES) {
			ElMessage.warning(`${file.name} 超过 5 MB`)
			continue
		}
		const extension = file.name.split('.').pop()?.toLowerCase()
		if (!SUPPORTED_ATTACHMENT_EXTENSIONS.has(extension)) {
			ElMessage.warning(`暂不支持 ${file.name} 的文件格式`)
			continue
		}
		const duplicated = selectedFiles.value.some(
			(item) =>
				item.name === file.name &&
				item.size === file.size &&
				item.lastModified === file.lastModified
		)
		if (!duplicated) {
			selectedFiles.value.push(file)
		}
	}
	event.target.value = ''
}

function removeAttachment(index) {
	selectedFiles.value.splice(index, 1)
}

function formatFileSize(bytes) {
	if (bytes < 1024) {
		return `${bytes} B`
	}
	if (bytes < 1024 * 1024) {
		return `${(bytes / 1024).toFixed(1)} KB`
	}
	return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function sendMessage() {
	const filesToSend = [...selectedFiles.value]
	const inputContent = inputMessage.value.trim()
	if ((!inputContent && filesToSend.length === 0) || isLoading.value) {
		return
	}
	const content = inputContent || '请分析这些附件。'

	const history = messages.value
		.filter((message) => !message.welcome && !message.error)
		.slice(-8)
		.map(({ role, content: messageContent }) => ({
			role,
			content: messageContent,
		}))

	messages.value.push(
		createMessage('user', content, {
			attachments: filesToSend.map((file) => ({ name: file.name })),
		})
	)
	inputMessage.value = ''
	selectedFiles.value = []
	isLoading.value = true

	const request = {
		message: content,
		history,
		pagePath: route.fullPath,
	}

	try {
		const response = filesToSend.length
			? await chatWithAiAgentFilesReq(request, filesToSend)
			: await chatWithAiAgentReq(request)
		messages.value.push(
			createMessage('assistant', response.data.answer, {
				tools: response.data.tools || [],
				citations: response.data.citations || [],
			})
		)
	} catch (error) {
		const errorMessage =
			error?.data?.msg ||
			error?.response?.data?.msg ||
			'暂时无法连接 AI 助手，请稍后重试。'
		messages.value.push(
			createMessage('assistant', errorMessage, { error: true })
		)
	} finally {
		isLoading.value = false
		nextTick(() => inputRef.value?.focus())
	}
}
function scrollToBottom() {
	nextTick(() => {
		const messageList = messageListRef.value
		if (messageList) {
			messageList.scrollTop = messageList.scrollHeight
		}
	})
}
</script>

<style scoped>
.ai-material-citations { margin-top: 12px; font-size: 12px; }
.ai-material-citations summary { cursor: pointer; color: #2563eb; }
.ai-material-citations article { border-top: 1px solid #dbeafe; padding-top: 10px; margin-top: 10px; }
.ai-material-citations p { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 180px; overflow-y: auto; }
.ai-agent-panel {
	position: fixed;
	right: 24px;
	bottom: 88px;
	z-index: 2000;
	display: flex;
	width: min(380px, calc(100vw - 32px));
	height: min(520px, calc(100dvh - 120px));
	flex-direction: column;
	overflow: hidden;
	color: #1f2937;
	background: #ffffff;
	border: 1px solid #dfe3e8;
	border-radius: 8px;
	box-shadow: 0 18px 48px rgb(15 23 42 / 18%);
}

.ai-agent-panel.is-resizing {
	user-select: none;
}

.ai-agent-resize-handle {
	position: absolute;
	top: 8px;
	left: 8px;
	z-index: 8;
	display: grid;
	width: 30px;
	height: 30px;
	padding: 0;
	color: #64748b;
	background: #f8fafc;
	border: 1px solid #e2e8f0;
	border-radius: 5px;
	cursor: nwse-resize;
	place-items: center;
	touch-action: none;
}

.ai-agent-resize-handle:hover {
	color: #2563eb;
	background: #eff6ff;
	border-color: #bfdbfe;
}

.ai-agent-resize-handle svg {
	width: 15px;
	height: 15px;
}

.ai-agent-header {
	display: flex;
	min-height: 56px;
	align-items: center;
	justify-content: space-between;
	padding: 0 12px 0 48px;
	background: #ffffff;
	border-bottom: 1px solid #e5e7eb;
}

.ai-agent-title,
.ai-agent-actions {
	display: flex;
	align-items: center;
}

.ai-agent-title {
	gap: 9px;
	min-width: 0;
	font-size: 15px;
	font-weight: 600;
}

.ai-agent-title-icon,
.ai-agent-avatar {
	display: inline-flex;
	flex: 0 0 auto;
	align-items: center;
	justify-content: center;
}

.ai-agent-title-icon {
	width: 30px;
	height: 33px;
}

.ai-agent-title-icon svg,
.ai-agent-avatar svg,
.ai-agent-icon-button svg,
.ai-agent-attach-button svg,
.ai-agent-send-button svg,
.ai-agent-floating-button svg {
	width: 20px;
	height: 20px;
}

.ai-agent-actions {
	gap: 4px;
}

.ai-agent-icon-button,
.ai-agent-attach-button,
.ai-agent-send-button,
.ai-agent-floating-button {
	display: inline-flex;
	align-items: center;
	justify-content: center;
	border: 0;
	cursor: pointer;
}

.ai-agent-icon-button {
	width: 34px;
	height: 34px;
	color: #6b7280;
	background: transparent;
	border-radius: 6px;
}

.ai-agent-icon-button:hover:not(:disabled) {
	color: #111827;
	background: #f3f4f6;
}

.ai-agent-icon-button:disabled,
.ai-agent-attach-button:disabled,
.ai-agent-send-button:disabled {
	cursor: not-allowed;
	opacity: 0.45;
}

.ai-agent-messages {
	flex: 1;
	min-height: 0;
	padding: 16px;
	overflow-y: auto;
	background: #f8fafc;
}

.ai-agent-message-row {
	display: flex;
	align-items: flex-start;
	gap: 8px;
	margin-bottom: 14px;
}

.ai-agent-message-row.is-user {
	flex-direction: row-reverse;
}

.ai-agent-avatar {
	width: 28px;
	height: 28px;
	color: #4b5563;
	background: #ffffff;
	border: 1px solid #dfe3e8;
	border-radius: 7px;
}

.ai-agent-avatar svg {
	width: 16px;
	height: 16px;
}

.ai-agent-message {
	max-width: calc(100% - 44px);
	padding: 9px 11px;
	font-size: 14px;
	line-height: 1.6;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
	background: #ffffff;
	border: 1px solid #e5e7eb;
	border-radius: 8px;
}

.ai-agent-message-files {
	display: flex;
	flex-direction: column;
	gap: 4px;
	margin-bottom: 6px;
}

.ai-agent-message-files span {
	display: flex;
	min-width: 0;
	align-items: center;
	gap: 5px;
	font-size: 12px;
	font-weight: 600;
	overflow-wrap: anywhere;
}
.ai-agent-response {
	min-width: 0;
}

.ai-agent-response :deep(> :first-child) {
	margin-top: 0;
}

.ai-agent-response :deep(> :last-child) {
	margin-bottom: 0;
}

.ai-agent-response :deep(p),
.ai-agent-response :deep(ul),
.ai-agent-response :deep(ol),
.ai-agent-response :deep(pre),
.ai-agent-response :deep(blockquote),
.ai-agent-response :deep(table) {
	margin: 0 0 8px;
}

.ai-agent-response :deep(h3),
.ai-agent-response :deep(h4) {
	margin: 10px 0 5px;
	color: #111827;
	font-size: 14px;
	font-weight: 650;
	line-height: 1.45;
}

.ai-agent-response :deep(ul),
.ai-agent-response :deep(ol) {
	padding-left: 20px;
}

.ai-agent-response :deep(li + li) {
	margin-top: 3px;
}

.ai-agent-response :deep(code) {
	padding: 1px 4px;
	color: #b42318;
	font-family: Consolas, 'Courier New', monospace;
	font-size: 12px;
	background: #f3f4f6;
	border-radius: 4px;
}

.ai-agent-response :deep(pre) {
	max-width: 100%;
	padding: 9px 10px;
	overflow-x: auto;
	background: #111827;
	border-radius: 6px;
}

.ai-agent-response :deep(pre code) {
	padding: 0;
	color: #f9fafb;
	white-space: pre;
	background: transparent;
}

.ai-agent-response :deep(blockquote) {
	padding-left: 9px;
	color: #4b5563;
	border-left: 3px solid #93c5fd;
}

.ai-agent-response :deep(a) {
	color: #2563eb;
	text-decoration: underline;
	text-underline-offset: 2px;
}

.ai-agent-response :deep(table) {
	display: block;
	max-width: 100%;
	overflow-x: auto;
	border-collapse: collapse;
}

.ai-agent-response :deep(th),
.ai-agent-response :deep(td) {
	padding: 5px 7px;
	text-align: left;
	white-space: nowrap;
	border: 1px solid #d1d5db;
}
.is-user .ai-agent-message {
	color: #ffffff;
	background: #2563eb;
	border-color: #2563eb;
}

.ai-agent-message.is-error {
	color: #b42318;
	background: #fff7ed;
	border-color: #fed7aa;
}

.ai-agent-tools {
	display: flex;
	flex-direction: column;
	gap: 5px;
	margin-bottom: 8px;
}

.ai-agent-tool {
	display: flex;
	align-items: flex-start;
	gap: 5px;
	color: #047857;
	font-size: 12px;
	line-height: 1.4;
}

.ai-agent-tool.is-failed {
	color: #b42318;
}

.ai-agent-tool svg {
	width: 14px;
	height: 14px;
	flex: 0 0 auto;
	margin-top: 1px;
}

.ai-agent-loading {
	display: flex;
	min-width: 142px;
	height: 38px;
	align-items: center;
	justify-content: center;
	gap: 4px;
}

.ai-agent-loading small {
	margin-left: 4px;
	color: #6b7280;
	font-size: 12px;
}

.ai-agent-loading span {
	width: 5px;
	height: 5px;
	background: #9ca3af;
	border-radius: 50%;
	animation: ai-agent-pulse 1.1s infinite ease-in-out;
}

.ai-agent-loading span:nth-child(2) {
	animation-delay: 0.15s;
}

.ai-agent-loading span:nth-child(3) {
	animation-delay: 0.3s;
}

.ai-agent-panel.is-file-over {
	border-color: #22c55e;
}

.ai-agent-drop-dock {
	position: absolute;
	right: 12px;
	bottom: 82px;
	left: 12px;
	z-index: 6;
	display: grid;
	grid-template-columns: 34px 30px minmax(0, 1fr);
	align-items: center;
	gap: 8px;
	min-height: 54px;
	padding: 6px 12px 6px 8px;
	background: rgb(255 255 255 / 96%);
	border: 1px solid #86efac;
	border-radius: 8px;
	box-shadow: 0 8px 24px rgb(15 23 42 / 12%);
	pointer-events: none;
}

.ai-agent-drop-dock.is-catching {
	border-color: #bbf7d0;
}

.ai-agent-drop-dock-mascot {
	display: flex;
	width: 34px;
	height: 38px;
	align-items: center;
	justify-content: center;
}

.ai-agent-drop-dock-icon {
	display: grid;
	width: 28px;
	height: 28px;
	color: #15803d;
	background: #f0fdf4;
	border-radius: 6px;
	place-items: center;
}

.ai-agent-drop-dock-icon svg {
	width: 16px;
	height: 16px;
}

.ai-agent-drop-dock-copy {
	display: flex;
	min-width: 0;
	flex-direction: column;
	gap: 1px;
}

.ai-agent-drop-dock-copy strong {
	color: #166534;
	font-size: 13px;
	line-height: 1.35;
}

.ai-agent-drop-dock-copy small {
	overflow: hidden;
	color: #64748b;
	font-size: 12px;
	line-height: 1.35;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.ai-drop-dock-enter-active,
.ai-drop-dock-leave-active {
	transition: opacity 140ms ease, transform 180ms ease;
}

.ai-drop-dock-enter-from,
.ai-drop-dock-leave-to {
	opacity: 0;
	transform: translateY(8px);
}

.ai-agent-message-row.is-upload-action {
	align-items: flex-start;
}

.ai-agent-upload-card {
	width: calc(100% - 36px);
	min-width: 0;
	padding: 12px;
	background: #ffffff;
	border: 1px solid #d1d5db;
	border-radius: 8px;
}

.ai-agent-upload-card-title {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 12px;
}

.ai-agent-upload-card-title > div {
	display: flex;
	min-width: 0;
	flex-direction: column;
	gap: 2px;
}

.ai-agent-upload-card-title strong {
	color: #111827;
	font-size: 14px;
	line-height: 1.4;
}

.ai-agent-upload-card-title small,
.ai-agent-upload-file-summary small {
	color: #6b7280;
	font-size: 11px;
}

.ai-agent-upload-card-title > svg {
	width: 20px;
	height: 20px;
	flex: 0 0 auto;
	color: #16a34a;
}

.ai-agent-upload-file-summary {
	display: flex;
	flex-direction: column;
	gap: 4px;
	margin: 10px 0;
	padding: 8px;
	background: #f8fafc;
	border: 1px solid #e5e7eb;
	border-radius: 6px;
}

.ai-agent-upload-file-summary span {
	display: flex;
	min-width: 0;
	align-items: center;
	gap: 6px;
	color: #374151;
	font-size: 12px;
	line-height: 1.4;
	overflow-wrap: anywhere;
}

.ai-agent-upload-file-summary svg {
	width: 14px;
	height: 14px;
	flex: 0 0 auto;
}

.ai-agent-upload-card p {
	margin: 8px 0;
	color: #4b5563;
	font-size: 12px;
	line-height: 1.5;
}

.ai-agent-upload-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 7px;
	margin-top: 10px;
}

.ai-agent-action-button {
	display: inline-flex;
	min-height: 34px;
	align-items: center;
	justify-content: center;
	gap: 6px;
	padding: 0 10px;
	color: #374151;
	font-size: 12px;
	font-weight: 600;
	background: #ffffff;
	border: 1px solid #d1d5db;
	border-radius: 6px;
	cursor: pointer;
}

.ai-agent-action-button:hover:not(:disabled) {
	color: #1d4ed8;
	border-color: #60a5fa;
}

.ai-agent-action-button.is-primary {
	color: #ffffff;
	background: #2563eb;
	border-color: #2563eb;
}

.ai-agent-action-button.is-primary:hover:not(:disabled) {
	color: #ffffff;
	background: #1d4ed8;
	border-color: #1d4ed8;
}

.ai-agent-action-button.is-quiet {
	color: #6b7280;
	background: transparent;
	border-color: transparent;
}

.ai-agent-action-button:disabled {
	cursor: not-allowed;
	opacity: 0.45;
}

.ai-agent-action-button svg {
	width: 15px;
	height: 15px;
}

.ai-agent-upload-field {
	display: grid;
	grid-template-columns: 64px minmax(0, 1fr);
	gap: 8px;
	align-items: center;
	margin-top: 8px;
	color: #4b5563;
	font-size: 12px;
}

.ai-agent-upload-field :deep(.el-select),
.ai-agent-upload-field :deep(.el-input) {
	width: 100%;
}

.ai-agent-upload-notice {
	color: #6b7280 !important;
}

.ai-agent-upload-error {
	padding: 6px 8px;
	color: #b42318 !important;
	background: #fff7ed;
	border-radius: 5px;
}

.ai-agent-upload-loading {
	display: flex;
	align-items: center;
	gap: 7px;
	padding: 10px 0 2px;
	color: #4b5563;
	font-size: 12px;
}

.ai-agent-upload-loading svg {
	width: 16px;
	height: 16px;
	animation: ai-agent-spin 900ms linear infinite;
}

.ai-agent-upload-progress {
	display: flex;
	flex-direction: column;
	gap: 8px;
	margin-top: 10px;
}

.ai-agent-upload-progress > div {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	color: #4b5563;
	font-size: 12px;
}

.ai-agent-upload-progress span {
	min-width: 0;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.ai-agent-upload-progress strong {
	flex: 0 0 auto;
	color: #166534;
}

.ai-agent-composer {
	display: flex;
	flex-direction: column;
	gap: 8px;
	padding: 12px;
	background: #ffffff;
	border-top: 1px solid #e5e7eb;
}

.ai-agent-file-input {
	display: none;
}

.ai-agent-composer-row {
	display: grid;
	grid-template-columns: 40px minmax(0, 1fr) 40px;
	gap: 8px;
	align-items: end;
}

.ai-agent-selected-files {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
}

.ai-agent-selected-files > span {
	display: grid;
	max-width: 100%;
	grid-template-columns: 15px minmax(0, 1fr) auto 18px;
	gap: 5px;
	align-items: center;
	padding: 5px 6px;
	color: #374151;
	font-size: 12px;
	background: #f3f4f6;
	border: 1px solid #d1d5db;
	border-radius: 6px;
}

.ai-agent-selected-files svg,
.ai-agent-message-files svg {
	width: 14px;
	height: 14px;
	flex: 0 0 auto;
}

.ai-agent-selected-file-name {
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.ai-agent-selected-files small {
	color: #6b7280;
	white-space: nowrap;
}

.ai-agent-selected-files button {
	display: inline-flex;
	width: 18px;
	height: 18px;
	align-items: center;
	justify-content: center;
	padding: 0;
	color: #6b7280;
	background: transparent;
	border: 0;
	cursor: pointer;
}

.ai-agent-selected-files button:hover {
	color: #b42318;
}

.ai-agent-attach-button {
	width: 40px;
	height: 40px;
	color: #4b5563;
	background: #ffffff;
	border: 1px solid #d1d5db;
	border-radius: 6px;
}

.ai-agent-attach-button:hover:not(:disabled) {
	color: #2563eb;
	border-color: #2563eb;
}

.ai-agent-composer :deep(.el-textarea__inner) {
	min-height: 64px !important;
	padding: 9px 11px;
	line-height: 1.5;
	border-radius: 6px;
	box-shadow: 0 0 0 1px #d1d5db inset;
}

.ai-agent-composer :deep(.el-textarea__inner:focus) {
	box-shadow: 0 0 0 1px #2563eb inset;
}

.ai-agent-send-button {
	width: 40px;
	height: 40px;
	color: #ffffff;
	background: #2563eb;
	border-radius: 6px;
}

.ai-agent-send-button:hover:not(:disabled) {
	background: #1d4ed8;
}

.ai-agent-floating-button {
	position: fixed;
	right: 84px;
	bottom: max(16px, env(safe-area-inset-bottom));
	z-index: 1999;
	width: 96px;
	height: 104px;
	padding: 0;
	background: transparent;
	cursor: grab;
	touch-action: none;
	user-select: none;
	will-change: left, top;
}

.ai-agent-floating-button.is-dragging {
	cursor: grabbing;
}

.ai-agent-floating-button.is-file-over {
	cursor: copy;
}

.ai-agent-mascot-stage {
	position: relative;
	display: block;
	width: 96px;
	height: 104px;
	transform-origin: 50% 88%;
	transition: transform 180ms ease;
}

.ai-agent-floating-button.is-file-over .ai-agent-mascot-stage {
	transform: translateY(2px) scale(0.98);
}

.ai-agent-floating-button.is-catching .ai-agent-mascot-stage {
	animation: ai-agent-soft-catch 680ms cubic-bezier(0.22, 0.8, 0.25, 1);
}

.ai-agent-catch-file {
	position: absolute;
	top: -12px;
	left: 50%;
	z-index: 0;
	display: grid;
	width: 30px;
	height: 36px;
	color: #2563eb;
	background: #ffffff;
	border: 1px solid #bfdbfe;
	border-radius: 4px;
	box-shadow: 0 5px 12px rgb(15 23 42 / 14%);
	transform: translateX(-50%);
	place-items: center;
	animation: ai-agent-file-ready 900ms ease-in-out infinite alternate;
}

.ai-agent-catch-file::after {
	position: absolute;
	top: -1px;
	right: -1px;
	width: 8px;
	height: 8px;
	background: linear-gradient(225deg, #dbeafe 50%, #ffffff 50%);
	border-top-right-radius: 3px;
	content: '';
}

.ai-agent-catch-file svg {
	width: 15px;
	height: 15px;
}

.ai-agent-catch-file.is-catching {
	animation: ai-agent-file-catch 560ms cubic-bezier(0.4, 0, 0.2, 1) forwards;
}

.ai-agent-drop-hint {
	position: absolute;
	top: -38px;
	left: 50%;
	display: inline-flex;
	width: max-content;
	align-items: center;
	gap: 4px;
	padding: 5px 7px;
	color: #166534;
	font-size: 12px;
	font-weight: 600;
	line-height: 1.3;
	background: rgb(255 255 255 / 96%);
	border: 1px solid #bbf7d0;
	border-radius: 6px;
	box-shadow: 0 4px 12px rgb(15 23 42 / 10%);
	transform: translateX(-50%);
	pointer-events: none;
}

.ai-agent-drop-hint svg {
	width: 13px;
	height: 13px;
}

.ai-panel-enter-active,
.ai-panel-leave-active {
	transition: opacity 160ms ease, transform 160ms ease;
}

.ai-panel-enter-from,
.ai-panel-leave-to {
	opacity: 0;
	transform: translateY(10px);
}

@keyframes ai-agent-file-ready {
	from {
		transform: translate(-50%, 0);
	}
	to {
		transform: translate(-50%, 4px);
	}
}

@keyframes ai-agent-file-catch {
	0% {
		opacity: 1;
		transform: translate(-50%, 0) scale(1);
	}
	45% {
		opacity: 1;
		transform: translate(-50%, 26px) scale(0.86);
	}
	78% {
		opacity: 0.85;
		transform: translate(-50%, 62px) scale(0.45);
	}
	100% {
		opacity: 0;
		transform: translate(-50%, 70px) scale(0.2);
	}
}

@keyframes ai-agent-soft-catch {
	0%,
	100% {
		transform: translateY(0) scale(1);
	}
	30% {
		transform: translateY(5px) scale(1.04, 0.94);
	}
	58% {
		transform: translateY(-4px) scale(0.98, 1.03);
	}
	80% {
		transform: translateY(1px) scale(1.01, 0.99);
	}
}

@keyframes ai-agent-spin {
	to {
		transform: rotate(360deg);
	}
}

@keyframes ai-agent-pulse {
	0%,
	80%,
	100% {
		opacity: 0.35;
		transform: translateY(0);
	}

	40% {
		opacity: 1;
		transform: translateY(-3px);
	}
}

@media (max-width: 640px) {
	.ai-agent-panel {
		right: 8px;
		bottom: calc(76px + env(safe-area-inset-bottom));
		left: 8px;
		width: auto !important;
		height: min(68dvh, 560px) !important;
	}

	.ai-agent-resize-handle {
		display: none;
	}

	.ai-agent-floating-button {
		right: 88px;
		bottom: max(16px, env(safe-area-inset-bottom));
	}
}

@media (prefers-reduced-motion: reduce) {
	.ai-panel-enter-active,
	.ai-panel-leave-active,
	.ai-agent-loading span,
	.ai-agent-catch-file,
	.ai-agent-floating-button.is-catching .ai-agent-mascot-stage,
	.ai-agent-upload-loading svg {
		transition: none;
		animation: none;
	}
}
</style>
