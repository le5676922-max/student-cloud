<template>
	<div class="workspace-registration-panels">
		<section
			class="registration-panel"
			aria-labelledby="workspace-invitations-title"
		>
			<div class="registration-panel-heading">
				<div>
					<h2 id="workspace-invitations-title">邀请成员</h2>
					<p>邀请码让新成员在注册时直接加入本空间。</p>
				</div>
				<div class="panel-actions">
					<el-button :loading="invitationsLoading" @click="loadInvitations"
						>刷新</el-button
					>
					<el-button type="primary" @click="openInvitation"
						>创建邀请码</el-button
					>
				</div>
			</div>
			<el-alert
				v-if="invitationError"
				:title="invitationError"
				type="error"
				:closable="false"
				show-icon
			/>
			<el-table
				v-loading="invitationsLoading"
				:data="invitations"
				empty-text="暂无邀请码"
				class="registration-table"
			>
				<el-table-column prop="id" label="邀请编号" min-width="120" />
				<el-table-column label="创建时间" min-width="170"
					><template #default="scope">{{
						formatTime(scope.row.createdAt)
					}}</template></el-table-column
				>
				<el-table-column label="到期时间" min-width="170"
					><template #default="scope">{{
						invitationExpiry(scope.row.expiresAt)
					}}</template></el-table-column
				>
				<el-table-column label="已使用 / 上限" min-width="130"
					><template #default="scope"
						>{{ scope.row.useCount }} /
						{{
							scope.row.maxUses == null ? '不限人数' : scope.row.maxUses
						}}</template
					></el-table-column
				>
				<el-table-column label="状态" width="100"
					><template #default="scope"
						><el-tag :type="invitationStatus(scope.row).type">{{
							invitationStatus(scope.row).label
						}}</el-tag></template
					></el-table-column
				>
				<el-table-column label="操作" width="100"
					><template #default="scope"
						><el-button
							link
							type="danger"
							:loading="revoking.has(scope.row.id)"
							:disabled="scope.row.revoked || revoking.has(scope.row.id)"
							@click="revokeInvitation(scope.row)"
							>撤销</el-button
						></template
					></el-table-column
				>
			</el-table>
			<p class="registration-hint">
				列表只显示邀请记录；完整邀请码仅在创建成功时显示一次。
			</p>
		</section>

		<section
			class="registration-panel"
			aria-labelledby="workspace-join-requests-title"
		>
			<div class="registration-panel-heading">
				<div>
					<h2 id="workspace-join-requests-title">加入申请</h2>
					<p>通过空间名称注册的成员，需要你批准后才能登录并加入本空间。</p>
				</div>
				<el-button :loading="requestsLoading" @click="loadRequests"
					>刷新</el-button
				>
			</div>
			<el-alert
				v-if="requestError"
				:title="requestError"
				type="error"
				:closable="false"
				show-icon
			/>
			<el-table
				v-loading="requestsLoading"
				:data="requests"
				empty-text="暂无待审批申请"
				class="registration-table"
			>
				<el-table-column prop="accountId" label="账号 ID" min-width="180" />
				<el-table-column prop="email" label="邮箱" min-width="200" />
				<el-table-column prop="nickname" label="昵称" min-width="120" />
				<el-table-column label="申请时间" min-width="170"
					><template #default="scope">{{
						formatTime(scope.row.createdAt)
					}}</template></el-table-column
				>
				<el-table-column label="操作" width="150"
					><template #default="scope">
						<el-button
							link
							type="primary"
							:disabled="reviewing.has(scope.row.id)"
							:loading="reviewing.has(scope.row.id)"
							@click="reviewRequest(scope.row, true)"
							>批准</el-button
						>
						<el-button
							link
							type="danger"
							:disabled="reviewing.has(scope.row.id)"
							@click="reviewRequest(scope.row, false)"
							>拒绝</el-button
						>
					</template></el-table-column
				>
			</el-table>
		</section>

		<el-dialog
			v-model="invitationVisible"
			title="创建邀请码"
			width="min(520px, 92vw)"
			:close-on-click-modal="!creating"
			:close-on-press-escape="!creating"
			:show-close="!creating"
			@closed="clearCreatedInvitation"
		>
			<template v-if="createdInvitation">
				<el-alert
					title="邀请码仅在本次显示，关闭后无法再次查看。请现在复制并保存。"
					type="success"
					:closable="false"
					show-icon
				/>
				<label class="invitation-code-label" for="workspace-created-invitation"
					>完整邀请码</label
				>
				<div class="invitation-code-row">
					<el-input
						id="workspace-created-invitation"
						:model-value="createdInvitation.code"
						readonly
					/>
					<el-button :loading="copying" @click="copyInvitation">复制</el-button>
				</div>
				<p class="registration-hint">
					{{
						createdInvitation.expiresAt == null
							? '长期有效'
							: `有效期至 ${formatTime(createdInvitation.expiresAt)}`
					}}，
					{{
						createdInvitation.maxUses == null
							? '不限加入人数'
							: `最多可加入 ${createdInvitation.maxUses} 位成员`
					}}。
				</p>
			</template>
			<el-form
				v-else
				:disabled="creating"
				label-position="top"
				@submit.prevent="createInvitation"
			>
				<el-form-item
					label="有效天数（1–30 天）"
					for="workspace-invitation-days"
					><el-input-number
						id="workspace-invitation-days"
						v-model="invitationDraft.expiresInDays"
						:min="1"
						:max="30"
						:precision="0"
				/></el-form-item>
				<el-form-item
					label="最多可加入的成员数（1–100 人）"
					for="workspace-invitation-uses"
					><el-input-number
						id="workspace-invitation-uses"
						v-model="invitationDraft.maxUses"
						:min="1"
						:max="100"
						:precision="0"
				/></el-form-item>
				<p class="registration-hint">
					有效邀请码会直接加入本空间，无需再审批。你可以随时在列表中撤销邀请码。
				</p>
				<p v-if="createError" class="registration-error" role="alert">
					{{ createError }}
				</p>
			</el-form>
			<template #footer>
				<el-button :disabled="creating" @click="invitationVisible = false">{{
					createdInvitation ? '已保存，关闭' : '取消'
				}}</el-button>
				<el-button
					v-if="!createdInvitation"
					type="primary"
					:loading="creating"
					:disabled="!validInvitationDraft"
					@click="createInvitation"
					>创建</el-button
				>
			</template>
		</el-dialog>
	</div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useClipboard } from '@vueuse/core'
