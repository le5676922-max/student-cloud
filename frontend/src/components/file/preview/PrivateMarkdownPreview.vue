<template>
	<section class="private-markdown" aria-label="Markdown 文档">
		<div class="markdown-tools" aria-label="Markdown 显示模式">
			<button
				type="button"
				:aria-pressed="!sourceMode"
				@click="sourceMode = false"
			>
				排版预览
			</button>
			<button
				type="button"
				:aria-pressed="sourceMode"
				@click="sourceMode = true"
			>
				查看源码
			</button>
		</div>
		<p v-if="truncated && !sourceMode" class="markdown-notice" role="status">
			排版预览仅显示前 10 万个字符；查看源码可阅读完整内容。
		</p>
		<pre v-if="sourceMode" class="markdown-source" tabindex="0">{{ text }}</pre>
		<!-- eslint-disable-next-line vue/no-v-html -- Only the shared DOMPurify allowlist renderer supplies this HTML. -->
		<article v-else v-html="html"></article>
	</section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { renderAssistantContent } from '~/utils/assistant-answer.mjs'
const props = defineProps({ text: { type: String, default: '' } })
const FORMATTED_LIMIT = 100000
const sourceMode = ref(false)
const truncated = computed(() => props.text.length > FORMATTED_LIMIT)
// The shared renderer strips resource-bearing tags before DOM parsing, then
// retains only inert markup and explicit http(s) navigation links. Bound input
// BEFORE parsing, not output HTML after a potentially expensive full parse.
const html = computed(() =>
	renderAssistantContent(props.text.slice(0, FORMATTED_LIMIT))
)
watch(
	() => props.text,
	() => {
		sourceMode.value = false
	}
)
</script>
<style scoped>
.private-markdown {
	max-width: 850px;
	margin: auto;
	padding: 20px;
	line-height: 1.8;
	overflow-wrap: anywhere;
	color: var(--cloud-ink);
}
.markdown-tools {
	display: flex;
	gap: 8px;
	flex-wrap: wrap;
	margin-bottom: 12px;
}
.markdown-tools button {
	border: 1px solid var(--cloud-line);
	border-radius: 8px;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
	padding: 8px 12px;
	cursor: pointer;
	min-height: 36px;
}
.markdown-tools button[aria-pressed='true'] {
	background: var(--cloud-lilac);
	border-color: var(--cloud-accent);
}
.markdown-tools button:hover {
	background: var(--cloud-soft);
}
.markdown-tools button:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.markdown-notice {
	font-size: 12px;
	color: var(--cloud-muted);
	padding: 10px;
	background: var(--cloud-soft);
	border-radius: 8px;
}
.private-markdown .markdown-source {
	white-space: pre-wrap;
	font: 13px/1.8 ui-monospace, monospace;
	overflow-wrap: anywhere;
}
.private-markdown :deep(pre) {
	overflow: auto;
	padding: 14px;
	background: var(--cloud-soft);
	border-radius: 8px;
	white-space: pre;
}
.private-markdown :deep(code) {
	font-family: ui-monospace, monospace;
	font-size: 13px;
}
.private-markdown :deep(blockquote) {
	margin: 12px 0;
	padding-left: 16px;
	border-left: 3px solid var(--cloud-line);
	color: var(--cloud-muted);
}
.private-markdown :deep(table) {
	display: block;
	max-width: 100%;
	overflow: auto;
	border-collapse: collapse;
}
.private-markdown :deep(td),
.private-markdown :deep(th) {
	padding: 8px;
	border: 1px solid var(--cloud-line);
}
.private-markdown :deep(a) {
	color: var(--cloud-accent-ink);
	text-decoration: underline;
}
.private-markdown :deep(a:focus-visible) {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.private-markdown :deep(h1) {
	font-size: 26px;
}
.private-markdown :deep(h2) {
	font-size: 22px;
}
.private-markdown :deep(h3) {
	font-size: 18px;
}
</style>
