<template>
	<!-- 文件区 -->
	<el-table
		v-if="!fileDataStore.imgMode"
		id="ListTable"
		ref="fileRef"
		v-loading="basicLoading"
		element-loading-text="正在读取文件…"
		element-loading-background="rgba(255, 255, 255, 0.6)"
		:size="storageConfigStore.globalConfig?.tableSize"
		empty-text=""
		:row-class-name="tableRowClassName"
		:class="{
			'student-cloud-table-empty': !hasDirectoryEntries,
		}"
		:data="skeletonLoading ? skeletonData : fileDataStore.fileList"
		@sort-change="sortChangeMethod"
		@row-click="tableClickRow"
		@row-dblclick="tableDbClickRow"
		@cell-mouse-enter="tableHoverRow"
		@cell-mouse-leave="tableLeaveRow"
		@row-contextmenu="showFileMenu"
		@selection-change="selectRowsChange"
	>
		<template #empty>
			<div v-if="canShowEmpty">
				<DriveEmptyState
					:has-storage="Boolean(route.params.storageKey)"
					:can-upload="storageConfigStore.permission.upload"
					:share-mode="shareMode"
					:query="shareMode ? '' : directoryNameQuery"
					@upload="openUploadDialog"
					@clear-filter="clearFilter"
				/>
			</div>
		</template>

		<el-table-column
			width="30px"
			type="selection"
			:selectable="checkSelectable"
		/>

		<el-table-column
			prop="name"
			sortable="custom"
			class-name="student-cloud-table-col-name"
			label-class-name="table-header-left"
			min-width="100%"
		>
			<template #header>
				<DocumentTextIcon />
				<span>文件名</span>
			</template>
			<template #default="scope">
				<div v-show="skeletonLoading">
					<el-skeleton animated>
						<template #template>
							<el-skeleton-item
								variant="circle"
								style="
									vertical-align: middle;
									width: 18px;
									height: 18px;
									margin-right: 20px;
								"
							/>
							<el-skeleton-item
								variant="text"
								style="vertical-align: middle; width: 30%"
							/>
						</template>
					</el-skeleton>
				</div>
				<div v-show="!skeletonLoading" class="cloud-file-name-cell">
					<span
						class="cloud-file-type-mark"
						:class="{
							'is-folder': ['ROOT', 'FOLDER', 'BACK'].includes(scope.row.type),
						}"
						><svg-icon :name="'file-type-' + scope.row.icon" aria-hidden="true"
					/></span>
					<button
						type="button"
						class="cloud-file-name-text"
						:title="scope.row.name"
						:aria-label="fileItemActionLabel(scope.row)"
						:disabled="loading"
						@click.stop="clickFileName(scope.row, $event)"
						@dblclick.stop="tableDbClickRow(scope.row)"
					>
						{{ scope.row.name }}
					</button>
				</div>
			</template>
		</el-table-column>

		<el-table-column
			v-if="isNotMobile"
			prop="time"
			sortable="custom"
			class-name="student-cloud-table-col-time"
			min-width="25%"
		>
			<template #header>
				<CalendarDaysIcon />
				<span>修改时间</span>
			</template>
			<template #default="scope">
				<div v-show="skeletonLoading">
					<el-skeleton animated>
						<template #template>
							<el-skeleton-item variant="text" style="width: 60%" />
						</template>
					</el-skeleton>
				</div>
				<div v-show="!skeletonLoading">
					{{ scope.row.time }}
				</div>
			</template>
		</el-table-column>

		<el-table-column
			v-if="storageConfigStore.globalConfig?.mobileShowSize || isNotMobile"
			prop="size"
			class-name="student-cloud-table-col-size"
			sortable="custom"
			:width="sizeColumnWeight"
			min-width="20%"
		>
			<template #header>
				<CircleStackIcon />
				<span>大小</span>
			</template>
			<template #default="scope">
				<div v-show="skeletonLoading">
					<el-skeleton animated>
						<template #template>
							<el-skeleton-item variant="text" style="width: 30%" />
						</template>
					</el-skeleton>
				</div>
				<div v-show="!skeletonLoading">
					{{ fileSizeFilter(scope.row, null, scope.row.size) }}
				</div>
			</template>
		</el-table-column>

		<template #append>
			<DriveEmptyState
				v-if="canShowEmpty && fileDataStore.fileList.length"
				:has-storage="Boolean(route.params.storageKey)"
				:can-upload="storageConfigStore.permission.upload"
				:share-mode="shareMode"
				:query="shareMode ? '' : directoryNameQuery"
				@upload="openUploadDialog"
				@clear-filter="clearFilter"
			/>
			<load-more-file
				v-show="skeletonLoading !== true && basicLoading !== true"
			/>
		</template>
	</el-table>
</template>

<script setup>
import DriveEmptyState from '~/components/file/DriveEmptyState.vue'
import useFileUpload from '~/composables/file/useFileUpload'
import {
	directoryHasEntries,
	fileItemActionLabel,
} from '~/utils/drive-view.mjs'
const { openUploadDialog } = useFileUpload()
const route = useRoute()

import { fileSizeFilter, isNotMobile } from '~/utils'
import {
	CalendarDaysIcon,
	CircleStackIcon,
	DocumentTextIcon,
} from '@heroicons/vue/24/outline'

// 业务代码
import useFileDataStore from '~/stores/file-data'
let fileDataStore = useFileDataStore()
const hasDirectoryEntries = computed(() =>
	directoryHasEntries(fileDataStore.fileList)
)

