<template>
	<section class="private-model" aria-label="三维模型预览" :aria-busy="loading">
		<div class="model-tools" aria-label="模型操作">
			<button type="button" :disabled="!ready" @click="rotate(-0.2, 0)">
				向左旋转</button
			><button type="button" :disabled="!ready" @click="rotate(0.2, 0)">
				向右旋转</button
			><button type="button" :disabled="!ready" @click="rotate(0, -0.2)">
				向上旋转</button
			><button type="button" :disabled="!ready" @click="rotate(0, 0.2)">
				向下旋转</button
			><button
				type="button"
				:disabled="!ready || view.zoom <= 0.35"
				@click="zoom(-0.15)"
			>
				缩小</button
			><span>{{ Math.round(view.zoom * 100) }}%</span
			><button
				type="button"
				:disabled="!ready || view.zoom >= 3"
				@click="zoom(0.15)"
			>
				放大</button
			><button type="button" :disabled="!ready" @click="reset">还原</button>
		</div>
		<div class="model-stage">
			<canvas
				ref="canvas"
				tabindex="0"
				aria-label="模型画布，方向键旋转，加减键缩放，Home 还原"
				@keydown="keyboard"
				@webglcontextlost="contextLost"
			></canvas>
			<p v-if="loading" class="model-status" role="status">正在解析模型几何…</p>
			<p v-else-if="error" class="model-status model-error" role="alert">
				{{ error }}
			</p>
		</div>
		<p class="model-note">
			<span v-if="geometry"
				>{{ geometry.vertexCount }} 个顶点 ·
				{{ geometry.triangleCount }} 个三角面。 </span
			>仅预览几何形状，不加载材质或外部资源。聚焦画布后可使用方向键旋转、+ / −
			缩放。
		</p>
	</section>
</template>
<script setup>
import {
	ref,
	shallowRef,
	reactive,
	computed,
	watch,
	onMounted,
	onBeforeUnmount,
	nextTick,
} from 'vue'
import { parsePrivateModelInWorker } from '~/utils/private-model-worker.mjs'
import { createPrivateModelRenderer } from '~/utils/private-model-renderer.mjs'

const props = defineProps({
	buffer: { type: ArrayBuffer, required: true },
	format: { type: String, required: true },
})
const canvas = ref(null),
	geometry = shallowRef(null),
	loading = ref(true),
	error = ref(''),
	view = reactive({ yaw: 0.5, pitch: -0.3, zoom: 1 })
const ready = computed(
	() => !loading.value && !error.value && Boolean(geometry.value)
)
let mounted = false,
	sequence = 0,
	controller,
	renderer,
	observer
