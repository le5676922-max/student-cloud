<template>
	<div
		v-if="actions.count"
		class="file-selection-toolbar"
		role="group"
		aria-label="已选文件操作"
	>
		<span class="file-selection-summary" role="status" aria-live="polite">
			已选 <strong>{{ actions.count }}</strong> 项
			<small v-if="actions.files && actions.folders">文件与文件夹</small>
		</span>
		<div class="file-selection-buttons">
			<el-button
				v-if="actions.download"
				:disabled="disabled"
				@click="runAction('download')"
			>
				<ArrowDownTrayIcon aria-hidden="true" />下载
			</el-button>
			<el-button
				v-if="actions.share"
				:disabled="disabled"
				@click="runAction('share')"
			>
				<ShareIcon aria-hidden="true" />分享
			</el-button>
			<el-button
				v-if="actions.rename"
				:disabled="disabled"
				@click="runAction('rename')"
			>
				<PencilSquareIcon aria-hidden="true" />重命名
			</el-button>
			<el-button
				v-if="actions.move"
				:disabled="disabled"
				@click="runAction('move')"
			>
				<FolderArrowDownIcon aria-hidden="true" />移动到
			</el-button>
			<el-button
				v-if="actions.copy"
				:disabled="disabled"
				@click="runAction('copy')"
			>
				<DocumentDuplicateIcon aria-hidden="true" />复制到
			</el-button>
			<el-button
				v-if="actions.delete"
				class="file-selection-delete"
				:disabled="disabled"
				@click="runAction('delete')"
			>
				<TrashIcon aria-hidden="true" />删除
			</el-button>
		</div>
		<el-button
			text
			:disabled="disabled"
			class="file-selection-clear"
			@click="clearSelection"
		>
			<XMarkIcon aria-hidden="true" />取消选择
		</el-button>
	</div>
</template>

<script setup>
import {
	ArrowDownTrayIcon,
	DocumentDuplicateIcon,
	FolderArrowDownIcon,
	PencilSquareIcon,
	ShareIcon,
	TrashIcon,
	XMarkIcon,
} from '@heroicons/vue/24/outline'
import { driveSelectionActions } from '~/utils/drive-selection-actions.mjs'
import useFileSelect from '~/composables/file/useFileSelect'
import useFileLoading from '~/composables/file/useFileLoading'
import useFileOperator from '~/composables/file/useFileOperator'
import useFileShare from '~/composables/file/useFileShare'
import useStorageConfigStore from '~/stores/storage-config'

const { selectRows, clearSelection } = useFileSelect()
const { loading } = useFileLoading()
const {
	operationBusy,
	batchDownloadFile,
	rename,
	moveTo,
	copyTo,
	batchDelete,
} = useFileOperator()
const { openCreateShareDialog } = useFileShare()
const storageConfigStore = useStorageConfigStore()
const actions = computed(() =>
	driveSelectionActions(
		selectRows.value,
		storageConfigStore.permission,
		storageConfigStore.folderConfig.metadata
	)
)
const disabled = computed(() => loading.value || operationBusy.value)
const handlers = {
	download: batchDownloadFile,
	share: openCreateShareDialog,
	rename,
	move: moveTo,
	copy: copyTo,
	delete: batchDelete,
}
const runAction = (action) => {
	// Recheck immediately before opening a dialog; a late permission refresh may
	// revoke an action after its button has been rendered.
	if (disabled.value || !actions.value[action]) return
	return handlers[action]?.()
}
</script>

<style scoped>
.file-selection-toolbar {
	position: sticky;
	top: 8px;
	z-index: 5;
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 12px;
	margin: 12px 0;
	padding: 12px 14px;
	border: 1px solid #cbded4;
	border-radius: 10px;
	background: #f0f6f2;
	box-shadow: 0 3px 12px #27393608;
}
.file-selection-summary {
	color: var(--cloud-ink);
	font-size: 13px;
	white-space: nowrap;
}
.file-selection-summary strong {
	color: var(--cloud-primary);
}
.file-selection-summary small {
	margin-left: 8px;
	color: var(--cloud-muted);
}
.file-selection-buttons {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
}
.file-selection-toolbar .el-button {
	margin-left: 0;
	padding: 8px 10px;
}
.file-selection-toolbar svg {
	width: 16px;
	height: 16px;
	margin-right: 5px;
}
.file-selection-delete {
	--el-button-text-color: #ad4840;
	--el-button-hover-text-color: #94362e;
}
.file-selection-clear {
	margin-left: auto !important;
	color: var(--cloud-muted);
}
@media (max-width: 767px) {
	.file-selection-toolbar {
		gap: 9px;
		padding: 10px;
		top: 4px;
	}
	.file-selection-buttons {
		order: 3;
		width: 100%;
	}
	.file-selection-toolbar .el-button {
		font-size: 12px;
	}
}
</style>
