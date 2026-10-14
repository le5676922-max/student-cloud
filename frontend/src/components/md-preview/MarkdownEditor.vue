<template>
	<section class="markdown-editor" :style="{ height }">
		<div class="markdown-editor-toolbar" aria-label="Markdown 格式工具">
			<button
				v-for="item in tools"
				:key="item.label"
				type="button"
				@click="insert(item)"
			>
				{{ item.label }}
			</button>
		</div>
		<div class="markdown-editor-columns">
			<textarea
				ref="input"
				:value="modelValue"
				aria-label="Markdown 编辑内容"
				spellcheck="false"
				@input="emit('update:modelValue', $event.target.value)"
			/>
			<div class="markdown-editor-preview" aria-label="Markdown 预览">
				<v-md-preview :text="modelValue" />
			</div>
		</div>
	</section>
</template>

<script setup>
const props = defineProps({
	modelValue: { type: String, default: '' },
	height: { type: String, default: '400px' },
})
const emit = defineEmits(['update:modelValue'])
const input = ref()
const tools = [
	{ label: '标题', before: '### ', after: '', sample: '标题' },
	{ label: '加粗', before: '**', after: '**', sample: '文字' },
	{ label: '斜体', before: '*', after: '*', sample: '文字' },
	{ label: '列表', before: '- ', after: '', sample: '列表项' },
	{ label: '链接', before: '[', after: '](https://)', sample: '链接文字' },
	{ label: '代码', before: '\n```\n', after: '\n```\n', sample: '代码' },
]
function insert(tool) {
	const field = input.value
	const start = field.selectionStart
	const end = field.selectionEnd
	const selected = props.modelValue.slice(start, end) || tool.sample
	emit(
		'update:modelValue',
		props.modelValue.slice(0, start) +
			tool.before +
			selected +
			tool.after +
			props.modelValue.slice(end)
	)
	nextTick(() => {
		field.focus()
		field.setSelectionRange(
			start + tool.before.length,
			start + tool.before.length + selected.length
		)
	})
}
</script>

<style scoped>
.markdown-editor {
	display: flex;
	flex-direction: column;
	min-height: 220px;
	border: 1px solid var(--el-border-color);
	border-radius: 6px;
	overflow: hidden;
}
.markdown-editor-toolbar {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	padding: 8px;
	background: var(--el-fill-color-light);
}
.markdown-editor-toolbar button {
	padding: 4px 9px;
	border-radius: 4px;
	background: var(--el-bg-color);
}
.markdown-editor-columns {
	display: grid;
	grid-template-columns: 1fr 1fr;
	min-height: 0;
	flex: 1;
}
.markdown-editor textarea {
	width: 100%;
	height: 100%;
	padding: 14px;
	resize: none;
	outline: none;
	border-right: 1px solid var(--el-border-color);
	font: 14px/1.6 monospace;
	background: var(--el-bg-color);
	color: var(--el-text-color-primary);
}
.markdown-editor-preview {
	padding: 14px;
	overflow: auto;
	min-width: 0;
}
@media (max-width: 640px) {
	.markdown-editor-columns {
		grid-template-columns: 1fr;
		overflow: auto;
	}
	.markdown-editor textarea {
		min-height: 200px;
	}
}
</style>
