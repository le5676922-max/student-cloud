<template>
	<el-form
		v-if="data && data.id !== 2"
		ref="adminUserFormRef"
		v-loading="saveLoading"
		:model="data"
		:label-width="globalConfigStore.adminForm.labelWidth"
		:label-position="globalConfigStore.adminForm.labelPosition"
		:size="globalConfigStore.adminForm.size"
		scroll-to-error
		status-icon
		class="z-admin-form"
		autocomplete="nope"
		:rules="rules"
	>
		<cancel
			:title="managerPreset ? '新增空间管理员' : '用户设置'"
			:to="
				managerPreset
					? '/admin/user-list?workspaceId=' + managerPreset.tenantId
					: '/admin/user-list'
			"
		/>
		<el-alert
			v-if="presetError"
			:title="presetError"
			type="error"
			:closable="false"
			show-icon
		/>

		<el-form-item
			:size="globalConfigStore.adminForm.size"
			label="用户名"
			prop="username"
		>
			<el-input v-model="data.username">
				<template #suffix>
					<span v-if="data.id === 0">新用户权限模板（不可登录）</span>
					<span v-else-if="data.id === 1">内置全局管理员</span>
					<span v-else>{{ roleLabel(data.role) }}</span>
				</template>
			</el-input>
		</el-form-item>

		<el-form-item label="用户昵称" prop="nickname">
			<el-input v-model="data.nickname"> </el-input>
		</el-form-item>

		<el-form-item label="用户角色">
			<el-select
				v-if="data.id == null || data.id > 2"
				v-model="data.role"
				class="w-full"
				:disabled="!!managerPreset"
			>
				<el-option label="普通用户" value="USER" />
				<el-option label="工作空间管理员" value="TENANT_ADMIN" />
			</el-select>
			<el-input v-else :model-value="roleLabel(data.role)" readonly />
			<div class="el-form-item-tips">
				全局管理员为内置账号。工作空间管理员只能管理所属空间的普通用户，不能进入全局后台。
			</div>
		</el-form-item>

		<el-form-item
			v-if="
				isAdmin && ['USER', 'TENANT_ADMIN'].includes(data.role) && data.id !== 0
			"
			label="所属工作空间"
		>
			<el-select
				v-model="data.tenantId"
				class="w-full"
				:disabled="!!managerPreset"
				@change="handleTenantChange"
			>
				<el-option
					v-for="tenant in tenantList"
					:key="tenant.id"
					:label="tenant.name"
					:value="tenant.id"
					:disabled="!tenant.enable"
				/>
			</el-select>
		</el-form-item>

		<el-form-item v-if="data.id !== 0" label="密码">
			<el-input
				v-model="data.password"
				autocomplete="off"
				type="password"
				show-password
			/>
			<div class="el-form-item-tips">编辑时不显示密码，不修改密码请留空</div>
		</el-form-item>

		<el-form-item label="用户默认权限">
			<div>
				<el-button-group type="primary" class="mb-2">
					<el-button
						@click="
							data.defaultPermissions = permissionList.map((item) => item.value)
						"
					>
						<i-mdi-check-circle class="mr-1" />
						全选
					</el-button>
					<el-button @click="data.defaultPermissions = []">
						<i-mdi-close-circle class="mr-1" />
						全不选
					</el-button>
					<el-button
						@click="
							data.defaultPermissions = permissionList
								.filter((item) => !data.defaultPermissions.includes(item.value))
								.map((item) => item.value)
						"
					>
						<i-mdi-shuffle-variant class="mr-1" />
						反选
					</el-button>
				</el-button-group>

				<el-checkbox-group
					v-model="data.defaultPermissions"
					class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5"
				>
					<el-checkbox
						v-for="item in permissionList"
						:key="item.value"
						:value="item.value"
						>{{ item.name }}
					</el-checkbox>
				</el-checkbox-group>
			</div>

			<div class="el-form-item-tips">
				这里控制新增存储源时，该用户默认拥有的权限（仅对新增的存储源有效，已存在的但未授予权限的存储源不会使用此配置；如没有任何默认权限，则添加存储源时不会授予使用）
			</div>
		</el-form-item>

		<el-form-item label="存储源授权">
			<el-button-group>
				<!--  为选中的存储源设置用户默认权限，按钮名叫 "设置选中存储源为默认权限" -->
				<el-button
					type="primary"
					size="small"
					class="mb-2"
					:disabled="getSelectedStorageSourceSize === 0"
					@click="openSetPermissionDialog(null)"
					>批量设置存储源列表
				</el-button>
			</el-button-group>
			<el-table
				ref="userStorageSourceTableRef"
				:size="globalConfigStore.adminTable.size"
				:data="data.userStorageSourceList"
				border
			>
				<el-table-column type="selection" width="50"></el-table-column>
				<el-table-column
					show-overflow-tooltip
					prop="storageSourceName"
					label="存储源名称"
				>
					<template #default="scope">
						<div class="flex justify-between">
							<span>{{ scope.row.storageSourceName }}</span>
							<el-tooltip
								v-if="scope.row.new"
								effect="dark"
								content="表示可能是新增的存储源，但该存储源还未授权."
								placement="left"
							>
								<el-tag type="success" size="small">new</el-tag>
							</el-tooltip>
						</div>
					</template>
				</el-table-column>
				<el-table-column show-overflow-tooltip prop="rootPath">
					<template #header>
						<span>授予根目录</span>
						<el-popover placement="right" width="300" trigger="hover">
							<template #default>
								表示该用户使用存储源下哪个目录作为根目录，注意要先创建好目录，否则使用时可能会出现问题，且不能填写
								URL 编码后的路径。
							</template>
							<template #reference>
								<QuestionMarkCircleIcon class="inline w-4 ml-1 -mt-0.5" />
							</template>
						</el-popover>
					</template>
					<template #default="scope">
						<el-input
							v-model="scope.row.rootPath"
							size="small"
							placeholder="请输入授权根目录"
						></el-input>
					</template>
				</el-table-column>
				<el-table-column width="120px" prop="enable" label="允许使用">
					<template #header>
						<div class="flex space-x-2">
							<span>允许使用</span>
							<el-tooltip effect="dark" content="批量选择" placement="top">
								<el-checkbox v-model="allowUseCommon" size="small" />
							</el-tooltip>
						</div>
					</template>
					<template #default="scope">
						<el-switch v-model="scope.row.enable" />
					</template>
				</el-table-column>
				<el-table-column width="120px" prop="readOnly" label="权限列表">
					<template #default="scope">
						<div class="space-x-2">
							<el-popover
								:disabled="getSize(scope.row.permissions) === 0"
								placement="left"
								:width="10"
							>
								<template #reference>
									<el-tag effect="dark"
										>{{ getSize(scope.row.permissions) }} 个</el-tag
									>
								</template>
								<div class="space-y-2">
									<div
										v-for="item in scope.row.permissions"
										:key="item.id"
										class="space-x-2"
									>
										<el-tag>{{ getPermissionName(item) }}</el-tag>
									</div>
								</div>
							</el-popover>
							<Cog6ToothIcon
								class="inline w-5 text-blue-400 cursor-pointer"
								@click="openSetPermissionDialog(scope.row)"
							/>
						</div>
					</template>
				</el-table-column>
			</el-table>
		</el-form-item>

		<el-form-item label="启用状态">
			<el-switch v-model="data.enable"></el-switch>
		</el-form-item>

		<el-form-item
			:label-width="globalConfigStore.adminForm.labelWidth"
			class="admin-from-footer"
		>
			<el-button
				class="ml-auto"
				type="primary"
				:icon="CheckBadgeIcon"
				:loading="saveLoading"
				:disabled="!!presetError || (!!managerPreset && !presetReady)"
				@click="saveAction"
				>保存</el-button
			>
		</el-form-item>
	</el-form>

	<el-dialog
		v-model="showSetPermissionDialog"
		append-to-body
		title="设置权限列表"
		top="5vh"
		:width="isMobile ? '95%' : '400px'"
	>
		<div class="mb-5">
			<el-button
				type="primary"
				size="small"
				@click="
					currentOpenPermissions = permissionList.map((item) => item.value)
				"
			>
				全选
			</el-button>
			<el-button
				type="primary"
				size="small"
				@click="currentOpenPermissions = []"
				>全不选</el-button
			>
			<el-button
				type="primary"
				size="small"
				@click="
					currentOpenPermissions = permissionList
						.filter((item) => !currentOpenPermissions.includes(item.value))
						.map((item) => item.value)
				"
			>
				反选
			</el-button>
			<el-button
				type="primary"
				size="small"
				@click="setFromUserDefaultPermission"
				>设置为用户默认权限</el-button
			>
		</div>

		<div class="student-cloud-user-permission-checkbox-body">
			<el-checkbox-group
				v-model="currentOpenPermissions"
				:size="globalConfigStore.adminForm.size"
			>
				<el-checkbox
					v-for="item in permissionList"
					:key="item.value"
					class="!block"
					:value="item.value"
				>
					<span>{{ item.name }}</span>
					<el-popover placement="right" width="300" trigger="hover">
						<template #default>
							{{ item.tips }}
						</template>
						<template #reference>
							<QuestionMarkCircleIcon
								v-show="item.tips"
								class="inline w-4 ml-1 -mt-0.5"
							/>
						</template>
					</el-popover>
				</el-checkbox>
			</el-checkbox-group>
		</div>

		<template #footer>
			<el-button size="default" @click="showSetPermissionDialog = false"
				>取消</el-button
			>
			<el-button
				type="primary"
				size="default"
				:icon="CheckBadgeIcon"
				@click="enterSetPermissionDialog"
				>确认</el-button
			>
		</template>
	</el-dialog>
