<template>
	<main class="workspace-page" aria-labelledby="workspace-title">
		<header class="page-header">
			<div>
				<p class="eyebrow">工作空间管理</p>
				<h1 id="workspace-title">
					{{ summary?.tenantName || '我的工作空间' }}
				</h1>
				<p>管理成员、访问权限和空间邀请。</p>
			</div>
			<nav class="workspace-shortcuts" aria-label="我的工作空间快捷入口">
				<router-link to="/admin/storage-list">管理云盘</router-link>
				<router-link to="/courses">共享课程 / 邀请成员</router-link>
				<router-link to="/learning">
					<svg viewBox="0 0 24 24" aria-hidden="true">
						<path
							d="M12 5v15M12 5C9 3 5 3 3 4v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-2-1-6-1-9 1"
						/>
					</svg>
					学习资料
				</router-link>
				<router-link to="/agent-tasks">
					<svg viewBox="0 0 24 24" aria-hidden="true">
						<circle cx="12" cy="12" r="8" />
						<path d="M12 7v5l3 2" />
					</svg>
					自动化任务
				</router-link>
				<router-link to="/" class="file-entry">
					<svg viewBox="0 0 24 24" aria-hidden="true">
						<path d="M12 16V4m-4 4 4-4 4 4M4 15v5h16v-5" />
					</svg>
					我的文件
				</router-link>
			</nav>
		</header>
		<el-alert
			v-if="error"
			:title="error"
			type="error"
			:closable="false"
			show-icon
		/>
		<div class="stats">
			<div>
				<span>空间成员</span><strong>{{ summary?.memberCount ?? '—' }}</strong
				><small>本空间的普通用户</small>
			</div>
			<div>
				<span>已启用</span
				><strong>{{ summary?.enabledMemberCount ?? '—' }}</strong
				><small>可以正常访问空间</small>
			</div>
			<div>
				<span>本空间存储源</span><strong>{{ storageCountDisplay }}</strong
				><small>{{ storageCountHint }}</small>
			</div>
		</div>
		<el-alert
			v-if="
				summary &&
				summary.storageCountAvailable !== false &&
				summary.storageCount === 0
			"
			title="本空间暂未配置云盘。可在“管理云盘”添加第三方存储；使用服务器本地空间需提交申请。"
			type="info"
			:closable="false"
			show-icon
		/>
		<AdminFileBusinessPanel
			v-if="store.globalConfig?.accountModel === 'independent'"
		/>
		<details class="member-panel" open>
			<summary>成员管理 <span>查看账号状态，按成员分配权限</span></summary>
			<div class="member-toolbar">
				<form novalidate class="member-search" @submit.prevent="searchMembers">
					<el-input
						v-model="search"
						placeholder="搜索账号、邮箱或昵称"
						aria-label="查找工作空间成员"
						clearable
						maxlength="100"
						@clear="clearMemberSearch"
					/><el-button native-type="submit" :loading="loading">查询</el-button>
				</form>
				<div class="member-toolbar-actions">
					<el-button :disabled="loading" @click="loadMembers(members.page)"
						>刷新</el-button
					>
					<el-button type="primary" @click="createVisible = true"
						>新增成员</el-button
					>
				</div>
			</div>
			<el-alert
				v-if="memberError"
				:title="memberError"
				type="error"
				:closable="false"
				show-icon
			/>
			<el-table
				v-loading="loading"
				:data="members.items"
				row-key="id"
				max-height="480"
				empty-text="暂无普通用户"
			>
				<el-table-column label="成员" min-width="180"
					><template #default="scope"
						><div class="member-identity">
							<span class="member-avatar" aria-hidden="true">{{
								(scope.row.nickname || scope.row.username || '成').slice(0, 1)
							}}</span>
							<div>
								<strong>{{ scope.row.nickname || scope.row.username }}</strong
								><small
									v-if="
										scope.row.nickname &&
										scope.row.nickname !== scope.row.username
									"
									>{{ scope.row.username }}</small
								>
							</div>
						</div></template
					></el-table-column
				>
				<el-table-column label="账号 ID" min-width="190" show-overflow-tooltip
					><template #default="scope"
						><span class="account-id">{{
							scope.row.accountId || scope.row.username
						}}</span></template
					></el-table-column
				>
				<el-table-column label="邮箱" min-width="200" show-overflow-tooltip
					><template #default="scope"
						><span :class="{ 'muted-value': !scope.row.email }">{{
							scope.row.email || '未绑定邮箱'
						}}</span></template
					></el-table-column
				>
				<el-table-column label="状态" width="95"
					><template #default="scope"
						><el-tag :type="memberState(scope.row).type">{{
							memberState(scope.row).label
						}}</el-tag></template
					></el-table-column
				>
				<el-table-column label="操作" width="230" fixed="right"
					><template #default="scope"
						><el-button
							link
							type="primary"
							:disabled="!canManageMember(scope.row)"
							@click="openMemberPermissions(scope.row)"
							>设置权限</el-button
						><el-button
							link
							type="primary"
							:disabled="!canManageMember(scope.row)"
							@click="openPasswordReset(scope.row)"
							>重置密码</el-button
						><el-button
							link
							type="primary"
							:disabled="!canManageMember(scope.row)"
							@click="toggleMember(scope.row)"
							>{{
								memberState(scope.row).blockedAction ||
								(scope.row.enabled ? '停用账号' : '启用账号')
							}}</el-button
						></template
					></el-table-column
				>
			</el-table>
			<el-pagination
				class="pagination"
				layout="prev, pager, next, total"
				:page-size="20"
				:current-page="members.page"
				:total="members.total"
				:disabled="loading"
				@current-change="loadMembers"
			/>
		</details>
		<el-drawer
			:model-value="!!permissionMember"
			title="成员权限"
			size="min(640px, 100vw)"
			append-to-body
			destroy-on-close
			class="workspace-permission-drawer"
			:z-index="2400"
			:before-close="closePermissionDrawer"
			:close-on-click-modal="!permissionSaving"
			:close-on-press-escape="!permissionSaving"
			:show-close="!permissionSaving"
		>
			<WorkspaceMemberPermissions
				v-if="permissionMember"
				:key="permissionMember.id"
				:member="permissionMember"
				@close="closePermissionDrawer()"
				@saved="permissionsSaved"
				@busy="permissionSaving = $event"
			/>
		</el-drawer>
		<details class="registration-panel" @toggle="onRegistrationToggle">
			<summary>
				邀请与加入申请 <span>展开后查看邀请码与待审核成员</span>
			</summary>
			<WorkspaceRegistrationAdmin
				v-if="registrationLoaded"
				@members-changed="refreshMembership"
			/>
		</details>
		<details
			class="registration-panel"
			@toggle="cloudAccessLoaded ||= $event.currentTarget.open"
		>
			<summary>云盘使用申请 <span>审批成员个人目录与容量</span></summary>
			<WorkspaceCloudAccess v-if="cloudAccessLoaded" />
		</details>
		<p class="scope-note">
			这里只管理本空间成员与可委派的存储权限。站点和跨空间设置由全局管理员负责。
		</p>
		<el-dialog
			v-model="createVisible"
			title="新增普通用户"
			width="min(460px, 92vw)"
			destroy-on-close
		>
			<el-form label-position="top" @submit.prevent="createMember">
				<el-form-item label="用户名"
					><el-input v-model="draft.username" maxlength="50" autocomplete="off"
				/></el-form-item>
				<el-form-item label="昵称"
					><el-input v-model="draft.nickname" maxlength="100"
				/></el-form-item>
				<el-form-item label="初始密码"
					><el-input
						v-model="draft.password"
						type="password"
						show-password
						autocomplete="new-password"
				/></el-form-item>
				<p class="hint">
					密码至少 8 位。请通过安全方式告知成员，首次登录后由成员自行修改。
				</p>
			</el-form>
			<template #footer
				><el-button @click="createVisible = false">取消</el-button
				><el-button
					type="primary"
					:loading="saving"
					:disabled="
						draft.username.trim().length < 3 || draft.password.length < 8
					"
					@click="createMember"
					>创建</el-button
				></template
			>
		</el-dialog>
		<el-dialog
			:model-value="!!passwordMember"
			title="重置成员密码"
			width="min(460px, 92vw)"
			:close-on-click-modal="false"
			:close-on-press-escape="!passwordSaving"
			:show-close="!passwordSaving"
			:before-close="closePasswordReset"
			destroy-on-close
		>
			<form novalidate autocomplete="off" @submit.prevent="submitPasswordReset">
				<p>
					为
					{{ passwordMember?.nickname || passwordMember?.username }}
					设置新密码，原登录会话将失效。
				</p>
				<el-form label-position="top">
					<el-form-item label="新密码"
						><el-input
							v-model="passwordDraft.password"
							type="password"
							show-password
							maxlength="128"
							autocomplete="new-password"
							:disabled="passwordSaving"
					/></el-form-item>
					<el-form-item label="再次输入"
						><el-input
							v-model="passwordDraft.confirmPassword"
							type="password"
							show-password
							maxlength="128"
							autocomplete="new-password"
							:disabled="passwordSaving"
					/></el-form-item>
				</el-form>
				<p v-if="passwordError" role="alert">{{ passwordError }}</p>
			</form>
			<template #footer
				><el-button :disabled="passwordSaving" @click="closePasswordReset()"
					>取消</el-button
				>
				<el-button
					type="primary"
					:loading="passwordSaving"
					:disabled="!validPasswordDraft"
					@click="submitPasswordReset"
					>确认重置</el-button
				></template
			>
		</el-dialog>
	</main>
