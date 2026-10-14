<template>
	<main class="workspace-page">
		<header class="page-header">
			<div>
				<p class="eyebrow">工作空间管理</p>
				<h1>{{ summary?.tenantName || '我的工作空间' }}</h1>
				<p>成员、邀请与共享资料，都在这里管理。</p>
			</div>
			<router-link to="/" class="file-entry">
				<svg viewBox="0 0 24 24" aria-hidden="true">
					<path d="M12 16V4m-4 4 4-4 4 4M4 15v5h16v-5" />
				</svg>
				我的文件 / 上传文件
			</router-link>
		</header>
		<nav class="workspace-shortcuts" aria-label="我的工作空间快捷入口">
			<router-link to="/">
				<svg viewBox="0 0 24 24" aria-hidden="true">
					<path d="M3 6h6l2 2h10v12H3z" />
				</svg>
				云盘文件
			</router-link>
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
		</nav>
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
				<span>本空间存储源</span
				><strong>{{ summary?.storageCount ?? '—' }}</strong
				><small>空间可用的文件存储</small>
			</div>
		</div>
		<el-alert
			v-if="summary && summary.storageCount === 0"
			title="本空间暂未配置存储源，请联系全局管理员开通共享云盘。开通后可在“我的文件”上传和管理文件。"
			type="info"
			:closable="false"
			show-icon
		/>
		<details class="member-panel" open>
			<summary>成员管理 <span>账号与访问状态</span></summary>
			<div class="panel-heading">
				<div>
					<h2>成员</h2>
					<p>
						新成员继承你当前在本空间的存储源授权；创建后可由全局管理员调整。
					</p>
				</div>
				<el-button type="primary" @click="createVisible = true"
					>新增成员</el-button
				>
			</div>
			<div class="member-search">
				<el-input
					v-model="search"
					placeholder="按账号 ID、邮箱、原有账号或昵称查找"
					aria-label="查找工作空间成员"
					clearable
					maxlength="100"
					@keyup.enter="loadMembers(1)"
				/><el-button :loading="loading" @click="loadMembers(1)">查询</el-button>
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
				empty-text="暂无普通用户"
			>
				<el-table-column prop="accountId" label="账号 ID" min-width="190" />
				<el-table-column prop="email" label="邮箱" min-width="200" />
				<el-table-column prop="username" label="用户名" min-width="160" />
				<el-table-column prop="nickname" label="昵称" min-width="150" />
				<el-table-column label="状态" width="95"
					><template #default="scope"
						><el-tag :type="memberState(scope.row).type">{{
							memberState(scope.row).label
						}}</el-tag></template
					></el-table-column
				>
				<el-table-column label="操作" width="125"
					><template #default="scope"
						><el-button
							link
							type="primary"
							:disabled="!!memberState(scope.row).blockedAction"
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
		<details class="registration-panel" @toggle="onRegistrationToggle">
			<summary>
				邀请与加入申请 <span>展开后查看邀请码与待审核成员</span>
			</summary>
			<WorkspaceRegistrationAdmin
				v-if="registrationLoaded"
				@members-changed="refreshMembership"
			/>
		</details>
		<p class="scope-note">
			这里仅管理本空间成员。存储授权与全局设置由全局管理员负责。
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
	</main>
</template>