</template>

<script setup>
import useGlobalConfigStore from '~/stores/global-config'
import { roleLabel } from '~/utils/roles.mjs'
import {
	workspaceManagerPreset,
	managedWorkspaceGrantDefaults,
} from '~/utils/admin-navigation.mjs'
const globalConfigStore = useGlobalConfigStore()

import {
	CheckBadgeIcon,
	QuestionMarkCircleIcon,
	Cog6ToothIcon,
} from '@heroicons/vue/24/solid'
import {
	checkUsernameDuplicateReq,
	getUserInfoReq,
	saveOrUpdateUserReq,
} from '~/api/admin/admin-user'
import { loadStorageListReq } from '~/api/admin/admin-storage'
import { getPermissionListReq } from '~/api/admin/admin-permission'
import { getTenantListReq } from '~/api/admin/admin-tenant'
import { getSize, isMobile } from '~/utils'
import useStorageConfigStore from '~/stores/storage-config'

let route = useRoute()
let router = useRouter()
let currentUserId = route.params.userId
const managerPreset = workspaceManagerPreset(route.query, currentUserId)
const presetReady = ref(false)
const presetError = ref('')
const storageConfigStore = useStorageConfigStore()
const isAdmin = computed(() => storageConfigStore.loginInfo.isAdmin === true)
const tenantList = ref([])

