<template>
	<div
		v-if="generateLinkResultDialogVisible"
		class="student-cloud-file-download-link-body"
	>
		<el-dialog
			v-model="generateLinkResultDialogVisible"
			:destroy-on-close="true"
			title="直链"
			:class="{
				'student-cloud-file-download-link-dialog-multiple':
					selectFiles.length > 1,
				'student-cloud-file-download-link-dialog-single':
					selectFiles.length <= 1,
			}"
			draggable
			top="5vh"
			@close="generateLinkResultDialogVisible = false"
		>
			<el-table
				v-if="selectFiles.length > 1"
				v-loading="loading"
				element-loading-text="生成中..."
				class="student-cloud-download-link-table"
				:max-height="currentPageHeight * 0.7"
				:data="dataList"
				@cell-click="handleCellClick"
			>
				<el-table-column prop="name" show-overflow-tooltip>
					<template #header>
						文件名
						<el-tooltip
							:show-arrow="false"
							:offset="15"
							effect="dark"
							content="批量复制到剪贴板"
							placement="top"
						>
							<i-ic-baseline-content-copy
								class="inline cursor-pointer l-5"
								@click="batchCopyLinkField('name')"
							/>
						</el-tooltip>
					</template>
					<template #default="scope">
						{{ scope.row.name }}
					</template>
				</el-table-column>

				<el-table-column
					v-if="
						storageConfigStore.permission.pathLink &&
						(currentLinkDialogType === 'all' ||
							currentLinkDialogType === 'pathLink')
					"
					prop="pathLink"
					show-overflow-tooltip
				>
					<template #header>
						直链
						<el-tooltip
							:show-arrow="false"
							:offset="15"
							effect="dark"
							content="批量复制到剪贴板"
							placement="top"
						>
							<i-ic-baseline-content-copy
								class="inline cursor-pointer l-5"
								@click="batchCopyLinkField('pathLink')"
							/>
						</el-tooltip>
					</template>
					<template #default="scope">
						{{ scope.row.pathLink }}
					</template>
				</el-table-column>

				<el-table-column
					v-if="
						storageConfigStore.permission.shortLink &&
						(currentLinkDialogType === 'all' ||
							currentLinkDialogType === 'shortLink')
					"
					prop="shortLink"
					show-overflow-tooltip
				>
					<template #header>
						短链
						<el-tooltip
							:show-arrow="false"
							:offset="15"
							effect="dark"
							content="批量复制到剪贴板"
							placement="top"
						>
							<i-ic-baseline-content-copy
								class="inline cursor-pointer l-5"
								@click="batchCopyLinkField('shortLink')"
							/>
						</el-tooltip>
					</template>
					<template #default="scope">
						{{ scope.row.shortLink }}
					</template>
				</el-table-column>
			</el-table>

			<el-row v-if="selectFiles.length === 1 && data" class="md:space-y-6">
				<div class="flex flex-row space-x-10 w-full">
					<QrCodePreview
						class="w-3/4 max-w-xs"
						:src="data.currentImg"
						:alt="data.currentImg ? '文件二维码' : ''"
						:filename="`${data?.name || 'link'}-qrcode.png`"
					/>
					<div class="flex flex-col mt-3 space-y-3.5 overflow-y-auto">
						<div
							class="border w-10 h-10 p-1 rounded-md cursor-pointer"
							:class="
								data.currentImg === data.qrcode.a1 ? 'border-blue-400' : ''
							"
							@click="data.currentImg = data.qrcode.a1"
						>
							<el-image class="w-full h-full" :src="data.qrcode.a1" />
						</div>
						<div
							class="border w-10 h-10 p-1 rounded-md cursor-pointer"
							:class="
								data.currentImg === data.qrcode.a2 ? 'border-blue-400' : ''
							"
							@click="data.currentImg = data.qrcode.a2"
						>
							<el-image class="w-full h-full" :src="data.qrcode.a2" />
						</div>
						<div
							class="border w-10 h-10 p-1 rounded-md cursor-pointer"
							:class="
								data.currentImg === data.qrcode.sp1 ? 'border-blue-400' : ''
							"
							@click="data.currentImg = data.qrcode.sp1"
						>
							<el-image class="w-full h-full" :src="data.qrcode.sp1" />
						</div>
						<div
							class="border w-10 h-10 p-1 rounded-md cursor-pointer"
							:class="
								data.currentImg === data.qrcode.aa1 ? 'border-blue-400' : ''
							"
							@click="data.currentImg = data.qrcode.aa1"
						>
							<el-image class="w-full h-full" :src="data.qrcode.aa1" />
						</div>
						<div
							class="border w-10 h-10 p-1 rounded-md cursor-pointer"
							:class="
								data.currentImg === data.qrcode.ab2 ? 'border-blue-400' : ''
							"
							@click="data.currentImg = data.qrcode.ab2"
						>
							<el-image class="w-full h-full" :src="data.qrcode.ab2" />
						</div>
					</div>
				</div>
				<div class="w-full">
					<el-form>
						<el-form-item>
							<el-input
								v-model="data.name"
								readonly
								:prefix-icon="DocumentTextIcon"
							></el-input>
						</el-form-item>
						<el-form-item>
							<el-input
								v-model="data.time"
								readonly
								:prefix-icon="CalendarIcon"
							></el-input>
						</el-form-item>
						<el-form-item>
							<el-input
								v-model="data.size"
								readonly
								:prefix-icon="CircleStackIcon"
							></el-input>
						</el-form-item>
						<el-form-item
							v-if="
								storageConfigStore.permission.pathLink &&
								(currentLinkDialogType === 'all' ||
									currentLinkDialogType === 'pathLink')
							"
						>
							<el-tooltip
								append-to=".student-cloud-file-download-link-body"
								popper-class="student-cloud-link-tips"
								placement="left"
								content="路径直链地址，包含文件完整路径."
							>
								<el-input
									v-model="data.pathLink"
									:prefix-icon="LinkIcon"
									@click="copyText(data.pathLink)"
								>
								</el-input>
							</el-tooltip>
						</el-form-item>
						<el-form-item
							v-if="
								storageConfigStore.permission.shortLink &&
								(currentLinkDialogType === 'all' ||
									currentLinkDialogType === 'shortLink')
							"
						>
							<el-tooltip
								append-to=".student-cloud-file-download-link-body"
								popper-class="student-cloud-link-tips"
								placement="left"
								content="缩短版直链地址，便于复制分发."
							>
								<el-input
									v-model="data.shortLink"
									:prefix-icon="LinkIcon"
									@click="copyText(data.shortLink)"
								>
								</el-input>
							</el-tooltip>
						</el-form-item>
					</el-form>
				</div>
			</el-row>

			<template #footer>
				<el-button
					v-show="selectFiles.length > 1"
					type="primary"
					:loading="exporting"
					:disabled="loading || !dataList.length"
					@click="exportExcel"
					>导出</el-button
				>
				<el-button type="info" @click="generateLinkResultDialogVisible = false"
					>关闭</el-button
				>
			</template>
		</el-dialog>
	</div>
