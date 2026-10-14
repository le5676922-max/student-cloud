<template>
	<div>
		<el-dialog
			v-model="generateLinkDialogVisible"
			title="生成短链"
			top="20vh"
			class="generate-link-wrapper"
		>
			<el-form
				label-width="100px"
				class="mt-4"
				:model="generateLinkFormData"
				@submit.prevent
			>
				<el-form-item label="选中的文件">
					<el-tag type="success">{{ selectRows.length }} 个</el-tag>
				</el-form-item>
				<el-form-item label="短链有效期">
					<el-select
						v-model="generateLinkFormData.expireTime"
						placeholder="请选择短链有效期"
					>
						<el-option
							v-for="item in storageConfigStore.globalConfig.linkExpireTimes"
							:key="item.seconds"
							:label="
								(item.unit === 'forever' ? '' : item.value) +
								' ' +
								constant.timeUnits[item.unit]
							"
							:value="item.seconds"
						>
						</el-option>
					</el-select>
				</el-form-item>
			</el-form>
			<template #footer>
				<el-button @click="generateLinkDialogVisible = false">取 消</el-button>
				<el-button
					type="primary"
					:loading="generateLinkLoading"
					@click="submitGenerateLinkForm"
					>确 定</el-button
				>
			</template>
		</el-dialog>
	</div>
</template>

<script setup>
import useFileLink from '~/composables/file/useFileLink'
let {
	generateLinkDialogVisible,
	generateLinkFormData,
	generateLinkLoading,
	submitGenerateLinkForm,
} = useFileLink()

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import useFileSelect from '~/composables/file/useFileSelect'
let { selectRows } = useFileSelect()
generateLinkFormData.expireTime =
	storageConfigStore.globalConfig.linkExpireTimes[0].seconds
</script>

<style scoped lang="scss">
:deep(.generate-link-wrapper) {
	@apply w-1/4 min-w-[300px];
}
</style>
