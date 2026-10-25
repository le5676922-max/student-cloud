<template>
	<section v-if="editorError" class="user-editor-unavailable" role="alert">
		<h2>无法编辑此账号</h2>
		<p>{{ editorError }}</p>
		<router-link to="/admin/user-list">返回空间人员，选择具体账号</router-link>
	</section>
	<p v-else-if="!editorReady" class="user-editor-loading" role="status">
		正在加载账号信息…
	</p>
	<el-form
		v-else
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
		<el-alert
			v-if="isGlobalAccount"
			title="全局管理员固定拥有最高管理权限，无需配置默认权限或存储授权。这里只修改账号资料和密码。"
			type="info"
			:closable="false"
			show-icon
		/>
		<el-alert
			v-else
			title="这里只管理账号资料与状态。成员的存储权限由所属工作空间管理员设置，全局管理员不设置成员权限。"
			type="info"
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
					<span v-if="isGlobalAccount">全局管理员</span>
					<span v-else>{{ roleLabel(data.role) }}</span>
				</template>
			</el-input>
		</el-form-item>

		<el-form-item label="用户昵称" prop="nickname">
			<el-input v-model="data.nickname"> </el-input>
		</el-form-item>

		<el-form-item label="用户角色">
			<el-select
				v-if="!isGlobalAccount && (data.id == null || data.id > 2)"
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

		<el-form-item v-if="isAdmin && !isGlobalAccount" label="所属工作空间">
			<el-select
				v-model="data.tenantId"
				class="w-full"
				:disabled="!!managerPreset || data.id != null"
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
			<div v-if="!hasSelectedWorkspace" class="el-form-item-tips">
				请选择账号所属的工作空间；创建后不通过此页面迁移空间。
			</div>
		</el-form-item>

		<el-form-item label="密码">
			<el-input
				v-model="data.password"
				autocomplete="off"
				type="password"
				show-password
			/>
			<div class="el-form-item-tips">编辑时不显示密码，不修改密码请留空</div>
		</el-form-item>

		<el-form-item v-if="canEditPermissions" label="新增存储源默认权限">
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
				仅影响当前账号今后新增存储源时的默认授权，不修改现有存储源，也不影响其他账号。已有存储源请在下方逐项设置；没有默认权限时，不会因新增存储源而自动获得使用权限。
			</div>
		</el-form-item>

		<el-form-item v-if="canEditPermissions" label="当前账号存储授权">
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
			<el-tag v-if="isGlobalAccount" type="info" effect="plain"
				>固定启用</el-tag
			>
			<el-switch v-else v-model="data.enable"></el-switch>
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
				:disabled="
					accessLoading ||
					!!presetError ||
					(!!managerPreset && !presetReady) ||
					(!isGlobalAccount && !hasSelectedWorkspace)
				"
				@click="saveAction"
				>保存</el-button
			>
		</el-form-item>
	</el-form>

	<el-dialog
		v-if="canEditPermissions"
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
				>使用该账号新增存储源默认权限</el-button
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
import { roleLabel, canAccessAdministration } from '~/utils/roles.mjs'
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
const editorReady = ref(false)
const editorError = ref('')
const accessLoading = ref(false)
const storageConfigStore = useStorageConfigStore()
const isAdmin = computed(() =>
	canAccessAdministration(storageConfigStore.loginInfo)
)
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
const isGlobalAccount = computed(
	() => data.value.id === 1 || data.value.role === 'ADMIN'
)
const hasSelectedWorkspace = computed(
	() => Number.isInteger(data.value.tenantId) && data.value.tenantId > 0
)
const permissionTargetSelected = computed(
	// Global account editing deliberately never loads or writes workspace grants.
	() => false
)
const canEditPermissions = computed(
	() =>
		editorReady.value && !editorError.value && permissionTargetSelected.value
)