import {
	createWorkspaceInvitationReq,
	getWorkspaceInvitationsReq,
	getWorkspaceJoinRequestsReq,
	revokeWorkspaceInvitationReq,
	reviewWorkspaceJoinRequestReq,
} from '~/api/home/workspace-admin'

const emit = defineEmits(['members-changed'])
const invitations = ref([])
const requests = ref([])
const invitationsLoading = ref(false)
const requestsLoading = ref(false)
const invitationError = ref('')
const requestError = ref('')
const invitationVisible = ref(false)
const invitationDraft = reactive({ expiresInDays: 7, maxUses: 20 })
const validInvitationDraft = computed(
	() =>
		Number.isInteger(invitationDraft.expiresInDays) &&
		invitationDraft.expiresInDays >= 1 &&
		invitationDraft.expiresInDays <= 30 &&
		Number.isInteger(invitationDraft.maxUses) &&
		invitationDraft.maxUses >= 1 &&
		invitationDraft.maxUses <= 100
)
const creating = ref(false)
const copying = ref(false)
const createdInvitation = ref(null)
const createError = ref('')
const revoking = ref(new Set())
const reviewing = ref(new Set())
const { copy } = useClipboard({ legacy: true })
let disposed = false
let invitationRequest = 0
let joinRequest = 0
const failureMessage = (failure, fallback) =>
	failure?.response?.data?.msg || failure?.data?.msg || fallback
const formatTime = (value) => {
	if (!value) return '—'
	const date = new Date(value)
	return Number.isNaN(date.getTime())
		? '—'
		: date.toLocaleString('zh-CN', { hour12: false })
}
const invitationStatus = (invitation) => {
	if (invitation.revoked) return { label: '已撤销', type: 'info' }
	if (
		invitation.expiresAt != null &&
		new Date(invitation.expiresAt).getTime() <= Date.now()
	)
		return { label: '已过期', type: 'info' }
	if (invitation.maxUses != null && invitation.useCount >= invitation.maxUses)
		return { label: '已用完', type: 'warning' }
	return { label: '有效', type: 'success' }
}
const invitationExpiry = (value) =>
	value == null ? '长期有效' : formatTime(value)
