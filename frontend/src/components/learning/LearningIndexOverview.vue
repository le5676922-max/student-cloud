<template>
	<section class="index-overview" aria-label="当前课程索引概况">
		<header>
			<h3>资料索引概况</h3>
			<span>点击分类筛选，原文件不会被修改</span>
		</header>
		<div class="index-filters" role="group" aria-label="按索引情况筛选资料">
			<button
				v-for="item in options"
				:key="item.key"
				type="button"
				:aria-pressed="modelValue === item.key"
				:class="{ selected: modelValue === item.key }"
				@click="select(item.key)"
			>
				<strong>{{ counts[item.key] }}</strong>
				<span>{{ item.label }}</span>
			</button>
		</div>
		<p class="index-scope">
			仅统计当前课程保存的索引记录，不代表原文件仍有效；检索时会重新校验权限和文件变化。
		</p>
		<p v-if="advice" class="index-advice" role="status">{{ advice }}</p>
	</section>
</template>

<script setup>
import { computed } from 'vue'
import { learningIndexOverview } from '~/utils/learning-index-overview.mjs'

const props = defineProps({
	materials: { type: Array, default: () => [] },
	modelValue: { type: String, default: 'all' },
})
const emit = defineEmits(['update:modelValue'])
const options = [
	{ key: 'all', label: '全部资料' },
	{ key: 'indexed', label: '文字已索引' },
	{ key: 'partial', label: '部分索引' },
	{ key: 'unknown', label: '覆盖未知' },
	{ key: 'untagged', label: '未设标签' },
]
const counts = computed(() => learningIndexOverview(props.materials))
const advice = computed(
	() =>
		({
			indexed: '已索引可提取的文字，不等于图片文字或完整文档均可检索。',
			partial: '这些资料只覆盖部分内容。请查看覆盖说明，拆分长文后补充索引。',
			unknown: '覆盖信息不足，可能是旧版或空索引。可逐份重新索引后检查。',
			untagged: '未设置标签的资料仍可检索；添加新资料时可设置课程主题标签。',
		}[props.modelValue] || '')
)
function select(key) {
	if (options.some((item) => item.key === key)) emit('update:modelValue', key)
}
</script>

<style scoped>
.index-overview {
	margin: 0 0 18px;
	padding: 16px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 16px;
	background: var(--cloud-soft, #f1f1f3);
}
.index-overview header {
	display: flex;
	justify-content: space-between;
	align-items: baseline;
	gap: 8px;
	flex-wrap: wrap;
}
.index-overview h3 {
	margin: 0;
	font-size: 14px;
	font-weight: 600;
}
.index-overview header > span,
.index-scope,
.index-advice {
	font-size: 12px;
	line-height: 1.6;
	color: var(--cloud-muted, #6d7974);
}
.index-filters {
	display: grid;
	grid-template-columns: repeat(5, minmax(0, 1fr));
	gap: 8px;
	margin-top: 12px;
}
.index-filters button {
	display: flex;
	flex-direction: column;
	align-items: flex-start;
	gap: 5px;
	padding: 10px 12px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 12px;
	background: var(--cloud-surface, #fff);
	color: var(--cloud-ink, #273936);
	text-align: left;
	cursor: pointer;
	transition: transform 200ms var(--cloud-ease, ease),
		background-color 180ms ease, border-color 180ms ease, box-shadow 180ms ease;
}
.index-filters button.selected {
	border-color: var(--cloud-primary, #262629);
	background: var(--cloud-primary, #262629);
	color: white;
	box-shadow: 0 4px 10px rgb(28 29 32 / 12%);
}
@media (hover: hover) {
	.index-filters button:not(.selected):hover {
		transform: translateY(-2px);
		border-color: #c5b7f7;
		background: var(--cloud-lilac, #f0ebff);
	}
}
.index-filters button:focus-visible {
	outline: 2px solid var(--cloud-primary, #277566);
	outline-offset: 2px;
}
.index-filters strong {
	font-size: 20px;
	font-weight: 600;
}
.index-filters span {
	font-size: 12px;
}
.index-scope {
	margin: 12px 0 0;
}
.index-advice {
	margin: 8px 0 0;
	color: #805f36;
}
@media (max-width: 640px) {
	.index-filters {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}
}
@media (prefers-reduced-motion: reduce) {
	.index-filters button,
	.index-filters button:not(.selected):hover {
		transition: none;
		transform: none;
	}
}
</style>
