<template>
	<div class="student-cloud-admin-permission-index">
		<div>
			<div class="flex justify-between">
				<h3 class="flex text-lg leading-6 font-medium text-gray-900">
					<router-link to="/admin/storage-list">
						<i-mdi-arrow-left class="my-auto h-full mr-2 cursor-pointer" />
					</router-link>
					<span class="line-through"
						>存储源权限控制（{{ storageItem?.name }}）</span
					>
					<span class="badge-blue">Pro</span>
					<span class="text-sm my-auto text-gray-400 ml-2"
						>这个页面的功能已经废弃，已自动将数据迁移到
						<router-link class="text-blue-400" to="/admin/user-list"
							>用户管理</router-link
						>
						中来配置权限。</span
					>
				</h3>
			</div>

			<el-table
				ref="tableRef"
				class-name="mt-8"
				:data="permissionConfigList"
				@cell-click="cellClickHandler"
			>
				<el-table-column
					align="center"
					show-overflow-tooltip
					prop="operatorName"
					label="操作"
				>
					<template #header>
						<span>操作</span>
						<el-popover placement="right" width="300" trigger="hover">
							<template #default>
								以下操作需要先在存储源打开<b>启用文件操作</b>功能才可用。
							</template>
							<template #reference>
								<QuestionMarkCircleIcon class="inline w-4 ml-1 -mt-0.5" />
							</template>
						</el-popover>
					</template>
					<template #default="scope">
						<span>{{ scope.row.operatorName }}</span>
						<el-popover placement="right" width="300" trigger="hover">
							<template #default>
								{{ scope.row.tips }}
							</template>
							<template #reference>
								<QuestionMarkCircleIcon
									v-show="scope.row.tips"
									class="inline w-4 ml-1 -mt-0.5"
								/>
							</template>
						</el-popover>
					</template>
				</el-table-column>

				<el-table-column align="center" prop="allowAdmin" label="管理员">
					<template #header>
						<div class="space-x-2">
							<span class="relative -top-[2px]">管理员</span>
							<el-checkbox
								v-model="allAdminOperator"
								disabled
								:indeterminate="indeterminateAdminOperator"
							></el-checkbox>
						</div>
					</template>
					<template #default="scope">
						<el-checkbox v-model="scope.row.allowAdmin" disabled></el-checkbox>
					</template>
				</el-table-column>
			</el-table>

			<div class="flex mt-5">
				<div class="flex-1"></div>
				<el-button
					type="primary"
					disabled
					:icon="CheckBadgeIcon"
					@click="savePermissionData"
					>保存</el-button
				>
			</div>
		</div>
	</div>
</template>

<script setup>
import {
	loadStorageItemReq,
	loadStoragePermissionReq,
	saveStoragePermissionReq,
} from '~/api/admin/admin-storage'

import { CheckBadgeIcon, QuestionMarkCircleIcon } from '@heroicons/vue/24/solid'
import { ElMessage } from 'element-plus'

let route = useRoute()
let router = useRouter()
let currentStorageId = route.params.storageId

const permissionConfigList = ref([])

const loadPermissionData = () => {
	loadStoragePermissionReq(currentStorageId).then((response) => {
		permissionConfigList.value = response.data
	})
}

const savePermissionData = () => {
	saveStoragePermissionReq(currentStorageId, permissionConfigList.value).then(
		(response) => {
			let data = response.data

			if (data.code !== constant.responseCode.SUCCESS) {
				if (data.code === constant.responseCode.BAD_REQUEST) {
					ElMessageBox.confirm(
						`保存成功，${data.msg}, 是否前往编辑存储源设置？`,
						'提示',
						{
							confirmButtonText: '确定',
							cancelButtonText: '取消',
							type: 'warning',
							callback: (action) => {
								if (action === 'confirm') {
									router.push('/admin/storage-edit/' + currentStorageId)
								}
							},
						}
					)
				} else {
					ElMessage({
						type: 'error',
						grouping: true,
						message: data.msg,
					})
				}
				return
			}

			ElMessageBox.confirm('保存成功, 是否返回存储源列表？', '提示', {
				confirmButtonText: '确定',
				cancelButtonText: '取消',
				type: 'success',
				callback: (action) => {
					if (action === 'confirm') {
						router.push('/admin/storage-list')
					}
				},
			})
		}
	)
}

onMounted(() => {
	loadPermissionData()
	loadStorageItem()
})

const storageItem = ref()
// 加载指定存储源的数据
const loadStorageItem = () => {
	loadStorageItemReq(currentStorageId).then((res) => {
		res.data.type = res.data.type.key
		storageItem.value = res.data
	})
}

const tableRef = ref()
const cellClickHandler = (row, column, cell, event) => {
	event.preventDefault()
	event.stopPropagation()
	if (column.property === 'allowAdmin') {
		row.allowAdmin = !row.allowAdmin
	}
}

// Keep computed getters pure: derive checked/partial states independently.
const permissionSelection = (field) => {
	const rows = computed(() => permissionConfigList.value || [])
	return {
		all: computed({
			get: () => rows.value.length > 0 && rows.value.every((row) => row[field]),
			set: (value) =>
				rows.value.forEach((row) => {
					row[field] = value
				}),
		}),
		partial: computed(
			() =>
				rows.value.some((row) => row[field]) &&
				!rows.value.every((row) => row[field])
		),
	}
}
const adminSelection = permissionSelection('allowAdmin')
const allAdminOperator = adminSelection.all
const indeterminateAdminOperator = adminSelection.partial
</script>

<style lang="scss" scoped>
.student-cloud-admin-permission-index {
	:deep(.el-table thead) {
		color: unset;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 操作权限
</route>
