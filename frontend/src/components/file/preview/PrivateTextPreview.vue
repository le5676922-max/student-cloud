<template>
	<section class="private-text" aria-label="文本与源代码预览">
		<div class="text-tools" aria-label="文本显示模式">
			<template v-if="language">
				<button
					type="button"
					:aria-pressed="!sourceMode"
					@click="sourceMode = false"
				>
					语法高亮
				</button>
				<button
					type="button"
					:aria-pressed="sourceMode"
					@click="sourceMode = true"
				>
					完整源码
				</button>
			</template>
			<button type="button" :aria-pressed="wrap" @click="wrap = !wrap">
				自动换行
			</button>
			<span
				>{{ lines }} 行<span v-if="language"> · {{ language }}</span></span
			>
		</div>
		<p v-if="truncated && !sourceMode" class="text-notice" role="status">
			语法高亮仅显示前 10 万个字符；完整源码可阅读全部内容。
		</p>
		<p class="text-notice">
			只读查看，HTML、脚本和文件内的外部资源不会执行或加载。
		</p>
		<pre
			v-if="sourceMode || !language || formatted === null"
			tabindex="0"
			:style="{ whiteSpace: wrap ? 'pre-wrap' : 'pre' }"
		><code>{{ text }}</code></pre>
		<!-- eslint-disable vue/no-v-html -- Only DOMPurify-sanitized highlight spans are rendered in this block. -->
		<pre
			v-else
			tabindex="0"
			:style="{ whiteSpace: wrap ? 'pre-wrap' : 'pre' }"
		><code v-html="formatted"></code></pre>
		<!-- eslint-enable vue/no-v-html -->
	</section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import java from 'highlight.js/lib/languages/java'
import python from 'highlight.js/lib/languages/python'
import css from 'highlight.js/lib/languages/css'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import bash from 'highlight.js/lib/languages/bash'
import sql from 'highlight.js/lib/languages/sql'
import cpp from 'highlight.js/lib/languages/cpp'
import yaml from 'highlight.js/lib/languages/yaml'
import DOMPurify from 'dompurify'

const props = defineProps({
	text: { type: String, default: '' },
	name: { type: String, default: '' },
})
const grammars = {
	javascript,
	typescript,
	java,
	python,
	css,
	json,
	xml,
	bash,
	sql,
	cpp,
	yaml,
}
for (const [name, grammar] of Object.entries(grammars))
	hljs.registerLanguage(name, grammar)
const extensions = {
	js: 'javascript',
	mjs: 'javascript',
	cjs: 'javascript',
	jsx: 'javascript',
	ts: 'typescript',
	tsx: 'typescript',
	java: 'java',
	py: 'python',
	css: 'css',
	scss: 'css',
	less: 'css',
	json: 'json',
	html: 'xml',
	htm: 'xml',
	xml: 'xml',
	svg: 'xml',
	vue: 'xml',
	sh: 'bash',
	bash: 'bash',
	zsh: 'bash',
	sql: 'sql',
	c: 'cpp',
	h: 'cpp',
	cpp: 'cpp',
	hpp: 'cpp',
	cc: 'cpp',
	yml: 'yaml',
	yaml: 'yaml',
}
const LIMIT = 100000,
	sourceMode = ref(false),
	wrap = ref(true)
const language = computed(
	() => extensions[props.name.toLowerCase().split('.').at(-1)] || ''
)
const lines = computed(() => props.text.split('\n').length)
const truncated = computed(
	() => Boolean(language.value) && props.text.length > LIMIT
)
const formatted = computed(() => {
	if (!language.value) return null
	try {
		const output = hljs.highlight(props.text.slice(0, LIMIT), {
			language: language.value,
			ignoreIllegals: true,
		}).value
		return DOMPurify.sanitize(output, {
			ALLOWED_TAGS: ['span'],
			ALLOWED_ATTR: ['class'],
			ALLOW_DATA_ATTR: false,
			ALLOW_ARIA_ATTR: false,
		})
	} catch {
		return null
	}
})
watch(
	() => [props.text, props.name],
	() => {
		sourceMode.value = false
		wrap.value = true
	}
)
</script>
<style scoped>
.private-text {
	color: var(--cloud-ink);
}
.text-tools {
	display: flex;
	align-items: center;
	gap: 8px;
	flex-wrap: wrap;
	padding: 10px 14px;
	background: var(--cloud-soft);
	border-bottom: 1px solid var(--cloud-line);
}
.text-tools button {
	border: 1px solid var(--cloud-line);
	border-radius: 8px;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
	padding: 8px 12px;
	min-height: 36px;
	cursor: pointer;
}
.text-tools button[aria-pressed='true'] {
	border-color: var(--cloud-accent);
	background: var(--cloud-lilac);
}
.text-tools button:hover {
	background: var(--cloud-canvas);
}
.text-tools button:focus-visible,
pre:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.text-tools span,
.text-notice {
	font-size: 12px;
	color: var(--cloud-muted);
}
.text-notice {
	padding: 8px 16px;
	margin: 0;
}
pre {
	padding: 16px;
	margin: 0;
	overflow: auto;
	overflow-wrap: anywhere;
	background: var(--cloud-soft);
	font: 13px/1.8 ui-monospace, SFMono-Regular, Consolas, monospace;
}
code {
	font: inherit;
}
code :deep(.hljs-comment),
code :deep(.hljs-quote) {
	color: var(--cloud-muted);
}
code :deep(.hljs-keyword),
code :deep(.hljs-tag),
code :deep(.hljs-number),
code :deep(.hljs-literal) {
	color: var(--cloud-accent-ink);
}
code :deep(.hljs-keyword),
code :deep(.hljs-title) {
	font-weight: 600;
}
code :deep(.hljs-string),
code :deep(.hljs-attr) {
	color: var(--cloud-primary-dark);
}
@media (max-width: 600px) {
	.text-tools button {
		min-height: 44px;
	}
	pre {
		padding: 12px;
	}
}
</style>
