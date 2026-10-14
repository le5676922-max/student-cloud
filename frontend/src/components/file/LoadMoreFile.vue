<template>
	<div v-if="page" class="directory-pagination" aria-label="文件分页">
		<span v-if="page.nativePagination" role="status"
			>快速浏览（存储源顺序） · 第 {{ page.pageIndex + 1 }} 页 · 本页
			{{ page.count }} 项 · 总数未知</span
		>
		<span v-else role="status"
			>共 {{ page.total }} 项 · 本页 {{ page.count }} 项<span v-if="page.total">
				· 第 {{ Math.floor(page.offset / page.pageSize) + 1 }} 页</span
			></span
		>
		<el-button
			:disabled="loading || !page.previousCursor"
			@click="loadPage(page.previousCursor)"
			>上一页</el-button
		>
		<el-button
			:disabled="loading || !page.nextCursor"
			@click="loadPage(page.nextCursor)"
			>下一页</el-button
		>
		<small>选择、画廊和连续播放仅针对当前页</small>
	</div>
	<div v-else-if="legacyHasMore" class="directory-pagination">
		<span>共 {{ allSize }} 项，已显示 {{ fileDataStore.loadFileSize }} 项</span>
		<el-button type="primary" @click="loadMoreLegacy">加载更多</el-button>
	</div>
</template>

<script setup>
import { computed } from 'vue'
import useFileDataStore from '~/stores/file-data'
import useStorageConfigStore from '~/stores/storage-config'
import useFileData from '~/composables/file/useFileData'

const fileDataStore = useFileDataStore()
const storageConfigStore = useStorageConfigStore()
const { loadPage, loading } = useFileData()
const page = computed(() => fileDataStore.directoryPage)
const allSize = computed(
	() =>
		fileDataStore.fileListSource.filter((item) => item.type !== 'BACK').length
)
const legacyHasMore = computed(
	() => allSize.value > 0 && fileDataStore.loadFileSize < allSize.value
)
const loadMoreLegacy = () =>
	fileDataStore.updateLoadFileSize(
		fileDataStore.loadFileSize + storageConfigStore.globalConfig.loadMoreSize
	)
</script>

<style scoped>
.directory-pagination {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	justify-content: center;
	gap: 12px;
	padding: 16px 8px;
	font-size: 13px;
}
small {
	color: #64748b;
}
</style>