const data = ref({
	id: null,
	username: '',
	nickname: '',
	enable: true,
	createTime: '',
	defaultPermissions: [],
	userStorageSourceList: [],
	role: managerPreset?.role || 'USER',
	tenantId: managerPreset?.tenantId || null,
})

onMounted(() => {
	const tenantPromise = isAdmin.value
		? getTenantListReq().then((res) => {
				tenantList.value = res.data
				if (managerPreset) {
					const tenant = tenantList.value.find(
						(item) => item.id === managerPreset.tenantId
					)
					presetReady.value = !!tenant?.enable
					if (!presetReady.value)
						presetError.value =
							'指定工作空间不存在或已停用，请返回空间人员页面。'
				}
				if (!currentUserId && !data.value.tenantId) {
					data.value.tenantId =
						tenantList.value.find((item) => item.enable)?.id || null
				}
		  })
		: Promise.resolve()
	tenantPromise.then(init).catch(() => {
		presetError.value = '工作空间信息加载失败，请刷新后重试'
	})
	loadPermissionList()
})

const { init, completeStorageSourceList } = useInitLoadData()
const {
	loadPermissionList,
	permissionList,
	getPermissionName,
	showSetPermissionDialog,
	currentOpenPermissions,
	openSetPermissionDialog,
	enterSetPermissionDialog,
	setFromUserDefaultPermission,
} = usePermissionData()

