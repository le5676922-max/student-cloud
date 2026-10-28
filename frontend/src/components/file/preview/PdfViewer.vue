<template>
	<section
		class="student-cloud-pdf-viewer"
		aria-label="PDF 预览"
		@keydown="handleKeydown"
	>
		<div class="app-header">
			<div class="page-controls">
				<button
					type="button"
					:disabled="controlsDisabled || page <= 1"
					aria-label="上一页"
					@click="goToPage(page - 1)"
				>
					上一页
				</button>
				<form
					novalidate
					class="page-jump"
					@submit.prevent="goToPage(pageInput)"
				>
					<label>
						第
						<input
							v-model="pageInput"
							type="number"
							min="1"
							:max="pageCount || 1"
							:disabled="controlsDisabled"
							aria-label="页码"
							@change="goToPage(pageInput)"
						/>
						/ {{ pageCount || '—' }} 页
					</label>
				</form>
				<button
					type="button"
					:disabled="controlsDisabled || page >= pageCount"
					aria-label="下一页"
					@click="goToPage(page + 1)"
				>
					下一页
				</button>
			</div>
			<div class="zoom-controls">
				<button
					type="button"
					:disabled="controlsDisabled || zoom <= PDF_ZOOM_MIN"
					aria-label="缩小"
					@click="setZoom(zoom - 10)"
				>
					−
				</button>
				<span>{{ zoom }}%</span>
				<button
					type="button"
					:disabled="controlsDisabled || zoom >= PDF_ZOOM_MAX"
					aria-label="放大"
					@click="setZoom(zoom + 10)"
				>
					+
				</button>
				<button
					type="button"
					:disabled="controlsDisabled || zoom === 100"
					@click="setZoom(100)"
				>
					适合宽度
				</button>
			</div>
		</div>

		<p class="preview-hint">
			按页预览，避免大文件一次渲染全部页面。聚焦页面后可用 ← / → 翻页，+ / −
			缩放。
		</p>
		<p
			v-if="isLoading || isRendering"
			class="preview-status"
			role="status"
			aria-live="polite"
		>
			<template v-if="isLoading">{{
				loadingProgress === null
					? '正在加载 PDF…'
					: `正在加载 PDF ${loadingProgress}%…`
			}}</template>
			<template v-else>正在渲染第 {{ page }} 页…</template>
		</p>
		<div v-if="errorMessage" class="preview-error" role="alert">
			<p>{{ errorMessage }}</p>
			<button v-if="fileUrl" type="button" @click="resetDocument">
				重新加载
			</button>
		</div>
		<form
			v-if="passwordCallback"
			novalidate
			class="password-form"
			@submit.prevent="submitPassword"
		>
			<label>
				{{ wrongPassword ? '密码不正确，请重试：' : '此 PDF 需要密码：' }}
				<input
					v-model="pdfPassword"
					type="password"
					autocomplete="off"
					aria-label="PDF 密码"
				/>
			</label>
			<button type="submit" :disabled="!pdfPassword">打开</button>
			<button
				type="button"
				@click="failDocument('已取消密码输入，可重新加载后再试。')"
			>
				取消
			</button>
		</form>

		<div
			ref="contentRef"
			class="app-content"
			tabindex="0"
			role="region"
			:aria-label="`${fileName || 'PDF'}，第 ${page} 页`"
			:aria-busy="isLoading || isRendering"
		>
			<vue-pdf-embed
				v-if="session"
				:key="session.id"
				class="pdf-page"
				:annotation-layer="!privateSource"
				text-layer
				:source="session.source"
				:page="page"
				:width="pageWidth"
				:style="{ width: `${pageWidth}px` }"
				@loaded="session.onLoaded"
				@progress="session.onProgress"
				@rendered="session.onRendered"
				@loading-failed="session.onLoadingFailed"
				@rendering-failed="session.onRenderingFailed"
				@password-requested="session.onPasswordRequested"
				@internal-link-clicked="goToPage"
			/>
		</div>
	</section>
</template>

<script setup>
import VuePdfEmbed, {
	GlobalWorkerOptions,
} from 'vue-pdf-embed/dist/index.essential.mjs'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { useElementSize } from '@vueuse/core'
import {
	PDF_ZOOM_MIN,
	PDF_ZOOM_MAX,
	clampPdfPage,
	clampPdfZoom,
	pdfLoadingProgress,
	pdfKeyboardAction,
	createPdfSessionGuard,
} from '~/utils/pdf-preview.mjs'
import 'vue-pdf-embed/dist/styles/annotationLayer.css'
import 'vue-pdf-embed/dist/styles/textLayer.css'
GlobalWorkerOptions.workerSrc = pdfWorkerUrl

const props = defineProps({
	fileUrl: { type: String, default: '' },
	fileName: { type: String, default: '' },
	privateSource: { type: Boolean, default: false },
})
const contentRef = ref()
const { width: contentWidth } = useElementSize(contentRef)
const zoom = ref(100)
// Use the component width so the canvas, selectable text and links stay aligned.
// The maximum also bounds the canvas size on very wide screens.
const pageWidth = computed(() =>
	Math.max(
		1,
		Math.min(2400, Math.round(((contentWidth.value || 600) * zoom.value) / 100))
	)
)
const page = ref(1)
const pageInput = ref(1)
const pageCount = ref(0)
const isLoading = ref(true)
const isRendering = ref(true)
const loadingProgress = ref(null)
const errorMessage = ref('')
const passwordCallback = shallowRef(null)
const pdfPassword = ref('')
const wrongPassword = ref(false)
const session = shallowRef(null)
const sessionGuard = createPdfSessionGuard()
const controlsDisabled = computed(
	() =>
		isLoading.value ||
		isRendering.value ||
		!pageCount.value ||
		!!errorMessage.value
)

