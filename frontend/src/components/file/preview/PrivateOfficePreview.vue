<template>
	<section class="document-preview" aria-label="文档内容预览">
		<p class="document-note">
			本地只读预览，仅显示文字与表格；图片、动画及原始排版请下载后查看。公式不会重新计算。
		</p>
		<p v-if="model.truncated" class="document-note" role="status">
			文档较大，当前展示预览预算内的部分内容。
		</p>
		<template v-if="model.kind === 'workbook'">
			<div class="sheet-tabs" role="tablist" aria-label="工作表">
				<button
					v-for="(sheet, index) in model.sheets"
					:id="`private-sheet-tab-${index}`"
					:key="index"
					type="button"
					role="tab"
					:tabindex="sheetIndex === index ? 0 : -1"
					aria-controls="private-sheet-content"
					:aria-selected="sheetIndex === index"
					@click="chooseSheet(index)"
					@keydown="sheetKeydown($event, index)"
				>
					{{ sheet.name }}
				</button>
			</div>
			<div
				id="private-sheet-content"
				class="table-scroll"
				tabindex="0"
				role="tabpanel"
				:aria-labelledby="`private-sheet-tab-${sheetIndex}`"
				:aria-label="`${activeSheet?.name || '工作表'}内容`"
			>
				<table v-if="activeSheet?.rows.length">
					<caption>
						{{
							activeSheet.name
						}}，第
						{{
							sheetRows.length ? (page - 1) * 50 + 1 : 0
						}}–{{
							Math.min(page * 50, activeSheet.rows.length)
						}}
						行，共
						{{
							activeSheet.rows.length
						}}
						行
					</caption>
					<tbody>
						<tr v-for="(row, index) in sheetRows" :key="index">
							<th scope="row">{{ (page - 1) * 50 + index + 1 }}</th>
							<td v-for="(value, column) in row" :key="column">{{ value }}</td>
						</tr>
					</tbody>
				</table>
				<p v-else class="document-note">此工作表没有可预览的数据。</p>
			</div>
		</template>
		<article
			v-else-if="model.kind === 'slides'"
			class="document-paper"
			:aria-label="`第 ${page} 页幻灯片`"
		>
			<p class="slide-number">{{ page }} / {{ model.pages.length }}</p>
			<p
				v-for="(paragraph, index) in model.pages[page - 1]?.paragraphs"
				:key="index"
			>
				{{ paragraph }}
			</p>
			<p v-if="!model.pages[page - 1]?.paragraphs.length">
				此页没有可提取的文字。
			</p>
		</article>
		<article v-else class="document-paper" aria-label="Word 文档内容">
			<template v-for="(block, index) in documentBlocks" :key="index"
				><p v-if="block.type === 'paragraph'">{{ block.text || '\u00a0' }}</p>
				<div v-else class="table-scroll">
					<table>
						<tbody>
							<tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
								<td v-for="(value, column) in row" :key="column">
									{{ value }}
								</td>
							</tr>
						</tbody>
					</table>
				</div></template
			>
			<p v-if="!model.blocks?.length">文档没有可提取的文字。</p>
		</article>
		<nav v-if="pages > 1" class="document-pager" aria-label="文档分页">
			<button type="button" :disabled="page <= 1" @click="page--">上一页</button
			><span role="status">{{ page }} / {{ pages }}</span
			><button type="button" :disabled="page >= pages" @click="page++">
				下一页
			</button>
		</nav>
	</section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ model: { type: Object, required: true } })
const sheetIndex = ref(0),
	page = ref(1)
const activeSheet = computed(() => props.model.sheets?.[sheetIndex.value])
const sheetRows = computed(
	() =>
		activeSheet.value?.rows.slice((page.value - 1) * 50, page.value * 50) || []
)
const documentBlocks = computed(
	() => props.model.blocks?.slice((page.value - 1) * 50, page.value * 50) || []
)
const pages = computed(() =>
	Math.max(
		1,
		props.model.kind === 'slides'
			? props.model.pages.length
			: Math.ceil(
					(props.model.kind === 'workbook'
						? activeSheet.value?.rows.length || 0
						: props.model.blocks?.length || 0) / 50
			  )
	)
)
const chooseSheet = (index) => {
	sheetIndex.value = index
	page.value = 1
}
const sheetKeydown = (event, index) => {
	const count = props.model.sheets.length
	const next =
		event.key === 'Home'
			? 0
			: event.key === 'End'
			? count - 1
			: event.key === 'ArrowRight'
			? (index + 1) % count
			: event.key === 'ArrowLeft'
			? (index - 1 + count) % count
			: null
	if (next === null) return
	event.preventDefault()
	chooseSheet(next)
	event.currentTarget.parentElement
		.querySelectorAll('[role="tab"]')
		[next]?.focus()
}
watch(
	() => props.model,
	() => {
		sheetIndex.value = 0
		page.value = 1
	}
)
</script>
<style scoped>
.document-preview {
	color: var(--cloud-ink);
}
.document-note {
	margin: 0;
	padding: 12px 16px;
	color: var(--cloud-muted);
	font-size: 12px;
	background: var(--cloud-soft);
}
.document-paper {
	max-width: 780px;
	min-height: 300px;
	margin: 16px auto;
	padding: 24px;
	background: var(--cloud-surface);
	border: 1px solid var(--cloud-line);
	line-height: 1.8;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
}
.document-paper p {
	margin: 0 0 14px;
}
.slide-number {
	color: var(--cloud-muted);
	font-size: 12px;
	text-align: right;
}
.sheet-tabs {
	display: flex;
	gap: 6px;
	overflow-x: auto;
	padding: 12px;
	border-bottom: 1px solid var(--cloud-line);
}
.sheet-tabs button,
.document-pager button {
	padding: 8px 12px;
	border: 1px solid var(--cloud-line);
	border-radius: 8px;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
	white-space: nowrap;
	cursor: pointer;
}
.sheet-tabs button[aria-selected='true'] {
	border-color: var(--cloud-accent);
	background: var(--cloud-lilac);
}
.sheet-tabs button:hover,
.document-pager button:not(:disabled):hover {
	background: var(--cloud-soft);
}
button:focus-visible,
.table-scroll:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.document-pager button:disabled {
	opacity: 0.5;
	cursor: default;
}
.table-scroll {
	max-width: 100%;
	overflow: auto;
	scrollbar-gutter: stable;
}
table {
	width: 100%;
	border-collapse: collapse;
	font-size: 13px;
}
caption {
	padding: 10px;
	text-align: left;
	color: var(--cloud-muted);
}
td,
th {
	padding: 8px 12px;
	border: 1px solid var(--cloud-line);
	text-align: left;
	white-space: pre-wrap;
	min-width: 90px;
	max-width: 320px;
	overflow-wrap: anywhere;
}
th {
	min-width: 40px;
	background: var(--cloud-soft);
	font-weight: 400;
}
.document-pager {
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 16px;
	padding: 12px;
}
.document-pager span {
	font-size: 13px;
	color: var(--cloud-muted);
}
@media (max-width: 600px) {
	.document-paper {
		padding: 16px;
		margin: 8px;
	}
	.document-note {
		padding: 10px;
	}
	.document-pager button {
		min-height: 44px;
	}
}
</style>
