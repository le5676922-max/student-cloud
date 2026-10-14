<template>
	<el-dialog v-model="dialogVisible" append-to-body title="复制用户及权限">
		<el-form
			ref="userCopyFormRef"
			:model="formData"
			:rules="rules"
			:label-width="globalConfigStore.adminForm.labelWidth"
			:label-position="globalConfigStore.adminForm.labelPosition"
			:size="globalConfigStore.adminForm.size"
			status-icon
			class="z-admin-form"
			@submit.prevent
		>
			<el-form-item label="新用户名称" prop="toUsername">
				<el-input
					v-model="formData.toUsername"
					placeholder="请输入新用户名称"
				/>
			</el-form-item>

			<el-form-item label="新用户昵称" prop="toNickname">
				<el-input
					v-model="formData.toNickname"
					placeholder="请输入新用户昵称"
				/>
			</el-form-item>

			<el-form-item label="新用户密码">
				<el-input
					v-model="formData.toPassword"
					placeholder="请输入新用户密码"
				/>
				<div class="text-xs text-gray-400">如不填写，则从源用户复制密码</div>
			</el-form-item>
		</el-form>
		<template #footer>
			<el-button @click="dialogVisible = false">取 消</el-button>
			<el-button type="primary" :loading="saveLoading" @click="submitForm(emit)"
				>确 定</el-button
			>
		</template>
	</el-dialog>
</template>

<script setup>
import useUserCopy from '~/composables/admin/user/user-copy'
const {
	dialogVisible,
	saveLoading,
	formData,
	rules,
	userCopyFormRef,
	submitForm,
} = useUserCopy()

const emit = defineEmits(['close'])

import useGlobalConfigStore from '~/stores/global-config'
const globalConfigStore = useGlobalConfigStore()
</script>

<style scoped>
.el-dialog {
	@apply w-[80%] sm:w-[60%] md:w-[40%] lg:w-[30%] xl:w-[20%];
}
</style>
