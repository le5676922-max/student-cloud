<template>
	<section class="workspace-management">
		<admin-form-header hide-bottom-border title="工作空间管理" />
		<p class="management-hint">
			管理空间名称和使用状态。停用不会删除文件，但该空间的管理员与成员将无法继续使用。
		</p>
		<p v-if="appliedQuery.workspaceId" class="management-hint">
			已定位空间 ID：{{
				appliedQuery.workspaceId
			}}。点击查询可重新搜索，点击重置查看全部。
		</p>
		<el-form
			:model="filters"
			inline
			class="workspace-filters"
			@submit.prevent="search"
		>
			<el-form-item label="名称 / ID">
				<el-input
					v-model="filters.keyword"
					placeholder="搜索空间名称或 ID"
					maxlength="100"
					clearable
				/>
			</el-form-item>
			<el-form-item label="空间状态">
				<el-select
					v-model="filters.enabled"
					class="status-select"
					aria-label="空间状态筛选"
				>
					<el-option label="全部" value="" />
					<el-option label="启用" value="true" />
					<el-option label="停用" value="false" />
				</el-select>
			</el-form-item>
			<el-form-item>
				<el-button type="primary" native-type="submit" :loading="loading"
					>查询</el-button
				>
				<el-button :disabled="loading" @click="resetFilters">重置</el-button>
			</el-form-item>
			<el-form-item class="create-action">
				<el-button type="primary" :icon="PlusIcon" @click="openCreateDialog"
					>新建工作空间</el-button
				>
			</el-form-item>
		</el-form>
		<el-alert
			v-if="error"
			:title="error"
			type="error"
			:closable="false"
			show-icon
		/>
		<el-table
			v-loading="loading"
			border
			:data="tenantList"
			:empty-text="error ? '数据加载失败，请重试' : '没有匹配的工作空间'"
		>
			<el-table-column width="80" prop="id" label="ID" />
			<el-table-column
				min-width="170"
				prop="name"
				label="工作空间名称"
				show-overflow-tooltip
			/>
			<el-table-column min-width="180" label="负责人账号 ID">
				<template #default="{ row }">{{
					row.ownerAccountId || '暂未分配'
				}}</template>
			</el-table-column>
			<el-table-column width="90" prop="members" label="成员数" />
			<el-table-column width="100" prop="workspaceAdmins" label="管理员数" />
			<el-table-column width="90" prop="pendingMembers" label="待加入" />
			<el-table-column width="90" prop="storageSources" label="存储源" />
			<el-table-column width="85" label="状态">
				<template #default="{ row }"
					><el-tag :type="row.enabled ? 'success' : 'info'">{{
						row.enabled ? '启用' : '停用'
					}}</el-tag></template
				>
			</el-table-column>
			<el-table-column width="205" label="操作" fixed="right">
				<template #default="{ row }"
					><el-button
						link
						type="primary"
						:aria-label="'编辑空间 ' + row.name"
						@click="openEditDialog(row)"
						>编辑</el-button
					><el-button
						link
						type="primary"
						:disabled="!row.enabled || provisioningId !== null"
						:loading="provisioningId === row.id"
						:aria-label="'开通共享云盘 ' + row.name"
						@click="provisionStorage(row)"
						>开通共享云盘</el-button
					></template
				>
			</el-table-column>
		</el-table>
		<el-pagination
			v-if="!error"
			v-model:current-page="page"
			v-model:page-size="pageSize"
			class="workspace-pagination"
			:page-sizes="[10, 20, 50]"
			:total="total"
			:disabled="loading"
			layout="total, sizes, prev, pager, next"
			background
			@current-change="loadData"
			@size-change="changePageSize"
		/>
		<el-dialog
			v-model="dialogVisible"
			:title="form.id ? '编辑工作空间' : '新建工作空间'"
			width="min(440px, 92vw)"
			:close-on-click-modal="!saving"
			:close-on-press-escape="!saving"
			:show-close="!saving"
			@closed="formRef?.clearValidate()"
		>
			<el-form
				ref="formRef"
				:model="form"
				:rules="rules"
				label-width="100px"
				:disabled="saving"
				@submit.prevent="save"
			>
				<el-form-item label="空间名称" prop="name"
					><el-input v-model.trim="form.name" maxlength="80"
				/></el-form-item>
				<el-form-item label="启用状态"
					><el-switch v-model="form.enable"
				/></el-form-item>
			</el-form>
			<template #footer
				><el-button :disabled="saving" @click="dialogVisible = false"
					>取消</el-button
				><el-button type="primary" :loading="saving" @click="save"
					>保存</el-button
				></template
			>
		</el-dialog>
	</section>
</template>

<script setup>
import { PlusIcon } from '@heroicons/vue/24/outline'
import {
	getGlobalWorkspacesReq,
	provisionWorkspaceStorageReq,
} from '~/api/admin/admin-overview'
import { saveTenantReq } from '~/api/admin/admin-tenant'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { normalizeOverviewWorkspaceQuery } from '~/utils/admin-overview.mjs'