const draw = () => renderer?.draw({ ...view })
const resize = () => {
	if (!renderer || !canvas.value) return
	const box = canvas.value.getBoundingClientRect()
	renderer.resize(
		box.width || 640,
		box.height || 400,
		window.devicePixelRatio || 1
	)
	draw()
}
const disposeGraphics = () => {
	observer?.disconnect()
	observer = undefined
	window.removeEventListener('resize', resize)
	renderer?.dispose()
	renderer = undefined
}
const rotate = (yaw, pitch) => {
	if (!ready.value) return
	view.yaw += yaw
	view.pitch += pitch
	draw()
}
const zoom = (delta) => {
	if (!ready.value) return
	view.zoom = Math.max(0.35, Math.min(3, view.zoom + delta))
	draw()
}
const reset = () => {
	view.yaw = 0.5
	view.pitch = -0.3
	view.zoom = 1
	draw()
}
const keyboard = (event) => {
	if (!ready.value || event.altKey || event.ctrlKey || event.metaKey) return
	const actions = {
		ArrowLeft: () => rotate(-0.2, 0),
		ArrowRight: () => rotate(0.2, 0),
		ArrowUp: () => rotate(0, -0.2),
		ArrowDown: () => rotate(0, 0.2),
		'+': () => zoom(0.15),
		'=': () => zoom(0.15),
		'-': () => zoom(-0.15),
		Home: reset,
	}
	if (actions[event.key]) {
		event.preventDefault()
		actions[event.key]()
	}
}
const contextLost = (event) => {
	event.preventDefault()
	if (!mounted) return
	error.value = '模型画布已失效，请关闭预览后重试'
	disposeGraphics()
}
function materialColor() {
	const value = window
		.getComputedStyle(canvas.value)
		.getPropertyValue('--cloud-accent')
		.trim()
	return /^#[\da-f]{6}$/i.test(value)
		? [1, 3, 5].map(
				(offset) => parseInt(value.slice(offset, offset + 2), 16) / 255
		  )
		: [0.35, 0.35, 0.4]
}
async function load() {
	const id = ++sequence
	controller?.abort()
	disposeGraphics()
	geometry.value = null
	loading.value = true
	error.value = ''
	reset()
	const current = (controller = new AbortController())
	try {
		// The parent keeps its source for retry/lifecycle checks; only transfer our copy.
		const model = await parsePrivateModelInWorker(
			props.buffer.slice(0),
			props.format,
			current.signal
		)
		if (!mounted || id !== sequence || current.signal.aborted) return
		geometry.value = model
		loading.value = false
		await nextTick()
		if (!mounted || id !== sequence || current.signal.aborted) return
		renderer = createPrivateModelRenderer(canvas.value, model, materialColor())
		resize()
		if (typeof globalThis.ResizeObserver === 'function') {
			observer = new globalThis.ResizeObserver(resize)
			observer.observe(canvas.value)
		} else window.addEventListener('resize', resize)
	} catch (failure) {
		if (mounted && id === sequence && !current.signal.aborted) {
			disposeGraphics()
			geometry.value = null
			loading.value = false
			error.value = failure?.privatePreviewMessage
				? failure.message
				: '当前浏览器无法安全预览此模型，请下载后查看'
		}
	}
}
watch(
	() => [props.buffer, props.format],
	() => {
		if (mounted) void load()
	},
	{ flush: 'sync' }
)
onMounted(() => {
	mounted = true
	void load()
})
onBeforeUnmount(() => {
	mounted = false
	sequence++
	controller?.abort()
	disposeGraphics()
	geometry.value = null
})
</script>
<style scoped>
.private-model {
	color: var(--cloud-ink);
}
.model-tools {
	display: flex;
	align-items: center;
	gap: 8px;
	flex-wrap: wrap;
	padding: 10px 14px;
	background: var(--cloud-soft);
	border-bottom: 1px solid var(--cloud-line);
}
.model-tools button {
	padding: 8px 12px;
	min-height: 36px;
	border: 1px solid var(--cloud-line);
	border-radius: 8px;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
	cursor: pointer;
}
.model-tools button:not(:disabled):hover {
	background: var(--cloud-canvas);
}
.model-tools button:disabled {
	opacity: 0.5;
	cursor: default;
}
.model-tools button:focus-visible,
canvas:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.model-tools span {
	font-size: 12px;
	color: var(--cloud-muted);
}
.model-stage {
	position: relative;
	background: var(--cloud-soft);
	min-height: 320px;
}
canvas {
	display: block;
	width: 100%;
	height: 420px;
	max-height: 65vh;
	min-height: 320px;
}
.model-status {
	position: absolute;
	inset: 0;
	display: grid;
	place-items: center;
	margin: 0;
	padding: 24px;
	background: var(--cloud-soft);
	text-align: center;
	color: var(--cloud-muted);
}
.model-error {
	color: var(--el-color-danger);
}
.model-note {
	font-size: 12px;
	color: var(--cloud-muted);
	padding: 12px 16px;
	margin: 0;
	line-height: 1.7;
}
@media (max-width: 600px) {
	.model-tools button {
		min-height: 44px;
	}
	.model-tools {
		gap: 6px;
		padding: 10px;
	}
	canvas {
		height: 340px;
	}
}
</style>