async function loadInvitations() {
	const current = ++invitationRequest
	invitationsLoading.value = true
	invitationError.value = ''
	try {
		const response = await getWorkspaceInvitationsReq()
		if (!disposed && current === invitationRequest)
			invitations.value = response.data
	} catch (failure) {
		if (!disposed && current === invitationRequest)
			invitationError.value = failureMessage(failure, '邀请记录加载失败')
	} finally {
		if (!disposed && current === invitationRequest)
			invitationsLoading.value = false
	}
}
async function loadRequests() {
	const current = ++joinRequest
	requestsLoading.value = true
	requestError.value = ''
	try {
		const response = await getWorkspaceJoinRequestsReq()
		if (!disposed && current === joinRequest) requests.value = response.data
	} catch (failure) {
		if (!disposed && current === joinRequest)
			requestError.value = failureMessage(failure, '加入申请加载失败')
	} finally {
		if (!disposed && current === joinRequest) requestsLoading.value = false
	}
}
function clearCreatedInvitation() {
	createdInvitation.value = null
	createError.value = ''
}
function openInvitation() {
	clearCreatedInvitation()
	invitationDraft.expiresInDays = 7
	invitationDraft.maxUses = 20
	invitationVisible.value = true
}
async function createInvitation() {
	if (creating.value || createdInvitation.value || !validInvitationDraft.value)
		return
	creating.value = true
	createError.value = ''
	try {
		const response = await createWorkspaceInvitationReq({ ...invitationDraft })
		if (disposed) return
		createdInvitation.value = response.data
		await loadInvitations()
	} catch (failure) {
		if (!disposed) createError.value = failureMessage(failure, '邀请码创建失败')
	} finally {
		if (!disposed) creating.value = false
	}
}
async function copyInvitation() {
	if (copying.value || !createdInvitation.value?.code) return
	copying.value = true
	try {
		await copy(createdInvitation.value.code)
		ElMessage.success('邀请码已复制')
	} catch {
		ElMessage.warning('复制失败，请选中邀请码手动复制。')
	} finally {
		copying.value = false
	}
}
async function revokeInvitation(invitation) {
	if (revoking.value.has(invitation.id) || invitation.revoked) return
	revoking.value.add(invitation.id)
	try {
		await ElMessageBox.confirm(
			'撤销后，成员将无法继续使用此邀请码注册。',
			'撤销邀请码',
			{ type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' }
		)
		if (disposed) return
		await revokeWorkspaceInvitationReq(invitation.id)
		if (disposed) return
		ElMessage.success('邀请码已撤销')
		await loadInvitations()
	} catch (failure) {
		if (!disposed && failure !== 'cancel' && failure !== 'close')
			ElMessage.error(failureMessage(failure, '撤销失败'))
	} finally {
		revoking.value.delete(invitation.id)
	}
}
async function reviewRequest(request, approve) {
	if (reviewing.value.has(request.id)) return
	reviewing.value.add(request.id)
	try {
		await ElMessageBox.confirm(
			approve
				? `批准“${request.nickname || request.accountId}”加入本空间？`
				: `拒绝“${request.nickname || request.accountId}”的加入申请？`,
			'审批加入申请',
			{
				type: approve ? 'info' : 'warning',
				confirmButtonText: approve ? '批准' : '拒绝',
				cancelButtonText: '取消',
			}
		)
		if (disposed) return
		await reviewWorkspaceJoinRequestReq(request.id, approve)
		if (disposed) return
		ElMessage.success(approve ? '已批准加入申请' : '已拒绝加入申请')
		// Both outcomes change the membership status shown in the parent table.
		emit('members-changed')
		await loadRequests()
	} catch (failure) {
		if (!disposed && failure !== 'cancel' && failure !== 'close')
			ElMessage.error(failureMessage(failure, '审批失败，请刷新申请列表后重试'))
	} finally {
		reviewing.value.delete(request.id)
	}
}
onMounted(() => {
	loadInvitations()
	loadRequests()
})
onBeforeUnmount(() => {
	disposed = true
	createdInvitation.value = null
})
</script>

<style scoped>
.workspace-registration-panels {
	display: grid;
	gap: 24px;
	margin-top: 24px;
}
.registration-panel {
	padding: 22px;
	border: 1px solid #e2e8f0;
	border-radius: 12px;
}
.registration-panel-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 20px;
}
.registration-panel-heading h2 {
	margin: 0 0 8px;
	font-size: 20px;
	font-weight: 600;
}
.registration-panel-heading p,
.registration-hint {
	color: #64748b;
	font-size: 12px;
	line-height: 1.8;
}
.registration-panel-heading p {
	margin: 0;
}
.panel-actions {
	display: flex;
	flex-shrink: 0;
}
.registration-table {
	margin-top: 20px;
}
.registration-hint {
	margin: 14px 0 0;
}
.registration-panel .el-alert {
	margin-top: 16px;
}
.invitation-code-label {
	display: block;
	margin: 20px 0 10px;
	font-size: 13px;
}
.invitation-code-row {
	display: flex;
	align-items: center;
	gap: 10px;
}
.invitation-code-row .el-input {
	min-width: 0;
	flex: 1;
}
.registration-error {
	color: #a52b26;
	font-size: 13px;
	line-height: 1.7;
}
@media (max-width: 640px) {
	.registration-panel {
		padding: 18px 12px;
	}
	.registration-panel-heading {
		flex-direction: column;
		align-items: flex-start;
		gap: 12px;
	}
}
</style>