const saveLoading = ref(false)
const adminUserFormRef = ref(null)

const saveAction = () => {
	if (
		saveLoading.value ||
		presetError.value ||
		(managerPreset && !presetReady.value)
	)
		return
	if (managerPreset) Object.assign(data.value, managerPreset)
	// 检查 data.userStorageSourceList 中的 rootPath 不能为空，也必须以 / 开头，否则会提示第几个存储源，哪个存储源的根目录配置错误
	let errorIndex = data.value.userStorageSourceList.findIndex((item) => {
		return (
			item.rootPath === undefined ||
			item.rootPath === null ||
			item.rootPath === '' ||
			!item.rootPath.startsWith('/')
		)
	})
	if (errorIndex !== -1) {
		ElMessage.error(
			`第 ${
				errorIndex + 1
			} 个存储源的根目录配置错误，请检查。（不能为空，且必须以 / 开头）`
		)
		return
	}

	saveLoading.value = true
	adminUserFormRef.value.validate((valid) => {
		if (valid) {
			saveOrUpdateUserReq(data.value)
				.then((res) => {
					ElMessage.success('保存成功')
					let userId = res.data.id
					if (userId != currentUserId) {
						// 如果是新增的话，跳转到编辑页面
						router.push('/admin/user-edit/' + userId)
					} else {
						init()
					}
				})
				.finally(() => {
					saveLoading.value = false
				})
		} else {
			saveLoading.value = false
		}
	})
}

let rules = ref({
	username: [
		{ required: true, message: '请输入用户名' },
		{ min: 1, max: 255, message: '长度应在 1 到 255 个字符', trigger: 'blur' },
		{
			validator: (rule, value, callback) => {
				if (value === undefined || value === null || value === '') {
					callback()
					return
				}
				let params = {
					id: data.value.id,
					username: value,
				}
				checkUsernameDuplicateReq(params).then((res) => {
					if (res.data) {
						callback(new Error('该用户名已存在，请修改。'))
					} else {
						callback()
					}
				})
			},
		},
	],
	password: [
		{ min: 6, max: 255, message: '长度在 6 到 255 个字符', trigger: 'blur' },
	],
})