// 文件数据相关
import useFileData from '~/composables/file/useFileData'
const {
	sortChangeMethod: defaultSortChangeMethod,
	skeletonData,
	searchParam,
	directoryNameQuery,
	loadFile,
} = useFileData()
const clearFilter = () => {
	searchParam.nameQuery = ''
	loadFile()
}

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import useFileLoading from '~/composables/file/useFileLoading'
const {
	basicLoading,
	skeletonLoading,
	loading,
	directoryLoadError,
	directoryLoadState,
} = useFileLoading()

// 表格选择
import useFileSelect from '~/composables/file/useFileSelect'
let { initSelectFun, checkSelectable, selectRowsChange, tableRowClassName } =
	useFileSelect()

const fileRef = ref()
onMounted(() => {
	initSelectFun(
		fileRef.value.clearSelection,
		fileRef.value.toggleRowSelection,
		fileRef.value.toggleAllSelection
	)
})

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

// 分享页排序处理
import useShareData from '~/composables/share/useShareData'
const { sortShareChangeMethod } = useShareData()

// 绑定表格排序回调：分享页使用分享排序，其它页使用默认排序
const sortChangeMethod = props.shareMode
	? sortShareChangeMethod
	: defaultSortChangeMethod

// 文件操作
import useTableOperator from '~/composables/file/useTableOperator'
import useShareTableOperator from '~/composables/file/useShareTableOperator'
const { tableClickRow, tableDbClickRow, tableHoverRow, tableLeaveRow } =
	props.shareMode ? useShareTableOperator() : useTableOperator()
const clickFileName = (row, event) => {
	if (loading.value) return
	// Native keyboard/assistive activation opens the row; pointer clicks retain the configured click mode.
	if (event.detail === 0) tableDbClickRow(row)
	else tableClickRow(row, {})
}

import useFileContextMenu from '~/composables/file/useFileContextMenu'
const { showFileMenu } = useFileContextMenu()

// 文件大小列宽度
const sizeColumnWeight = computed(() => {
	let size = storageConfigStore.globalConfig?.tableSize
	let mobileShowSize = storageConfigStore.globalConfig?.mobileShowSize
	if (isNotMobile.value || mobileShowSize === false) {
		return ''
	}
	switch (size) {
		case 'small':
			return '85px'
		case 'default':
			return '100px'
		case 'large':
			return '120px'
		default:
			return ''
	}
})
</script>

<style scoped lang="scss">
// 用于页面数据为空时，撑满整个页面
#ListTable {
	// 左右收缩与顶部栏对齐
	@apply h-full;

	// 文件行选中效果
	:deep(.select-row) {
		background-color: var(--el-table-row-hover-bg-color);
	}

	// 空白页样式
	:deep(.el-table__empty-block) {
		min-height: 260px;
	}
	:deep(.el-table__empty-text) {
		@apply w-full;
	}

	// 隐藏横向滚动条
	:deep(.el-scrollbar__bar.is-horizontal) {
		@apply hidden #{!important};
	}
}

// 文件列表主体
.el-table {
	// 避免拖拽选中
	@apply select-none;

	:deep(.el-table__body-wrapper) {
		font-weight: 400;
	}
	:deep(.el-table__cell) {
		padding-top: 11px;
		padding-bottom: 11px;
	}

	/* 表头 -- icon 位置和大小 */
	.el-table__header-wrapper svg {
		@apply mr-2 -mt-0.5 w-3 inline;
	}

	/* 表身 -- 文件名列 icon 位置 */
	.el-table__body-wrapper .student-cloud-table-col-name svg {
		@apply relative align-middle text-xl mr-1.5 inline;
	}
}
.cloud-file-name-cell {
	display: flex;
	align-items: center;
	gap: 12px;
	min-width: 0;
}
.cloud-file-type-mark {
	display: inline-flex;
	align-items: center;
	justify-content: center;
	flex-shrink: 0;
	width: 34px;
	height: 34px;
	border-radius: 8px;
	background: var(--cloud-soft);
}
.cloud-file-type-mark.is-folder {
	background: var(--cloud-warm);
}
.cloud-file-type-mark :deep(svg) {
	width: 23px;
	height: 23px;
	margin: 0 !important;
}
.cloud-file-name-text {
	flex: 1;
	min-width: 0;
	padding: 2px 0;
	border: 0;
	background: transparent;
	font: inherit;
	text-align: left;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
	color: var(--cloud-ink);
	cursor: pointer;
}
.cloud-file-name-text:focus-visible {
	outline: 2px solid var(--cloud-primary);
	outline-offset: 3px;
	border-radius: 3px;
}
.cloud-file-name-text:disabled {
	cursor: wait;
}

// table default 模式样式
.el-table.el-table--default {
	/* 表头 -- icon 大小 */
	.el-table__header-wrapper svg {
		@apply w-4;
	}

	/* 表身 -- 文字大小 */
	.el-table__body-wrapper .student-cloud-table-col-name svg {
		@apply text-2xl;
	}
}

// table large 模式样式
.el-table.el-table--large {
	/* 表头 -- icon 大小 */
	.el-table__header-wrapper svg {
		@apply w-5;
	}

	/* 表身 -- 文字大小 */
	.el-table__body-wrapper .student-cloud-table-col-name svg {
		@apply text-3xl;
	}
}

.icon {
	width: 1em;
	height: 1em;
	fill: currentColor;
	overflow: hidden;
}
</style>