<script setup>
import { onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkspaceRegistrationAdmin from '~/components/auth/WorkspaceRegistrationAdmin.vue'
import {
	getWorkspaceSummaryReq,
	getWorkspaceMembersReq,
	createWorkspaceMemberReq,
	setWorkspaceMemberEnabledReq,
} from '~/api/home/workspace-admin'
const summary = ref(null)
const members = ref({ total: 0, page: 1, items: [] })
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const memberError = ref('')
const summaryRequests = createLatestRequest()
const memberRequests = createLatestRequest()
let disposed = false
const search = ref('')
const createVisible = ref(false)
const registrationLoaded = ref(false)
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
		const result = await getWorkspaceMembersReq(page, search.value.trim(), {
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
	saving.value = true
	try {
		await createWorkspaceMemberReq({ ...draft })
		draft.username = ''
		draft.nickname = ''
		draft.password = ''
		createVisible.value = false
		ElMessage.success('成员已创建')
		await Promise.all([loadSummary(), loadMembers(1)])
	} catch (failure) {
		ElMessage.error(failure?.response?.data?.msg || '创建失败，请检查输入')
	} finally {
		saving.value = false
	}
}
async function toggleMember(member) {
	if (memberState(member).blockedAction) return
	try {
		await ElMessageBox.confirm(
			`确定${member.enabled ? '停用' : '启用'}“${member.username}”吗？`,
			'确认成员状态',
			{ type: 'warning' }
		)
	} catch {
		return
	}
	try {
		await setWorkspaceMemberEnabledReq(member.id, !member.enabled)
		await Promise.all([loadSummary(), loadMembers(members.value.page)])
	} catch (failure) {
		ElMessage.error(failure?.response?.data?.msg || '状态修改失败')
	}
}
onMounted(() => {
	loadSummary()
	loadMembers()
})
onBeforeUnmount(() => {
	disposed = true
	summaryRequests.cancel()
	memberRequests.cancel()
})
</script>

<style scoped>
.workspace-page {
	max-width: 1100px;
	margin: auto;
	padding: 36px 24px 80px;
	color: var(--cloud-ink, #273936);
}
.page-header,
.panel-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 20px;
}
.page-header a {
	color: var(--cloud-primary, #277566);
}
.file-entry {
	display: inline-flex;
	align-items: center;
	gap: 9px;
	padding: 12px 16px;
	border: 1px solid #ccded4;
	background: var(--cloud-soft, #e8f1ec);
	border-radius: 10px;
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
	background: #dcece3;
}
.workspace-shortcuts {
	display: flex;
	flex-wrap: wrap;
	gap: 10px;
	margin-top: 24px;
}
.workspace-shortcuts a {
	display: flex;
	align-items: center;
	gap: 10px;
	padding: 11px 15px;
	border: 1px solid var(--cloud-line, #e1e6df);
	background: var(--cloud-surface, #fff);
	border-radius: 10px;
	color: var(--cloud-ink, #273936);
	font-size: 13px;
}
.workspace-shortcuts svg {
	width: 16px;
	height: 16px;
	color: var(--cloud-primary, #277566);
}
.workspace-shortcuts a:hover {
	border-color: #adcabd;
	background: #f7faf7;
}
.registration-panel {
	margin-top: 16px;
	padding: 22px 24px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 12px;
	background: var(--cloud-surface, #fff);
}
summary {
	cursor: pointer;
	font-size: 16px;
	font-weight: 600;
}
summary::marker {
	color: var(--cloud-primary, #277566);
}
summary span {
	font-size: 12px;
	font-weight: 400;
	color: var(--cloud-muted, #6d7974);
	margin-left: 12px;
}
details[open] > summary {
	margin-bottom: 22px;
}
summary:focus-visible,
.workspace-page a:focus-visible {
	outline: 2px solid var(--cloud-primary, #277566);
	outline-offset: 4px;
}
.page-header p,
.panel-heading p,
.hint,
.scope-note {
	color: var(--cloud-muted, #6d7974);
	line-height: 1.7;
}
.page-header > div > p:last-child {
	font-size: 13px;
}
.eyebrow {
	color: var(--cloud-primary, #277566) !important;
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
	gap: 14px;
	margin: 24px 0;
}
.stats > div {
	position: relative;
	padding: 20px 22px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 12px;
	background: var(--cloud-surface, #fff);
	display: flex;
	flex-direction: column;
	gap: 5px;
}
.stats > div::before {
	content: '';
	position: absolute;
	top: 23px;
	right: 22px;
	width: 7px;
	height: 7px;
	border-radius: 50%;
	background: var(--cloud-primary, #277566);
}
.stats > div:nth-child(3)::before {
	background: var(--cloud-accent, #ba7657);
}
.stats span {
	font-size: 13px;
	color: var(--cloud-muted, #6d7974);
}
.stats strong {
	font-size: 30px;
	font-weight: 600;
	font-variant-numeric: tabular-nums;
}
.stats small {
	font-size: 11px;
	color: var(--cloud-muted, #6d7974);
}
.member-panel {
	padding: 22px 24px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 12px;
	background: var(--cloud-surface, #fff);
}
.panel-heading p {
	font-size: 12px;
	margin-top: 6px;
}
.member-search {
	display: flex;
	gap: 10px;
	max-width: 540px;
	margin: 20px 0;
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
@media (max-width: 640px) {
	.workspace-page {
		padding: 22px 12px 70px;
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
	.page-header,
	.panel-heading {
		align-items: flex-start;
		flex-wrap: wrap;
		gap: 14px;
	}
	.page-header {
		flex-direction: column;
	}
	.workspace-shortcuts {
		gap: 7px;
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
	summary span {
		display: block;
		margin: 6px 0 0 16px;
	}
	.member-search .el-input {
		min-width: 0;
	}
}
</style>