function useInitLoadData() {
	const init = () => {
		if (currentUserId) {
			getUserInfoReq(currentUserId).then((res) => {
				// // 判断 defaultPermissions 是否为空，如果为空，则设置为 [].
				if (!res.data.defaultPermissions) {
					res.data.defaultPermissions = []
				}
				if (res.data.userStorageSourceList) {
					res.data.userStorageSourceList.forEach((item) => {
						// 如果 item.permissions 不为空且包含 available，则从中删除 available
						if (item.permissions && item.permissions.includes('available')) {
							item.permissions.splice(item.permissions.indexOf('available'), 1)
						}
					})
				}
				data.value = res.data
				completeStorageSourceList()
			})
		} else {
			completeStorageSourceList()
		}
	}

	const completeStorageSourceList = () => {
		loadStorageListReq().then((res) => {
			let storageSourceList =
				isAdmin.value && data.value.tenantId
					? res.data.filter((item) => item.tenantId === data.value.tenantId)
					: res.data
			let userStorageSourceList = data.value.userStorageSourceList
			// 判断当前已经授权的存储源和所有存储源的差集，如果差集不为空，则说明有存储源被删除了，需要删除掉，否则会出现授权了但是没有存储源的情况
			// 还要判断当前已经授权的存储源和所有存储源的交集，如果交集不为空，则说明有存储源被添加了，需要添加进去
			let storageSourceIdList = storageSourceList.map((item) => item.id)
			let userStorageSourceIdList = userStorageSourceList.map(
				(item) => item.storageSourceId
			)
			let deleteStorageSourceIdList = userStorageSourceIdList.filter(
				(item) => !storageSourceIdList.includes(item)
			)
			let addStorageSourceIdList = storageSourceIdList.filter(
				(item) => !userStorageSourceIdList.includes(item)
			)
			// 删除掉
			deleteStorageSourceIdList.forEach((storageSourceId) => {
				let index = userStorageSourceList.findIndex(
					(item) => item.storageSourceId === storageSourceId
				)
				console.log(
					'检测到存储源被删除，删除掉：',
					userStorageSourceList[index].storageSourceName
				)
				userStorageSourceList.splice(index, 1)
			})
			// 添加进去
			addStorageSourceIdList.forEach((storageSourceId) => {
				let storageSource = storageSourceList.find(
					(item) => item.id === storageSourceId
				)
				userStorageSourceList.push({
					storageSourceId: storageSource.id,
					storageSourceName: storageSource.name,
					rootPath: '/',
					...managedWorkspaceGrantDefaults(
						currentUserId ? null : managerPreset,
						storageSource
					),
					new: true,
				})
				console.log('检测到存储源被添加，添加进去：', storageSource.name)
			})
		})
	}

	return {
		init,
		completeStorageSourceList,
	}
}

const handleTenantChange = () => {
	data.value.userStorageSourceList = []
	completeStorageSourceList()
}

const userStorageSourceTableRef = ref(null)
const getSelectedStorageSourceSize = computed(() => {
	return userStorageSourceTableRef.value
		? userStorageSourceTableRef.value.getSelectionRows().length
		: 0
})

function usePermissionData() {
	const showSetPermissionDialog = ref(false)
	const permissionList = ref([])
	const permissionMap = ref({})

	const loadPermissionList = () => {
		getPermissionListReq().then((res) => {
			permissionList.value = res.data
			permissionMap.value = res.data.reduce((map, item) => {
				map[item.value] = item
				return map
			}, {})
		})
	}

	const getPermissionName = (value) => {
		let permission = permissionMap.value[value]
		return permission ? permission.name : value
	}

	const currentOpenPermissions = ref([])
	const currentOpenRow = ref(null)

	const setFromUserDefaultPermission = () => {
		currentOpenPermissions.value = data.value.defaultPermissions
	}

	// 打开设置权限列表对话框，row 为要设置的存储源，如果 row 为空，则表示设置选中的存储源
	const openSetPermissionDialog = (row) => {
		showSetPermissionDialog.value = true
		currentOpenRow.value = row
		currentOpenPermissions.value = row ? row.permissions : []
	}

	const enterSetPermissionDialog = () => {
		if (currentOpenRow.value) {
			currentOpenRow.value.permissions = currentOpenPermissions.value
		} else {
			userStorageSourceTableRef.value.getSelectionRows().forEach((row) => {
				row.permissions = currentOpenPermissions.value
			})
		}
		showSetPermissionDialog.value = false
	}

	return {
		loadPermissionList,
		permissionList,
		getPermissionName,
		showSetPermissionDialog,
		openSetPermissionDialog,
		currentOpenPermissions,
		enterSetPermissionDialog,
		setFromUserDefaultPermission,
	}
}

const allowUseCommon = computed({
	get: () => {
		return data.value.userStorageSourceList.every((item) => item.enable)
	},
	set: (value) => {
		data.value.userStorageSourceList.forEach((item) => {
			item.enable = value
		})
	},
})
</script>

<style lang="scss" scoped>
.el-form {
	// 防止谷歌浏览器记住密码后样式错乱
	:deep(.el-input input) {
		box-shadow: 0 0 0 1000px white inset;
	}
}

.student-cloud-user-permission-checkbox-body {
	@apply overflow-y-auto;
	height: 65vh;

	:deep(.el-checkbox__label) {
		@apply align-top;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 新增用户
</route>