</template>

<script setup>
import {
	computed,
	defineAsyncComponent,
	onMounted,
	onBeforeUnmount,
	reactive,
	ref,
	watch,
} from 'vue'
import useStorageConfigStore from '~/stores/storage-config'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkspaceRegistrationAdmin from '~/components/auth/WorkspaceRegistrationAdmin.vue'
import WorkspaceMemberPermissions from '~/components/auth/WorkspaceMemberPermissions.vue'
import WorkspaceCloudAccess from '~/components/auth/WorkspaceCloudAccess.vue'
const AdminFileBusinessPanel = defineAsyncComponent({
	loader: () => import('~/components/admin/AdminFileBusinessPanel.vue'),
	suspensible: false,
})
import {
	getWorkspaceSummaryReq,
	getWorkspaceMembersReq,
	createWorkspaceMemberReq,
	setWorkspaceMemberEnabledReq,
	resetWorkspaceMemberPasswordReq,
} from '~/api/home/workspace-admin'
const summary = ref(null)
const storageCountDisplay = computed(() =>
	summary.value?.storageCountAvailable === false
		? '—'
		: summary.value?.storageCount ?? '—'
)
const storageCountHint = computed(() =>
	summary.value?.storageCountAvailable === false
		? '存储统计暂不可用，请稍后刷新'
		: '空间可用的文件存储'
)
const store = useStorageConfigStore()
const identity = computed(
	() =>
		`${store.loginInfo?.isLogin}:${store.loginInfo?.role}:${store.loginInfo?.tenantId}:${store.loginInfo?.username}`
)
const members = ref({ total: 0, page: 1, items: [] })
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const memberError = ref('')
const summaryRequests = createLatestRequest()
const memberRequests = createLatestRequest()
let disposed = false
const search = ref('')
const appliedSearch = ref('')
function searchMembers() {
	appliedSearch.value = search.value.trim()
	return loadMembers(1)
}
function clearMemberSearch() {
	search.value = ''
	return searchMembers()
}
const createVisible = ref(false)
const registrationLoaded = ref(false)
const cloudAccessLoaded = ref(false)
const permissionMember = ref(null)
const permissionSaving = ref(false)
const passwordMember = ref(null)
const passwordDraft = reactive({ password: '', confirmPassword: '' })
const passwordSaving = ref(false)
const passwordError = ref('')
let passwordController
const validPasswordDraft = computed(
	() =>
		passwordMember.value &&
		passwordDraft.password.length >= 8 &&
		passwordDraft.password.length <= 128 &&
		passwordDraft.password.trim().length > 0 &&
		passwordDraft.password === passwordDraft.confirmPassword
)
function clearPasswordDraft() {
	passwordDraft.password = ''
	passwordDraft.confirmPassword = ''
}
function openPasswordReset(member) {
	if (!canManageMember(member) || passwordSaving.value) return
	clearPasswordDraft()
	passwordError.value = ''
	passwordMember.value = member
}
function closePasswordReset(done) {
	if (passwordSaving.value) return
	passwordMember.value = null
	clearPasswordDraft()
	passwordError.value = ''
	if (typeof done === 'function') done()
}
async function submitPasswordReset() {
	if (
		!validPasswordDraft.value ||
		passwordSaving.value ||
		!canManageMember(passwordMember.value)
	)
		return
	passwordSaving.value = true
	passwordError.value = ''
	const target = passwordMember.value
	const controller = new AbortController()
	passwordController = controller
	try {
		await resetWorkspaceMemberPasswordReq(
			target.id,
			{ ...passwordDraft },
			{ signal: controller.signal }
		)
		if (disposed || passwordMember.value !== target) return
		passwordSaving.value = false
		closePasswordReset()
		ElMessage.success('密码已重置，成员需要重新登录')
	} catch {
		if (!disposed && passwordMember.value === target)
			passwordError.value =
				'重置结果未确认，请先让成员验证新密码，不要反复提交。'
	} finally {
		if (passwordController === controller) {
			clearPasswordDraft()
			passwordSaving.value = false
		}
	}
}
function canManageMember(member) {
	return (
		Number.isSafeInteger(member?.id) &&
		member.id > 2 &&
		!memberState(member).blockedAction
	)
}
function openMemberPermissions(member) {
	if (!canManageMember(member) || permissionSaving.value) return
	permissionMember.value = member
}
function closePermissionDrawer(done) {
	if (permissionSaving.value) return
	permissionMember.value = null
	if (typeof done === 'function') done()
}
function permissionsSaved() {
	permissionSaving.value = false
	permissionMember.value = null
	ElMessage.success('成员权限已更新')
}
function onRegistrationToggle(event) {
	if (event.currentTarget.open) registrationLoaded.value = true
}
const draft = reactive({ username: '', nickname: '', password: '' })
const refreshMembership = () => Promise.all([loadSummary(), loadMembers(1)])
function memberState(member) {
	if (member.membershipStatus === 'PENDING') {
		return { label: '待审核', type: 'warning', blockedAction: '需先审批' }
	}
	if (member.membershipStatus === 'REJECTED') {
		return { label: '未通过', type: 'danger', blockedAction: '申请未通过' }
	}
	return {
		label: member.enabled ? '启用' : '停用',
		type: member.enabled ? 'success' : 'info',
		blockedAction: '',
	}
}
async function loadSummary() {
	const request = summaryRequests.begin()
	error.value = ''
	try {
		const result = await getWorkspaceSummaryReq({
			signal: request.signal,
			timeout: 15000,
			showDefaultMsg: false,
		})
		if (!request.isCurrent() || disposed) return
		summary.value = result.data
	} catch (failure) {
		if (!request.isCurrent() || disposed) return
		error.value = failure?.response?.data?.msg || '工作空间信息加载失败'
	}
}
async function loadMembers(page = 1) {
	const request = memberRequests.begin()
	loading.value = true
	memberError.value = ''
	members.value = { ...members.value, page, items: [] }
	try {
		const result = await getWorkspaceMembersReq(page, appliedSearch.value, {
			signal: request.signal,
			timeout: 15000,
			showDefaultMsg: false,
		})
		if (!request.isCurrent() || disposed) return
		members.value = result.data
	} catch (failure) {
		if (!request.isCurrent() || disposed) return
		memberError.value = failure?.response?.data?.msg || '成员列表加载失败'
	} finally {
		if (request.isCurrent() && !disposed) loading.value = false
	}
}
async function createMember() {
	if (saving.value) return
	const actor = identity.value
	saving.value = true
	try {
		await createWorkspaceMemberReq({ ...draft })
		if (disposed || identity.value !== actor) return
		draft.username = ''
		draft.nickname = ''
		draft.password = ''
		createVisible.value = false
		ElMessage.success('成员已创建')
		search.value = ''
		appliedSearch.value = ''
		await Promise.all([loadSummary(), loadMembers(1)])
	} catch (failure) {
		if (!disposed && identity.value === actor)
			ElMessage.error(failure?.response?.data?.msg || '创建失败，请检查输入')
	} finally {
		if (identity.value === actor) saving.value = false
	}
}
async function toggleMember(member) {
	if (!canManageMember(member)) return
	const actor = identity.value
	try {
		await ElMessageBox.confirm(
			`确定${member.enabled ? '停用' : '启用'}“${member.username}”吗？`,
			'确认成员状态',
			{ type: 'warning' }
		)
	} catch {
		return
	}
	if (disposed || identity.value !== actor) return
	try {
		await setWorkspaceMemberEnabledReq(member.id, !member.enabled)
		if (disposed || identity.value !== actor) return
		await Promise.all([loadSummary(), loadMembers(members.value.page)])
	} catch (failure) {
		if (!disposed && identity.value === actor)
			ElMessage.error(failure?.response?.data?.msg || '状态修改失败')
	}
}
watch(createVisible, (visible) => {
	if (!visible) draft.password = ''
})
watch(
	identity,
	() => {
		summaryRequests.cancel()
		memberRequests.cancel()
		passwordController?.abort()
		passwordController = null
		clearPasswordDraft()
		passwordMember.value = null
		passwordSaving.value = false
		passwordError.value = ''
		permissionMember.value = null
		permissionSaving.value = false
		createVisible.value = false
		saving.value = false
		draft.username = draft.nickname = draft.password = ''
		summary.value = null
		members.value = { total: 0, page: 1, items: [] }
		loading.value = false
		search.value = appliedSearch.value = ''
		registrationLoaded.value = false
		cloudAccessLoaded.value = false
		error.value = memberError.value = ''
		if (
			store.loginInfo?.isLogin === true &&
			store.loginInfo?.role === 'TENANT_ADMIN'
		) {
			loadSummary()
			loadMembers()
		}
	},
	{ flush: 'sync' }
)
onMounted(() => {
	loadSummary()
	loadMembers()
})
onBeforeUnmount(() => {
	disposed = true
	summaryRequests.cancel()
	memberRequests.cancel()
	passwordController?.abort()
	clearPasswordDraft()
})
</script>