function failDocument(message) {
	errorMessage.value = message
	isLoading.value = false
	isRendering.value = false
	passwordCallback.value = null
	pdfPassword.value = ''
	sessionGuard.invalidate()
	// Unmounting releases the PDF loading task, document and canvas via vue-pdf-embed.
	session.value = null
}

function resetDocument() {
	const { id, guard } = sessionGuard.begin()
	page.value = 1
	pageInput.value = 1
	pageCount.value = 0
	zoom.value = 100
	errorMessage.value = ''
	loadingProgress.value = null
	isLoading.value = true
	isRendering.value = true
	passwordCallback.value = null
	pdfPassword.value = ''
	wrongPassword.value = false
	if (!props.fileUrl) {
		failDocument('没有可预览的 PDF 地址。')
		return
	}
	// A fresh key unmounts the previous loader; guarded callbacks cannot update a new document.
	session.value = {
		id,
		source: props.privateSource
			? {
					url: props.fileUrl,
					enableXfa: false,
					isEvalSupported: false,
					maxImageSize: 20000000,
			  }
			: props.fileUrl,
		onLoaded: guard((doc) => {
			if (!Number.isSafeInteger(doc?.numPages) || doc.numPages < 1) {
				failDocument('PDF 页数无效，请检查文件后重新加载。')
				return
			}
			pageCount.value = doc.numPages
			isLoading.value = false
		}),
		onProgress: guard((progress) => {
			loadingProgress.value = pdfLoadingProgress(progress)
		}),
		onRendered: guard(() => {
			isRendering.value = false
		}),
		onLoadingFailed: guard(() =>
			failDocument('PDF 加载失败，请检查网络、文件链接或访问权限后重试。')
		),
		onRenderingFailed: guard(() =>
			failDocument('PDF 页面渲染失败，请重新加载；文件损坏时可下载后查看。')
		),
		onPasswordRequested: guard(({ callback, isWrongPassword }) => {
			passwordCallback.value = callback
			wrongPassword.value = !!isWrongPassword
			isLoading.value = false
			isRendering.value = false
		}),
	}
}

function submitPassword() {
	const callback = passwordCallback.value
	if (!callback || !pdfPassword.value) return
	const password = pdfPassword.value
	pdfPassword.value = ''
	passwordCallback.value = null
	isLoading.value = true
	isRendering.value = true
	callback(password)
}

function goToPage(value) {
	if (!pageCount.value) return
	page.value = clampPdfPage(value, pageCount.value, page.value)
	pageInput.value = page.value
}

function setZoom(value) {
	zoom.value = clampPdfZoom(value)
}

function handleKeydown(event) {
	if (controlsDisabled.value) return
	const action = pdfKeyboardAction(event)
	if (!action) return
	event.preventDefault()
	if (action === 'previous') goToPage(page.value - 1)
	if (action === 'next') goToPage(page.value + 1)
	if (action === 'zoom-in') setZoom(zoom.value + 10)
	if (action === 'zoom-out') setZoom(zoom.value - 10)
}

watch(
	[page, pageWidth],
	() => {
		if (session.value && pageCount.value) isRendering.value = true
	},
	{ flush: 'sync' }
)
watch(() => props.fileUrl, resetDocument, { immediate: true })
onBeforeUnmount(() => {
	sessionGuard.invalidate()
	pdfPassword.value = ''
	passwordCallback.value = null
})
</script>

<style scoped lang="scss">
.app-header {
	position: sticky;
	top: 0;
	z-index: 5;
	display: flex;
	flex-wrap: wrap;
	justify-content: space-between;
	gap: 12px;
	padding: 12px;
	background: #4b5563;
	color: white;
}
.page-controls,
.zoom-controls,
.page-jump,
.password-form {
	display: flex;
	align-items: center;
	gap: 8px;
}
button {
	padding: 4px 8px;
	border: 1px solid currentColor;
	border-radius: 4px;
	&:disabled {
		opacity: 0.45;
		cursor: not-allowed;
	}
}
.page-jump input {
	width: 64px;
	padding: 2px 4px;
	color: #111827;
	border-radius: 4px;
	background: white;
}
.preview-hint,
.preview-status,
.preview-error,
.password-form {
	padding: 10px 4px;
}
.preview-hint {
	font-size: 12px;
	color: #6b7280;
}
.preview-error {
	color: #b91c1c;
}
.password-form {
	flex-wrap: wrap;
}
.password-form input {
	border: 1px solid #9ca3af;
	border-radius: 4px;
	padding: 4px;
}
.app-content {
	width: 100%;
	overflow: auto;
	padding: 8px 0 24px;
}
.pdf-page {
	margin: 0 auto;
}
.vue-pdf-embed :deep(> div) {
	box-shadow: 0 2px 8px 2px rgb(0 0 0 / 10%);
}
</style>
