<template>
	<div
		v-if="!fileDataStore.imgMode"
		ref="fileRef"
		v-loading="loading"
		class="student-cloud-card-file-view-wrapper box"
		element-loading-text="正在读取文件…"
		element-loading-background="rgba(255, 255, 255, 0.6)"
		:class="
			'student-cloud-card-size-' + storageConfigStore.globalConfig?.tableSize
		"
	>
		<div
			v-for="(item, index) in fileDataStore.fileList"
			:key="`${item.type}:${item.path}/${item.name}`"
			:class="tableRowClassName({ row: item, rowIndex: index })"
			class="student-cloud-card-file-item"
			tabindex="0"
			role="button"
			:aria-label="fileItemActionLabel(item)"
			:aria-pressed="selectRows.includes(item)"
			@contextmenu="showCardFileMenu(item, $event)"
			@click="clickRow(item)"
			@dblclick="dblclickRow(item)"
			@keydown.enter.prevent.stop="dblclickRow(item)"
			@keydown.space.prevent.stop="toggleRowSelection(item)"
		>
			<div class="mb-3">
				<div class="student-cloud-card-file-icon">
					<svg-icon :name="'file-type-' + item.icon"></svg-icon>
				</div>
			</div>
			<span class="student-cloud-card-file-name">
				{{ item.name }}
			</span>
		</div>
		<DriveEmptyState
			v-if="canShowEmpty"
			class="cloud-card-empty"
			:has-storage="Boolean(route.params.storageKey)"
			:can-upload="storageConfigStore.permission.upload"
			:share-mode="shareMode"
			:query="shareMode ? '' : directoryNameQuery"
			@upload="openUploadDialog"
			@clear-filter="clearFilter"
		/>
	</div>
</template>

<script setup>
// 业务代码
import DriveEmptyState from '~/components/file/DriveEmptyState.vue'
import useFileUpload from '~/composables/file/useFileUpload'
import useFileData from '~/composables/file/useFileData'
import {
	directoryHasEntries,
	fileItemActionLabel,
} from '~/utils/drive-view.mjs'
const { openUploadDialog } = useFileUpload()
const { searchParam, directoryNameQuery, loadFile } = useFileData()
const route = useRoute()
const clearFilter = () => {
	searchParam.nameQuery = ''
	loadFile()
}
import useFileDataStore from '~/stores/file-data'
let fileDataStore = useFileDataStore()
const hasDirectoryEntries = computed(() =>
	directoryHasEntries(fileDataStore.fileList)
)

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import useFileLoading from '~/composables/file/useFileLoading'
const { loading, directoryLoadError, directoryLoadState } = useFileLoading()

// 表格选择
import useFileSelect from '~/composables/file/useFileSelect'
let {
	initSelectFun,
	selectRowsChange,
	tableRowClassName,
	selectRows,
	checkSelectable,
} = useFileSelect()

// 分享模式
const props = defineProps({
	shareMode: { type: Boolean, default: false },
})
const canShowEmpty = computed(
	() =>
		!loading.value &&
		!hasDirectoryEntries.value &&
		(props.shareMode ||
			(!directoryLoadError.value && directoryLoadState.value !== 'password'))
)

// 文件操作
import useTableOperator from '~/composables/file/useTableOperator'
import useShareTableOperator from '~/composables/file/useShareTableOperator'
const { tableClickRow, tableDbClickRow } = props.shareMode
	? useShareTableOperator()
	: useTableOperator()

import useFileContextMenu from '~/composables/file/useFileContextMenu'
const { showFileMenu } = useFileContextMenu()

const showCardFileMenu = (item, e) => {
	toggleRowSelection(item, true)
	showFileMenu(item, null, e)
}

const clickRow = (item) => {
	tableClickRow(item, {})
}

const dblclickRow = (item) => {
	tableDbClickRow(item)
}

const clearSelection = () => {
	selectRows.value = []
	selectRowsChange(selectRows.value)
}

const toggleRowSelection = (row, selected) => {
	if (!checkSelectable(row)) return
	const isSelected = selectRows.value.includes(row)

	if (selected === undefined) {
		selected = !isSelected
	}

	if (selected) {
		if (!isSelected) {
			selectRows.value.push(row)
		}
	} else {
		selectRows.value = selectRows.value.filter((item) => item !== row)
	}
	selectRowsChange(selectRows.value)
}

// 可选择的文件列表
const computedFileList = computed(() => {
	// 第一个的 type 可能是 BACK，表示返回上一级，不可选
	return fileDataStore.fileList.filter(checkSelectable)
})

const toggleAllSelection = () => {
	if (selectRows.value.length === computedFileList.value.length) {
		selectRows.value = []
	} else {
		selectRows.value = computedFileList.value
	}
	selectRowsChange(selectRows.value)
}

onMounted(() => {
	initSelectFun(clearSelection, toggleRowSelection, toggleAllSelection)
})
</script>

<style scoped lang="scss">
.student-cloud-card-file-view-wrapper {
	@apply grid grid-cols-2 md:grid-cols-4 lg:grid-cols-[repeat(auto-fill,minmax(156px,1fr))] mt-4 select-none;
	gap: 12px;
	min-height: 260px;
	align-content: start;
	background: transparent;
}
.cloud-card-empty {
	grid-column: 1 / -1;
}

.student-cloud-card-file-item {
	@apply relative flex flex-col items-center p-4 rounded-lg cursor-pointer;
	gap: 6px;
	min-height: 128px;
	border: 1px solid #e8ece4;
	background: #fcfdf9;
	transition: border-color 160ms ease, background-color 160ms ease,
		transform 160ms ease;
	&:hover {
		transform: translateY(-2px);
		border-color: #a8beb0;
		background: #f3f7f0;
	}
	.student-cloud-card-file-icon {
		@apply w-12 h-12 rounded-lg flex items-center justify-center;
		background: #f1f4eb;
		:deep(svg) {
			width: 32px;
			height: 32px;
		}
	}

	.student-cloud-card-file-name {
		@apply text-sm text-center line-clamp-2 break-all;
		color: var(--cloud-ink);
		line-height: 1.6;
	}
}

// small 模式
.student-cloud-card-size-small {
	@apply gap-2;
	.student-cloud-card-file-icon {
		@apply w-8 h-8;
	}

	.student-cloud-card-file-name {
		@apply text-xs;
	}
}

// large 模式
.student-cloud-card-size-large {
	.student-cloud-card-file-icon {
		@apply w-16 h-16;
	}

	.student-cloud-card-file-name {
		@apply text-base;
	}
}

// 文件行选中效果
.select-row {
	border-color: #82a99a;
	background: var(--cloud-soft);
}
</style>
