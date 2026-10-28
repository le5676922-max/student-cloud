<template>
	<ZDialog
		:model-value="state.visible"
		:title="state.name || '文件预览'"
		width="min(1000px, 94vw)"
		top="5vh"
		:show-footer="false"
		destroy-on-close
		@close="close"
		@update:model-value="
			(value) => {
				if (!value) close()
			}
		"
	>
		<section
			class="private-preview"
			aria-label="私有文件预览"
			:aria-busy="state.loading"
		>
			<p v-if="state.loading" role="status">正在验证权限并读取文件…</p>
			<p v-else-if="state.error" class="preview-error" role="alert">
				{{ state.error }}
			</p>
			<template v-else-if="state.kind === 'image'">
				<div class="preview-tools" aria-label="图片工具">
					<button
						type="button"
						:disabled="zoom <= 25"
						@click="zoom = Math.max(25, zoom - 25)"
					>
						缩小</button
					><span>{{ zoom }}%</span
					><button
						type="button"
						:disabled="zoom >= 300"
						@click="zoom = Math.min(300, zoom + 25)"
					>
						放大</button
					><button type="button" @click="rotation = (rotation + 90) % 360">
						旋转</button
					><button type="button" @click="resetImage">还原</button>
				</div>
				<div class="image-stage">
					<img
						:src="state.url"
						:alt="state.name"
						referrerpolicy="no-referrer"
						:style="{
							transform: `rotate(${rotation}deg) scale(${zoom / 100})`,
						}"
						@error="mediaError = '图片无法显示，请下载后查看'"
					/>
				</div>
			</template>
			<PdfViewer
				v-else-if="state.kind === 'pdf'"
				:file-url="state.url"
				:file-name="state.name"
				private-source
			/>
			<PrivateModelPreview
				v-else-if="state.kind === 'model' && state.buffer"
				:buffer="state.buffer"
				:format="state.name.split('.').at(-1).toLowerCase()"
			/>
			<template v-else-if="state.kind === 'video' || state.kind === 'audio'">
				<div class="media-stage">
					<video
						v-if="state.kind === 'video'"
						ref="media"
						:src="state.url"
						controls
						playsinline
						preload="metadata"
						@error="mediaError = '浏览器不支持此视频编码，请下载后查看'"
					/><audio
						v-else
						ref="media"
						:src="state.url"
						controls
						preload="metadata"
						@error="mediaError = '浏览器不支持此音频编码，请下载后查看'"
					/>
				</div>
				<p class="preview-note">
					文件仅在当前浏览器播放。实际播放能力取决于浏览器支持的编码。
				</p>
			</template>
			<PrivateMarkdownPreview
				v-else-if="state.kind === 'markdown'"
				:text="state.text"
			/>
			<PrivateOfficePreview
				v-else-if="state.kind === 'office' && state.document"
				:model="state.document"
			/>
			<PrivateTextPreview
				v-else-if="state.kind === 'text'"
				:text="state.text"
				:name="state.name"
			/>
			<p v-if="mediaError" class="preview-error" role="alert">
				{{ mediaError }}
			</p>
		</section>
	</ZDialog>
</template>

<script setup>
import { defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useStorage } from '@vueuse/core'
import ZDialog from '~/components/common/dialog/ZDialog.vue'
import usePrivatePreviewDialog from '~/composables/file/usePrivatePreviewDialog'
import useStorageConfigStore from '~/stores/storage-config'
const PdfViewer = defineAsyncComponent(() =>
	import('~/components/file/preview/PdfViewer.vue')
)
const PrivateMarkdownPreview = defineAsyncComponent(() =>
	import('~/components/file/preview/PrivateMarkdownPreview.vue')
)
const PrivateOfficePreview = defineAsyncComponent(() =>
	import('~/components/file/preview/PrivateOfficePreview.vue')
)
const PrivateTextPreview = defineAsyncComponent(() =>
	import('~/components/file/preview/PrivateTextPreview.vue')
)
const PrivateModelPreview = defineAsyncComponent(() =>
	import('~/components/file/preview/PrivateModelPreview.vue')
)
const { state, close } = usePrivatePreviewDialog()
const zoom = ref(100),
	rotation = ref(0),
	media = ref(null),
	mediaError = ref('')
const resetImage = () => {
	zoom.value = 100
	rotation.value = 0
}
watch(
	() => [state.value.visible, state.value.name, state.value.url],
	() => {
		media.value?.pause()
		mediaError.value = ''
		resetImage()
	},
	{ flush: 'sync' }
)
const store = useStorageConfigStore(),
	route = useRoute()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
watch(
	() => [
		route.fullPath,
		store.loginInfo.isLogin,
		store.loginInfo.username,
		store.loginInfo.role,
		store.loginInfo.tenantId,
		credential.value,
		store.folderConfig?.permission?.preview,
	],
	close,
	{ flush: 'sync' }
)
onBeforeUnmount(close)
</script>

<style scoped>
.private-preview {
	min-height: 160px;
	max-height: 80vh;
	overflow: auto;
	color: var(--cloud-ink);
}
.image-stage > img {
	display: block;
	max-width: 100%;
	max-height: 76vh;
	object-fit: contain;
	margin: 0 auto;
}
.image-stage {
	min-height: 300px;
	overflow: auto;
	display: grid;
	place-items: center;
	padding: 24px;
}
.preview-tools {
	display: flex;
	align-items: center;
	gap: 8px;
	flex-wrap: wrap;
	padding: 10px 14px;
	background: var(--cloud-soft);
	border-bottom: 1px solid var(--cloud-line);
}
.preview-tools button {
	border: 1px solid var(--cloud-line);
	border-radius: 8px;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
	padding: 8px 12px;
	cursor: pointer;
	min-height: 36px;
}
.preview-tools button:hover {
	background: var(--cloud-canvas);
}
.preview-tools button:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.preview-tools button:disabled {
	opacity: 0.5;
	cursor: default;
}
.preview-tools span {
	font-size: 12px;
	color: var(--cloud-muted);
}
.media-stage {
	min-height: 240px;
	display: grid;
	place-items: center;
	padding: 16px;
	background: var(--cloud-soft);
}
.media-stage video {
	width: 100%;
	max-height: 65vh;
}
.media-stage audio {
	width: min(600px, 100%);
}
.private-preview pre {
	margin: 0;
	padding: 20px;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
	font: 13px/1.8 ui-monospace, monospace;
	background: var(--cloud-soft);
}
.preview-note {
	padding: 10px 20px;
	font-size: 12px;
	color: var(--cloud-muted);
}
.preview-error {
	color: var(--el-color-danger);
	padding: 20px;
}
</style>