const route = useRoute()
const filters = reactive({ keyword: '', enabled: '' })
const appliedQuery = ref(normalizeOverviewWorkspaceQuery())
const tenantList = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const provisioningId = ref(null)
const dialogVisible = ref(false)
const formRef = ref()
const form = reactive({ id: null, name: '', enable: true })
const requests = createLatestRequest()
let previouslyEnabled = true
let disposed = false
const rules = {
	name: [
		{
			required: true,
			whitespace: true,
			message: '请输入工作空间名称',
			trigger: 'blur',
		},
		{ max: 80, message: '名称不能超过 80 个字符', trigger: 'blur' },
	],
}

async function loadData() {
	const request = requests.begin()
	loading.value = true
	error.value = ''
	tenantList.value = []
	// Keep the previous total while loading so pagination does not reset the requested page.
	try {
		const result = await getGlobalWorkspacesReq(
			normalizeOverviewWorkspaceQuery({
				...appliedQuery.value,
				page: page.value,
				pageSize: pageSize.value,
			}),
			{ signal: request.signal, timeout: 15000, showDefaultMsg: false }
		)
		if (!request.isCurrent() || disposed) return
		tenantList.value = result.data.records
		total.value = result.data.total
	} catch (failure) {
		if (!request.isCurrent() || disposed) return
		error.value =
			failure?.data?.msg ||
			failure?.response?.data?.msg ||
			'工作空间加载失败，请重新查询'
	} finally {
		if (request.isCurrent() && !disposed) loading.value = false
	}
}

function search() {
	appliedQuery.value = normalizeOverviewWorkspaceQuery(filters)
	page.value = 1
	loadData()
}
function resetFilters() {
	Object.assign(filters, { keyword: '', enabled: '' })
	search()
}
function changePageSize() {
	page.value = 1
	loadData()
}
function openCreateDialog() {
	Object.assign(form, { id: null, name: '', enable: true })
	previouslyEnabled = true
	dialogVisible.value = true
}
function openEditDialog(row) {
	Object.assign(form, { id: row.id, name: row.name, enable: row.enabled })
	previouslyEnabled = row.enabled
	dialogVisible.value = true
}

async function save() {
	if (saving.value) return
	saving.value = true
	try {
		if (!(await formRef.value.validate().catch(() => false))) return
		if (form.id && previouslyEnabled && !form.enable) {
			await ElMessageBox.confirm(
				'停用“' +
					form.name +
					'”后，该空间管理员和成员将无法继续使用。文件不会删除，是否继续？',
				'确认停用空间',
				{
					type: 'warning',
					confirmButtonText: '确认停用',
					cancelButtonText: '取消',
				}
			)
		}
		await saveTenantReq({ ...form, name: form.name.trim() })
		if (disposed) return
		ElMessage.success('保存成功')
		dialogVisible.value = false
		await loadData()
	} catch (failure) {
		if (failure === 'cancel' || failure === 'close' || disposed) return
		// The shared request interceptor displays save failures; leave the form open for correction.
	} finally {
		if (!disposed) saving.value = false
	}
}

async function provisionStorage(row) {
	if (!row.enabled || provisioningId.value !== null) return
	try {
		await ElMessageBox.confirm(
			'为“' +
				row.name +
				'”开通隔离的空间共享云盘。该空间管理员与已加入成员可按授权使用；不会开放其他空间的文件。已有共享云盘不会重复创建或重置授权。',
			'开通共享云盘',
			{ confirmButtonText: '确认开通', cancelButtonText: '取消', type: 'info' }
		)
	} catch {
		return
	}
	if (disposed) return
	provisioningId.value = row.id
	try {
		const result = await provisionWorkspaceStorageReq(row.id, {
			timeout: 30000,
		})
		if (disposed) return
		ElMessage.success(
			result.data.created
				? '空间共享云盘已开通'
				: '共享云盘已存在，原有授权保持不变'
		)
		await loadData()
	} catch {
		/* The shared interceptor displays the server error. */
	} finally {
		if (!disposed) provisioningId.value = null
	}
}

watch(
	() => [route.query.keyword, route.query.workspaceId],
	([keyword, workspaceId]) => {
		filters.keyword = typeof keyword === 'string' ? keyword.slice(0, 100) : ''
		filters.enabled = ''
		appliedQuery.value = normalizeOverviewWorkspaceQuery({
			...filters,
			workspaceId,
		})
		page.value = 1
		loadData()
	},
	{ immediate: true }
)
onBeforeUnmount(() => {
	disposed = true
	requests.cancel()
})
</script>

<style scoped>
.workspace-management {
	padding-bottom: 30px;
}
.management-hint {
	color: #64748b;
	margin: 0 0 20px;
	font-size: 13px;
}
.workspace-filters {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
}
.status-select {
	width: 110px;
}
.create-action {
	margin-left: auto;
}
.workspace-pagination {
	margin-top: 18px;
	overflow-x: auto;
	padding-bottom: 4px;
}
@media (max-width: 640px) {
	.create-action {
		margin-left: 0;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 工作空间管理
</route>