<style scoped>
.workspace-page {
	max-width: 1560px;
	width: calc(100% - 48px);
	height: calc(100dvh - 32px);
	margin: 16px auto;
	padding: 24px 24px 88px;
	overflow-y: auto;
	scrollbar-gutter: stable;
	border-radius: 24px;
	background: var(--cloud-canvas, #efeff0);
	color: var(--cloud-ink, #242428);
}
.page-header {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 20px;
}
.page-header a {
	color: white;
}
.page-header {
	padding: 18px 22px;
	border-radius: var(--cloud-radius, 20px);
	background: var(--cloud-panel, #dedede);
}
.file-entry {
	display: inline-flex;
	align-items: center;
	gap: 9px;
	padding: 12px 16px;
	border: 1px solid var(--cloud-primary, #262629);
	background: var(--cloud-primary, #262629);
	border-radius: 999px;
	font-size: 13px;
	font-weight: 600;
	white-space: nowrap;
}
.file-entry svg,
.workspace-shortcuts svg {
	width: 17px;
	height: 17px;
	fill: none;
	stroke: currentColor;
	stroke-width: 1.7;
	stroke-linecap: round;
	stroke-linejoin: round;
}
.file-entry:hover {
	background: var(--cloud-primary-dark, #121215);
	transform: translateY(-2px);
}
.workspace-shortcuts {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
	align-items: center;
	justify-content: flex-end;
}
.workspace-shortcuts a {
	display: flex;
	align-items: center;
	gap: 10px;
	padding: 11px 15px;
	border: 1px solid var(--cloud-line, #e1e6df);
	background: var(--cloud-surface, #fff);
	border-radius: 999px;
	color: var(--cloud-ink, #273936);
	font-size: 13px;
}
.workspace-shortcuts svg {
	width: 16px;
	height: 16px;
	color: var(--cloud-primary, #277566);
}
.workspace-shortcuts a:hover {
	border-color: #c5b7f7;
	background: var(--cloud-lilac, #f0ebff);
	transform: translateY(-2px);
}
.workspace-shortcuts .file-entry {
	color: var(--cloud-surface, #fff);
	border-color: var(--cloud-primary, #262629);
	background: var(--cloud-primary, #262629);
}
.workspace-shortcuts .file-entry svg {
	color: inherit;
}
.workspace-shortcuts .file-entry:hover {
	color: var(--cloud-surface, #fff);
	background: var(--cloud-primary-dark, #121215);
	border-color: var(--cloud-primary-dark, #121215);
}
.registration-panel {
	margin-top: 16px;
	padding: 22px 24px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: var(--cloud-radius, 20px);
	background: var(--cloud-surface, #fff);
}
summary {
	cursor: pointer;
	font-size: 16px;
	font-weight: 600;
}
summary::marker {
	color: var(--cloud-accent, #6d42f6);
}
summary span {
	font-size: 12px;
	font-weight: 400;
	color: var(--cloud-muted, #6d7974);
	margin-left: 12px;
}
details[open] > summary {
	margin-bottom: 16px;
}
summary:focus-visible,
.workspace-page a:focus-visible {
	outline: 2px solid var(--cloud-primary, #277566);
	outline-offset: 4px;
}
.page-header p,
.hint,
.scope-note {
	color: var(--cloud-muted, #6d7974);
	line-height: 1.7;
}
.page-header > div > p:last-child {
	font-size: 13px;
}
.eyebrow {
	color: var(--cloud-accent-ink, #5830bc) !important;
	font-size: 12px;
	letter-spacing: 0.12em;
}
h1 {
	font-size: 27px;
	font-weight: 650;
	margin: 3px 0 6px;
}
h2 {
	font-size: 18px;
	font-weight: 600;
}
.stats {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 12px;
	margin: 18px 0;
}
.stats > div {
	position: relative;
	padding: 14px 18px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 18px;
	background: var(--cloud-surface, #fff);
	display: flex;
	flex-direction: column;
	gap: 5px;
}
.stats span {
	font-size: 13px;
	color: var(--cloud-muted, #6d7974);
}
.stats strong {
	font-size: 26px;
	font-weight: 600;
	font-variant-numeric: tabular-nums;
}
.stats small {
	font-size: 11px;
	color: var(--cloud-muted, #6d7974);
}
.member-panel {
	padding: 20px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: var(--cloud-radius, 20px);
	background: var(--cloud-surface, #fff);
}
.member-toolbar {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 12px;
	margin: 16px 0;
}
.member-search {
	display: flex;
	gap: 10px;
	width: min(420px, 100%);
}
.member-toolbar-actions {
	display: flex;
	gap: 8px;
}
.member-toolbar-actions :deep(.el-button + .el-button) {
	margin-left: 0;
}
.member-identity {
	display: flex;
	align-items: center;
	gap: 10px;
	min-width: 0;
}
.member-avatar {
	display: grid;
	place-items: center;
	flex-shrink: 0;
	width: 32px;
	height: 32px;
	border-radius: 10px;
	color: var(--cloud-accent-ink);
	background: var(--cloud-lilac);
	font-weight: 600;
}
.member-identity > div {
	display: grid;
	gap: 2px;
	min-width: 0;
}
.member-identity strong {
	font-weight: 550;
	overflow: hidden;
	text-overflow: ellipsis;
}
.member-identity small,
.muted-value {
	color: var(--cloud-muted);
	font-size: 12px;
}
.account-id {
	font-variant-numeric: tabular-nums;
	font-size: 12px;
}
.member-panel :deep(.el-table th.el-table__cell) {
	background: var(--cloud-soft);
	font-size: 12px;
}
.member-panel :deep(.el-table td.el-table__cell) {
	padding: 12px 0;
}
:global(.workspace-permission-drawer .el-drawer__body) {
	display: flex;
	min-height: 0;
	padding: 0;
	overflow: hidden;
}
:global(.workspace-permission-drawer .el-drawer__header) {
	margin: 0;
	padding: 18px 22px;
	border-bottom: 1px solid var(--cloud-line, #dedede);
	color: var(--cloud-ink, #242428);
}
.pagination {
	margin-top: 18px;
	justify-content: flex-end;
	overflow-x: auto;
}
.hint,
.scope-note {
	font-size: 12px;
}
.scope-note {
	margin-top: 16px;
}
details[open] > :not(summary) {
	animation: workspace-disclose 180ms var(--cloud-ease, ease);
}
@keyframes workspace-disclose {
	from {
		opacity: 0;
		transform: translateY(-4px);
	}
	to {
		opacity: 1;
		transform: translateY(0);
	}
}
@media (max-width: 640px) {
	.workspace-page {
		width: calc(100% - 16px);
		height: calc(100dvh - 16px);
		margin: 8px auto;
		padding: 12px 12px 70px;
		border-radius: 18px;
	}
	.stats {
		gap: 8px;
	}
	.stats > div {
		padding: 14px 12px;
	}
	.stats > div::before,
	.stats small {
		display: none;
	}
	.stats strong {
		font-size: 25px;
	}
	.stats span {
		font-size: 11px;
	}
	.page-header {
		align-items: flex-start;
		flex-wrap: wrap;
		gap: 14px;
	}
	.page-header {
		flex-direction: column;
		padding: 22px 18px;
	}
	.workspace-shortcuts {
		gap: 7px;
		justify-content: flex-start;
	}
	.workspace-shortcuts a {
		padding: 9px 10px;
		gap: 6px;
		font-size: 12px;
	}
	.member-panel,
	.registration-panel {
		padding: 18px 14px;
	}
	.member-search {
		width: 100%;
	}
	.member-toolbar-actions {
		margin-left: auto;
	}
	summary span {
		display: block;
		margin: 6px 0 0 16px;
	}
	.member-search .el-input {
		min-width: 0;
	}
}
@media (prefers-reduced-motion: reduce) {
	.stats > div,
	.stats > div:hover,
	.file-entry,
	.file-entry:hover,
	.workspace-shortcuts a,
	.workspace-shortcuts a:hover {
		transition: none;
		transform: none;
	}
	details[open] > :not(summary) {
		animation: none;
	}
}
</style>
