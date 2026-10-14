<template>
	<div class="drive-empty-state">
		<span class="drive-empty-mark" aria-hidden="true"
			><MagnifyingGlassIcon v-if="query.trim()" /><FolderOpenIcon v-else
		/></span>
		<h2>{{ state.title }}</h2>
		<p>
			{{
				!emptyNativePage &&
				hasStorage &&
				!canUpload &&
				!query.trim() &&
				!shareMode
					? '文件准备好后会显示在这里。需要上传权限时，请联系管理员。'
					: state.detail
			}}
		</p>
		<el-button v-if="query.trim()" @click="$emit('clear-filter')"
			>清空筛选</el-button
		>
		<el-button
			v-else-if="hasStorage && canUpload && !shareMode && !emptyNativePage"
			type="primary"
			@click="$emit('upload')"
			>上传第一个文件</el-button
		>
	</div>
</template>

<script setup>
import { computed } from 'vue'
import { FolderOpenIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline'
import { driveEmptyState } from '~/utils/drive-presentation.mjs'
import useFileDataStore from '~/stores/file-data'
const fileDataStore = useFileDataStore()
const props = defineProps({
	hasStorage: Boolean,
	canUpload: Boolean,
	shareMode: Boolean,
	query: { type: String, default: '' },
})
defineEmits(['clear-filter', 'upload'])
const emptyNativePage = computed(
	() =>
		!props.shareMode &&
		fileDataStore.directoryPage?.nativePagination &&
		fileDataStore.directoryPage.count === 0 &&
		Boolean(fileDataStore.directoryPage.nextCursor)
)
const state = computed(() =>
	emptyNativePage.value
		? {
				title: '本页没有可显示的文件',
				detail: '后续页面可能有文件，请点击下一页继续浏览。',
		  }
		: driveEmptyState(props)
)
</script>

<style scoped>
.drive-empty-state {
	display: flex;
	flex-direction: column;
	align-items: center;
	gap: 10px;
	padding: 58px 20px;
	line-height: 1.7;
	text-align: center;
}
.drive-empty-mark {
	display: grid;
	place-items: center;
	width: 72px;
	height: 72px;
	margin-bottom: 8px;
	border-radius: 22px;
	background: #f7eedf;
	color: #b1884b;
}
.drive-empty-mark svg {
	width: 36px;
	height: 36px;
}
.drive-empty-state h2 {
	margin: 0;
	color: var(--cloud-ink);
	font-size: 16px;
	font-weight: 500;
}
.drive-empty-state p {
	max-width: 330px;
	margin: 0 0 8px;
	color: var(--cloud-muted);
	font-size: 13px;
}
@media (max-width: 767px) {
	.drive-empty-state {
		padding: 38px 12px;
	}
}
</style>