onMounted(() => {
	if (!isAdmin.value) {
		editorError.value = '当前账号没有全局账号管理权限。'
		return
	}
	if (currentUserId === '0') {
		editorError.value =
			'全局新账号默认权限模板不再提供编辑。成员权限请由所属工作空间管理员设置。'
		return
	}
	if (
		currentUserId != null &&
		(typeof currentUserId !== 'string' ||
			!/^[1-9]\d{0,9}$/.test(currentUserId) ||
			Number(currentUserId) > 2147483647 ||
			Number(currentUserId) === 2)
	) {
		editorError.value =
			'此记录不是可编辑的用户账号，请返回空间人员选择具体账号。'
		return
	}
	init()
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
		!isAdmin.value ||
		!editorReady.value ||
		editorError.value ||
		accessLoading.value ||
		presetError.value ||
		(managerPreset && !presetReady.value)
	)
		return
	if (managerPreset) Object.assign(data.value, managerPreset)
	if (!isGlobalAccount.value && !hasSelectedWorkspace.value) {
		ElMessage.error('请先选择账号所属的工作空间。')
		return
	}
	// 检查 data.userStorageSourceList 中的 rootPath 不能为空，也必须以 / 开头，否则会提示第几个存储源，哪个存储源的根目录配置错误
	let errorIndex = !canEditPermissions.value
		? -1
		: data.value.userStorageSourceList.findIndex((item) => {
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
			// Only send fields that this account editor owns. Account identifiers,
			// membership status and legacy grant row identities are not editable.
			const payload = {
				id: data.value.id,
				username: data.value.username,
				nickname: data.value.nickname,
				password: data.value.password,
				role: isGlobalAccount.value ? 'ADMIN' : data.value.role,
				enable: isGlobalAccount.value ? true : data.value.enable,
			}
			if (!isGlobalAccount.value) {
				payload.tenantId = data.value.tenantId
			}
			saveOrUpdateUserReq(payload)
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
				.catch(() => ElMessage.error('保存失败，请检查账号信息后重试。'))
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
	let storageListVersion = 0
	const init = async () => {
		editorReady.value = false
		editorError.value = ''
		showSetPermissionDialog.value = false
		try {
			if (currentUserId) {
				const { data: account } = await getUserInfoReq(currentUserId)
				if (
					!account ||
					account.id !== Number(currentUserId) ||
					account.id <= 0 ||
					account.id === 2 ||
					!['ADMIN', 'USER', 'TENANT_ADMIN'].includes(account.role) ||
					(account.id === 1 && account.role !== 'ADMIN')
				)
					throw new Error('Invalid account')
				data.value = {
					...account,
					password: '',
					defaultPermissions: Array.isArray(account.defaultPermissions)
						? [...account.defaultPermissions]
						: [],
					userStorageSourceList: Array.isArray(account.userStorageSourceList)
						? account.userStorageSourceList.map((item) => ({
								...item,
								permissions: (item.permissions || []).filter(
									(permission) => permission !== 'available'
								),
						  }))
						: [],
				}
			}
			if (!isGlobalAccount.value) {
				tenantList.value = (await getTenantListReq()).data
				if (managerPreset) {
					presetReady.value = !!tenantList.value.find(
						(item) => item.id === managerPreset.tenantId
					)?.enable
					if (!presetReady.value)
						presetError.value =
							'指定工作空间不存在或已停用，请返回空间人员页面。'
				}
				if (permissionTargetSelected.value)
					await Promise.all([loadPermissionList(), completeStorageSourceList()])
			}
			editorReady.value = true
		} catch {
			editorError.value =
				'账号或授权信息加载失败，请返回空间人员重新选择，或刷新后重试。'
		}
	}

	const completeStorageSourceList = async () => {
		const version = ++storageListVersion
		const tenantId = data.value.tenantId
		if (!permissionTargetSelected.value) {
			if (!isGlobalAccount.value) data.value.userStorageSourceList = []
			accessLoading.value = false
			return
		}
		accessLoading.value = true
		try {
			const { data: sources } = await loadStorageListReq()
			if (
				version !== storageListVersion ||
				tenantId !== data.value.tenantId ||
				!permissionTargetSelected.value
			)
				return
			const available = sources.filter((source) => source.tenantId === tenantId)
			const existing = data.value.userStorageSourceList
			data.value.userStorageSourceList = available.map((source) => {
				const grant = existing.find(
					(item) => item.storageSourceId === source.id
				)
				return grant
					? { ...grant, storageSourceName: source.name }
					: {
							storageSourceId: source.id,
							storageSourceName: source.name,
							rootPath: '/',
							...managedWorkspaceGrantDefaults(
								currentUserId ? null : managerPreset,
								source
							),
							new: true,
					  }
			})
		} finally {
			if (version === storageListVersion) accessLoading.value = false
		}
	}

	return { init, completeStorageSourceList }
}

const handleTenantChange = async () => {
	data.value.userStorageSourceList = []
	showSetPermissionDialog.value = false
	presetError.value = ''
	try {
		await Promise.all([loadPermissionList(), completeStorageSourceList()])
	} catch {
		presetError.value = '当前空间的授权信息加载失败，请重新选择或刷新后重试。'
	}
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
		if (!permissionTargetSelected.value || permissionList.value.length)
			return Promise.resolve()
		return getPermissionListReq().then((res) => {
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
		if (!canEditPermissions.value) return
		currentOpenPermissions.value = [...data.value.defaultPermissions]
	}

	// 打开设置权限列表对话框，row 为要设置的存储源，如果 row 为空，则表示设置选中的存储源
	const openSetPermissionDialog = (row) => {
		if (!canEditPermissions.value || accessLoading.value) return
		showSetPermissionDialog.value = true
		currentOpenRow.value = row
		currentOpenPermissions.value = [...(row?.permissions || [])]
	}

	const enterSetPermissionDialog = () => {
		if (!canEditPermissions.value || accessLoading.value) {
			showSetPermissionDialog.value = false
			return
		}
		if (currentOpenRow.value) {
			currentOpenRow.value.permissions = [...currentOpenPermissions.value]
		} else {
			userStorageSourceTableRef.value.getSelectionRows().forEach((row) => {
				row.permissions = [...currentOpenPermissions.value]
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
		if (!canEditPermissions.value) return
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
.user-editor-unavailable {
	padding: 24px;
	border: 1px solid var(--cloud-line);
	border-radius: 16px;
	background: var(--cloud-soft);
	h2 {
		margin-bottom: 10px;
		font-size: 18px;
		font-weight: 600;
	}
	p {
		margin-bottom: 16px;
		color: var(--cloud-muted);
		line-height: 1.8;
	}
	a {
		color: var(--cloud-primary);
		text-decoration: underline;
		text-underline-offset: 3px;
	}
}
.user-editor-loading {
	padding: 24px;
	color: var(--cloud-muted);
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 新增用户
</route>