</template>

<script setup>
import QrCodePreview from '~/components/common/QrCodePreview.vue'

import { currentPageHeight } from '~/utils'
import {
	CalendarIcon,
	CircleStackIcon,
	LinkIcon,
	DocumentTextIcon,
} from '@heroicons/vue/24/outline'

// 组件传参及回写
defineProps({
	rowData: {
		type: Object,
		default: undefined,
	},
})

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import useFileSelect from '~/composables/file/useFileSelect'
let { selectFiles } = useFileSelect()

import useFileLink from '~/composables/file/useFileLink'
let {
	generateLinkResultDialogVisible,
	generateLinkResultLoading: loading,
	copyText,
	data,
	dataList,
	generateALlLink,
	currentLinkDialogType,
} = useFileLink()

watch(
	() => generateLinkResultDialogVisible.value,
	(value) => {
		if (value) {
			if (selectFiles.value.length === 0) {
				ElMessage.warning('请至少选择一个文件')
			} else {
				if (!storageConfigStore.permission.link) {
					ElMessage.error('没有权限生成直链或短链')
					return
				}
				generateALlLink(selectFiles.value)
			}
		} else {
			dataList.value = []
		}
	}
)

const batchCopyLinkField = (filed) => {
	let links = []
	let fields = filed.split('.')
	dataList.value.forEach((row) => {
		let value = row
		fields.forEach((item) => {
			value = value[item]
		})
		links.push(value)
	})
	copyText(links.join('\n'))
}

import FileSaver from 'file-saver'

const exporting = ref(false)
const exportExcel = async () => {
	if (exporting.value || loading.value || !dataList.value.length) return
	exporting.value = true
	try {
		const { utils, write } = await import('xlsx')
		const columns = [{ key: 'name', label: '文件名' }]
		if (
			storageConfigStore.permission.pathLink &&
			['all', 'pathLink'].includes(currentLinkDialogType.value)
		) {
			columns.push({ key: 'pathLink', label: '直链' })
		}
		if (
			storageConfigStore.permission.shortLink &&
			['all', 'shortLink'].includes(currentLinkDialogType.value)
		) {
			columns.push({ key: 'shortLink', label: '短链' })
		}
		// Export the actual records, not the UI table DOM (which can be virtualized).
		const rows = [
			columns.map((column) => column.label),
			...dataList.value.map((item) =>
				columns.map((column) => String(item[column.key] ?? ''))
			),
		]
		const sheet = utils.aoa_to_sheet(rows)
		sheet['!cols'] = columns.map((column, index) => ({
			wch: Math.min(100, Math.max(30, ...rows.map((row) => row[index].length))),
		}))
		const workbook = utils.book_new()
		utils.book_append_sheet(workbook, sheet, '直链')
		const output = write(workbook, { bookType: 'xlsx', type: 'array' })
		const date = new Date()
		const name = `${date.getFullYear()}${String(date.getMonth() + 1).padStart(
			2,
			'0'
		)}${String(date.getDate()).padStart(2, '0')}`
		FileSaver.saveAs(
			new Blob([output], {
				type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
			}),
			`${name}学生云盘 直链导出.xlsx`
		)
	} catch (error) {
		console.warn('直链导出失败', error)
		ElMessage.error('导出失败，请检查网络后重试')
	} finally {
		exporting.value = false
	}
}

const handleCellClick = (row, column) => {
	copyText(row[column.property])
}
</script>

<style scoped lang="scss">
.student-cloud-file-download-link-body {
	:deep(.student-cloud-file-download-link-dialog-single) {
		@apply w-10/12 md:w-96;

		.el-form-item {
			margin-bottom: 8px;
		}
	}

	:deep(.student-cloud-file-download-link-dialog-multiple) {
		@apply w-10/12;
	}

	:deep(.student-cloud-link-tips) {
		@apply hidden md:block;
	}
}
</style>
