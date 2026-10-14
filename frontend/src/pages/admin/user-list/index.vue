<template>
	<section class="workspace-directory">
		<admin-form-header hide-bottom-border title="空间成员总览" />
		<div class="directory-heading">
			<p>
				按工作空间查看账号归属，先展示空间管理员。成员审批、文件和日常账号管理由对应空间管理员负责。
			</p>
			<el-button
				:loading="refreshing"
				:disabled="refreshing || !selectedSpace"
				@click="refreshCurrent"
				>刷新当前空间</el-button
			>
		</div>
		<div class="directory-layout">
			<aside class="space-navigation" aria-labelledby="space-navigation-title">
				<div class="space-navigation-heading">
					<h2 id="space-navigation-title">工作空间</h2>
					<router-link to="/admin/tenant-list">空间管理 →</router-link>
				</div>
				<form class="space-search" @submit.prevent="searchSpaces">
					<el-input
						v-model="spaceKeyword"
						placeholder="搜索空间名称 / ID"
						maxlength="100"
						clearable
						aria-label="搜索工作空间"
					/>
					<el-button native-type="submit" :loading="spacesLoading"
						>查询</el-button
					>
				</form>
				<el-alert
					v-if="spacesError"
					:title="spacesError"
					type="error"
					:closable="false"
					show-icon
				/>
				<el-button
					v-if="spacesError"
					class="retry-button"
					:loading="spacesLoading"
					@click="loadSpaces(spaceQuery)"
					>重试空间列表</el-button
				>
				<div
					v-else
					v-loading="spacesLoading"
					class="space-list"
					:aria-busy="spacesLoading"
				>
					<button
						v-for="space in spacePage?.records || []"
						:key="space.id"
						type="button"
						class="space-choice"
						:class="{
							'space-choice--selected': selectedSpace?.id === space.id,
						}"
						:aria-pressed="selectedSpace?.id === space.id"
						@click="selectSpace(space)"
					>
						<span class="space-choice-name">{{ space.name }}</span>
						<span class="space-choice-detail"
							>ID {{ space.id }} ·
							{{
								space.enabled === true
									? '启用'
									: space.enabled === false
									? '停用'
									: '状态未获取'
							}}</span
						>
						<span class="space-choice-detail"
							>管理员 {{ formatOverviewCount(space.workspaceAdmins) }} · 账号
							{{ formatOverviewCount(space.members) }}</span
						>
					</button>
					<p v-if="spacePage && !spacePage.records.length" class="empty-note">
						没有匹配的空间，请修改搜索条件。
					</p>
				</div>
				<div v-if="spacePage" class="space-pagination">
					<p class="hint">
						共 {{ formatOverviewCount(spacePage.total) }} 个空间
					</p>
					<el-pagination
						small
						:current-page="spacePage.page"
						:page-size="spacePage.pageSize"
						:total="spacePage.total"
						:disabled="spacesLoading"
						layout="prev, pager, next"
						:pager-count="5"
						@current-change="changeSpacePage"
					/>
				</div>
			</aside>

			<main class="space-content">
				<div v-if="!selectedSpace" class="selection-empty">
					{{
						spacesLoading ? '正在加载工作空间…' : '请在左侧选择一个工作空间。'
					}}
				</div>
				<template v-else>
					<header class="space-summary">
						<div>
							<p class="eyebrow">当前工作空间 · ID {{ selectedSpace.id }}</p>
							<h2>{{ selectedSpace.name }}</h2>
							<p class="hint">
								负责人账号 ID：{{ selectedSpace.ownerAccountId || '暂未分配' }}
							</p>
						</div>
						<el-tag
							:type="selectedSpace.enabled === true ? 'success' : 'info'"
							>{{ selectedSpace.enabled === true ? '启用' : '停用' }}</el-tag
						>
					</header>
					<p v-if="selectedSpace.enabled === false" class="space-warning">
						此空间已停用。若需恢复使用，请到工作空间管理中调整；停用期间不新增管理员。
					</p>
					<section
						class="people-panel"
						aria-labelledby="workspace-admins-title"
					>
						<div class="section-heading">
							<div>
								<h3 id="workspace-admins-title">空间管理员</h3>
								<p class="hint">
									负责此空间的成员、文件与存储；账号只属于当前空间。
								</p>
							</div>
							<el-button
								type="primary"
								:disabled="
									selectedSpace.enabled !== true || administratorsLoading
								"
								@click="createAdministrator"
								>新增空间管理员</el-button
							>
						</div>
						<form class="people-search" @submit.prevent="searchAdministrators">
							<el-input
								v-model="administratorKeyword"
								placeholder="搜索账号 ID、用户名、昵称或邮箱"
								maxlength="100"
								clearable
								aria-label="搜索空间管理员"
							/>
							<el-button native-type="submit" :loading="administratorsLoading"
								>查询管理员</el-button
							>
						</form>
						<el-alert
							v-if="administratorsError"
							:title="administratorsError"
							type="error"
							:closable="false"
							show-icon
						/>
						<el-button
							v-if="administratorsError"
							class="retry-button"
							:loading="administratorsLoading"
							@click="loadAdministrators(administratorQuery)"
							>重新加载管理员</el-button
						>
						<el-table
							v-else
							v-loading="administratorsLoading"
							:data="administratorPage?.records || []"
							:empty-text="
								administratorsLoading
									? '正在加载管理员…'
									: '此空间没有匹配的空间管理员'
							"
							:aria-busy="administratorsLoading"
						>
							<el-table-column label="序号" width="70"
								><template #default="scope">{{
									workspaceUserSequence(administratorPage, scope.$index)
								}}</template></el-table-column
							>
							<el-table-column
								label="账号 ID"
								min-width="185"
								show-overflow-tooltip
								><template #default="scope">{{
									scope.row.accountId || '未生成唯一账号 ID'
								}}</template></el-table-column
							>
							<el-table-column
								label="用户名 / 昵称"
								min-width="175"
								show-overflow-tooltip
								><template #default="scope"
									><span>{{ scope.row.username }}</span
									><span v-if="scope.row.nickname" class="row-subtitle">{{
										scope.row.nickname
									}}</span></template
								></el-table-column
							>
							<el-table-column label="角色" width="120"
								><template #default="scope"
									><el-tag type="warning">空间管理员</el-tag
									><small
										v-if="
											scope.row.accountId &&
											scope.row.accountId === selectedSpace.ownerAccountId
										"
										class="row-subtitle"
										>空间负责人</small
									></template
								></el-table-column
							>
							<el-table-column
								prop="email"
								label="邮箱"
								min-width="200"
								show-overflow-tooltip
							/>
							<el-table-column label="状态" width="100"
								><template #default="scope"
									><el-tag :type="workspaceUserStatus(scope.row).type">{{
										workspaceUserStatus(scope.row).label
									}}</el-tag></template
								></el-table-column
							>
							<el-table-column label="操作" width="145" fixed="right"
								><template #default="scope"
									><div
										v-if="
											canManageWorkspaceAdministrator(
												scope.row,
												selectedSpace.id
											)
										"
										class="row-actions"
									>
										<router-link :to="`/admin/user-edit/${scope.row.id}`"
											>编辑</router-link
										><el-button
											link
											:type="scope.row.enable ? 'warning' : 'primary'"
											:loading="savingAdministrators.has(scope.row.id)"
											:disabled="savingAdministrators.has(scope.row.id)"
											@click="changeAdministratorEnable(scope.row)"
											>{{ scope.row.enable ? '停用' : '启用' }}</el-button
										>
									</div>
									<span v-else class="hint">请检查账号状态</span></template
								></el-table-column
							>
						</el-table>
						<div v-if="administratorPage" class="people-pagination">
							<span class="hint"
								>共
								{{ formatOverviewCount(administratorPage.total) }}
								位匹配管理员</span
							><el-pagination
								:current-page="administratorPage.page"
								:page-size="administratorPage.pageSize"
								:page-sizes="[10, 20, 50]"
								:total="administratorPage.total"
								:disabled="administratorsLoading"
								layout="sizes, prev, pager, next"
								@current-change="changeAdministratorPage"
								@size-change="changeAdministratorPageSize"
							/>
						</div>
					</section>

					<section
						class="people-panel members-panel"
						aria-labelledby="workspace-members-title"
					>
						<button
							type="button"
							class="member-toggle"
							:aria-expanded="membersOpen"
							aria-controls="workspace-members-content"
							@click="toggleMembers"
						>
							<span
								><strong id="workspace-members-title">普通成员</strong
								><small>{{
									membersOpen
										? '此处仅查看账号归属与状态，不重复提供日常管理操作。'
										: '默认折叠；展开后才分页加载当前空间的成员。'
								}}</small></span
							><span class="member-toggle-action">{{
								membersOpen ? '收起 ↑' : '查看成员 ↓'
							}}</span>
						</button>
						<div
							v-if="membersOpen"
							id="workspace-members-content"
							class="members-content"
						>
							<p class="ownership-note">
								成员新增、启停和加入审批由本空间管理员处理。全局管理员在此只查看空间归属，不代为审核或操作成员私人文件。
							</p>
							<form class="people-search" @submit.prevent="searchMembers">
								<el-input
									v-model="memberKeyword"
									placeholder="搜索账号 ID、用户名、昵称或邮箱"
									maxlength="100"
									clearable
									aria-label="搜索当前空间成员"
								/><el-button native-type="submit" :loading="membersLoading"
									>查询成员</el-button
								>
							</form>
							<el-alert
								v-if="membersError"
								:title="membersError"
								type="error"
								:closable="false"
								show-icon
							/>
							<el-button
								v-if="membersError"
								class="retry-button"
								:loading="membersLoading"
								@click="loadMembers(memberQuery)"
								>重新加载成员</el-button
							>
							<el-table
								v-else
								v-loading="membersLoading"
								:data="memberPage?.records || []"
								:empty-text="
									membersLoading ? '正在加载成员…' : '此空间没有匹配的普通成员'
								"
								:aria-busy="membersLoading"
							>
								<el-table-column label="序号" width="70"
									><template #default="scope">{{
										workspaceUserSequence(memberPage, scope.$index)
									}}</template></el-table-column
								>
								<el-table-column
									label="账号 ID"
									min-width="185"
									show-overflow-tooltip
									><template #default="scope">{{
										scope.row.accountId || '未生成唯一账号 ID'
									}}</template></el-table-column
								>
								<el-table-column
									label="用户名 / 昵称"
									min-width="175"
									show-overflow-tooltip
									><template #default="scope"
										><span>{{ scope.row.username }}</span
										><span v-if="scope.row.nickname" class="row-subtitle">{{
											scope.row.nickname
										}}</span></template
									></el-table-column
								>
								<el-table-column label="角色" width="95"
									><template #default
										><el-tag type="info">普通成员</el-tag></template
									></el-table-column
								>
								<el-table-column
									prop="email"
									label="邮箱"
									min-width="200"
									show-overflow-tooltip
								/>
								<el-table-column label="状态" width="105"
									><template #default="scope"
										><el-tag :type="workspaceUserStatus(scope.row).type">{{
											workspaceUserStatus(scope.row).label
										}}</el-tag></template
									></el-table-column
								>
								<el-table-column label="注册时间" min-width="185"
									><template #default="scope">{{
										workspaceUserCreatedAt(scope.row.createTime)
									}}</template></el-table-column
								>
							</el-table>
							<div v-if="memberPage" class="people-pagination">
								<span class="hint"
									>共
									{{ formatOverviewCount(memberPage.total) }} 位匹配成员</span
								><el-pagination
									:current-page="memberPage.page"
									:page-size="memberPage.pageSize"
									:page-sizes="[10, 20, 50]"
									:total="memberPage.total"
									:disabled="membersLoading"
									layout="sizes, prev, pager, next"
									@current-change="changeMemberPage"
									@size-change="changeMemberPageSize"
								/>
							</div>
						</div>
					</section>
				</template>
			</main>
		</div>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getGlobalWorkspacesReq } from '~/api/admin/admin-overview'
import {
	getWorkspaceUsersReq,
	updateUserEnableReq,
} from '~/api/admin/admin-user'
import {
	formatOverviewCount,
	normalizeOverviewWorkspaceQuery,
	overviewFailureMessage,
} from '~/utils/admin-overview.mjs'
import {
	canManageWorkspaceAdministrator,
	createWorkspaceUserRequestGate,
	normalizeWorkspaceUserQuery,
	validWorkspaceUserPage,
	validWorkspaceDirectoryPage,
	workspaceAdministratorCreateLink,
	workspaceDirectorySearchQuery,
	workspaceUserCreatedAt,
	workspaceUserSequence,
	workspaceUserStatus,
	workspaceUserTenantId,
} from '~/utils/workspace-users.mjs'

const route = useRoute()
const router = useRouter()
const spacesGate = createWorkspaceUserRequestGate()
const administratorsGate = createWorkspaceUserRequestGate()
const membersGate = createWorkspaceUserRequestGate()
const spaceKeyword = ref('')
const spaceQuery = ref(normalizeOverviewWorkspaceQuery())
const spacePage = ref(null)
const spacesLoading = ref(false)
const spacesError = ref('')
const selectedSpace = ref(null)
const administratorKeyword = ref('')
const administratorQuery = ref(normalizeWorkspaceUserQuery())
const administratorPage = ref(null)
const administratorsLoading = ref(false)
const administratorsError = ref('')
const memberKeyword = ref('')
const memberQuery = ref(normalizeWorkspaceUserQuery({}, 'USER'))
const memberPage = ref(null)
const membersOpen = ref(false)
const membersLoading = ref(false)
const membersError = ref('')
const savingAdministrators = ref(new Set())
const refreshing = computed(
	() =>
		spacesLoading.value || administratorsLoading.value || membersLoading.value
)
let disposed = false

function clearCurrentSpace() {
	administratorsGate.cancel()
	membersGate.cancel()
	selectedSpace.value = null
	administratorPage.value = null
	memberPage.value = null
	administratorsLoading.value = false
	membersLoading.value = false
	administratorsError.value = ''
	membersError.value = ''
	membersOpen.value = false
	administratorKeyword.value = ''
	memberKeyword.value = ''
	administratorQuery.value = normalizeWorkspaceUserQuery()
	memberQuery.value = normalizeWorkspaceUserQuery({}, 'USER')
}

async function loadSpaces(params = spaceQuery.value, preferredId = null) {
	const request = spacesGate.begin()
	if (!request) return
	const query = normalizeOverviewWorkspaceQuery({ ...params, pageSize: 10 })
	spaceQuery.value = query
	spacePage.value = null
	spacesLoading.value = true
	spacesError.value = ''
	clearCurrentSpace()
	try {
		const response = await getGlobalWorkspacesReq(query, {
			signal: request.signal,
			timeout: 20000,
			showDefaultMsg: false,
		})
		if (!spacesGate.isCurrent(request)) return
		const data = response?.data
		if (!validWorkspaceDirectoryPage(data))
			throw new Error('Invalid workspace page')
		spacePage.value = data
		spaceQuery.value = { ...query, page: data.page, pageSize: data.pageSize }
		const target =
			preferredId === null
				? data.records[0]
				: data.records.find((space) => space.id === preferredId)
		if (target) selectSpace(target)
		else if (preferredId !== null)
			spacesError.value = '未找到指定的工作空间，请重新搜索。'
	} catch (failure) {
		if (spacesGate.isCurrent(request))
			spacesError.value = overviewFailureMessage(
				failure,
				'工作空间加载失败，请重试。'
			)
	} finally {
		if (spacesGate.isCurrent(request)) spacesLoading.value = false
		spacesGate.finish(request)
	}
}

function selectSpace(space) {
	if (selectedSpace.value?.id === space.id) return
	clearCurrentSpace()
	selectedSpace.value = space
	return loadAdministrators()
}

async function loadAdministrators(params = administratorQuery.value) {
	const tenantId = selectedSpace.value?.id
	if (!tenantId) return
	const request = administratorsGate.begin()
	if (!request) return
	const query = normalizeWorkspaceUserQuery(params)
	administratorQuery.value = query
	administratorPage.value = null
	administratorsLoading.value = true
	administratorsError.value = ''
	try {
		const response = await getWorkspaceUsersReq(tenantId, query, {
			signal: request.signal,
			timeout: 20000,
			showDefaultMsg: false,
		})
		if (
			!administratorsGate.isCurrent(request) ||
			selectedSpace.value?.id !== tenantId
		)
			return
		if (!validWorkspaceUserPage(response?.data, tenantId, 'TENANT_ADMIN'))
			throw new Error('Invalid administrator page')
		administratorPage.value = response.data
		administratorQuery.value = {
			...query,
			page: response.data.page,
			pageSize: response.data.pageSize,
		}
	} catch (failure) {
		if (
			administratorsGate.isCurrent(request) &&
			selectedSpace.value?.id === tenantId
		)
			administratorsError.value = overviewFailureMessage(
				failure,
				'空间管理员加载失败，请重试。'
			)
	} finally {
		if (administratorsGate.isCurrent(request))
			administratorsLoading.value = false
		administratorsGate.finish(request)
	}
}

async function loadMembers(params = memberQuery.value) {
	const tenantId = selectedSpace.value?.id
	if (!tenantId || !membersOpen.value) return
	const request = membersGate.begin()
	if (!request) return
	const query = normalizeWorkspaceUserQuery(params, 'USER')
	memberQuery.value = query
	memberPage.value = null
	membersLoading.value = true
	membersError.value = ''
	try {
		const response = await getWorkspaceUsersReq(tenantId, query, {
			signal: request.signal,
			timeout: 20000,
			showDefaultMsg: false,
		})
		if (
			!membersGate.isCurrent(request) ||
			selectedSpace.value?.id !== tenantId ||
			!membersOpen.value
		)
			return
		if (!validWorkspaceUserPage(response?.data, tenantId, 'USER'))
			throw new Error('Invalid member page')
		memberPage.value = response.data
		memberQuery.value = {
			...query,
			page: response.data.page,
			pageSize: response.data.pageSize,
		}
	} catch (failure) {
		if (
			membersGate.isCurrent(request) &&
			selectedSpace.value?.id === tenantId &&
			membersOpen.value
		)
			membersError.value = overviewFailureMessage(
				failure,
				'空间成员加载失败，请重试。'
			)
	} finally {
		if (membersGate.isCurrent(request)) membersLoading.value = false
		membersGate.finish(request)
	}
}

function searchSpaces() {
	return loadSpaces(
		workspaceDirectorySearchQuery(spaceKeyword.value, spaceQuery.value)
	)
}
function changeSpacePage(page) {
	return loadSpaces({ ...spaceQuery.value, page })
}
function searchAdministrators() {
	return loadAdministrators({
		...administratorQuery.value,
		keyword: administratorKeyword.value,
		page: 1,
	})
}
function searchMembers() {
	return loadMembers({
		...memberQuery.value,
		keyword: memberKeyword.value,
		page: 1,
	})
}
function changeAdministratorPage(page) {
	return loadAdministrators({ ...administratorQuery.value, page })
}
function changeAdministratorPageSize(pageSize) {
	return loadAdministrators({ ...administratorQuery.value, page: 1, pageSize })
}
function changeMemberPage(page) {
	return loadMembers({ ...memberQuery.value, page })
}
function changeMemberPageSize(pageSize) {
	return loadMembers({ ...memberQuery.value, page: 1, pageSize })
}

function toggleMembers() {
	membersOpen.value = !membersOpen.value
	if (membersOpen.value) return loadMembers()
	membersGate.cancel()
	membersLoading.value = false
	membersError.value = ''
	memberPage.value = null
}

function refreshCurrent() {
	if (refreshing.value || !selectedSpace.value) return
	return Promise.all([
		loadAdministrators(),
		membersOpen.value ? loadMembers() : Promise.resolve(),
	])
}

function createAdministrator() {
	if (selectedSpace.value?.enabled !== true) return
	const link = workspaceAdministratorCreateLink(selectedSpace.value.id)
	if (link) router.push(link)
}

async function changeAdministratorEnable(row) {
	const tenantId = selectedSpace.value?.id
	if (
		!canManageWorkspaceAdministrator(row, tenantId) ||
		savingAdministrators.value.has(row.id)
	)
		return
	const enable = !row.enable
	savingAdministrators.value = new Set([...savingAdministrators.value, row.id])
	try {
		await ElMessageBox.confirm(
			`确认${enable ? '启用' : '停用'}此空间管理员？不会改变所属工作空间。`,
			'管理员账号状态',
			{ type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
		)
		if (disposed || selectedSpace.value?.id !== tenantId) return
		await updateUserEnableReq(row.id, enable, { showDefaultMsg: false })
		if (disposed || selectedSpace.value?.id !== tenantId) return
		ElMessage.success('空间管理员状态已更新')
		await loadAdministrators()
	} catch (failure) {
		if (failure === 'cancel' || failure === 'close' || disposed) return
		ElMessage.error(
			overviewFailureMessage(failure, '管理员状态更新失败，请刷新后重试。')
		)
	} finally {
		if (!disposed) {
			const next = new Set(savingAdministrators.value)
			next.delete(row.id)
			savingAdministrators.value = next
		}
	}
}

function loadRequestedSpace() {
	const id = workspaceUserTenantId(
		route.query.workspaceId ?? route.query.tenantId
	)
	spaceKeyword.value = ''
	return loadSpaces({ workspaceId: id, page: 1, pageSize: 10 }, id)
}

onMounted(loadRequestedSpace)
watch(() => [route.query.workspaceId, route.query.tenantId], loadRequestedSpace)
onBeforeUnmount(() => {
	disposed = true
	spacesGate.dispose()
	administratorsGate.dispose()
	membersGate.dispose()
})
</script>

<style scoped>
.workspace-directory {
	color: #1f2937;
	padding-bottom: 36px;
}
.directory-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 20px;
	margin: 4px 0 22px;
}
.directory-heading > p {
	margin: 0;
	color: #64748b;
	font-size: 13px;
	line-height: 1.7;
	max-width: 750px;
}
.directory-layout {
	display: grid;
	grid-template-columns: 245px minmax(0, 1fr);
	gap: 20px;
	align-items: start;
}
.space-navigation {
	border: 1px solid #e2e8f0;
	background: #fbfcfa;
	border-radius: 12px;
	padding: 16px;
}
.space-navigation-heading,
.section-heading,
.space-summary {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
}
.space-navigation h2 {
	font-size: 16px;
	font-weight: 600;
	margin: 0;
}
.space-navigation-heading a,
.row-actions a {
	color: #38694a;
	font-size: 12px;
	text-decoration: none;
}
.space-search,
.people-search {
	display: flex;
	gap: 8px;
	margin: 15px 0;
}
.space-search :deep(.el-input),
.people-search :deep(.el-input) {
	min-width: 0;
}
.space-list {
	min-height: 80px;
}
.space-choice {
	display: flex;
	flex-direction: column;
	gap: 6px;
	width: 100%;
	padding: 12px;
	text-align: left;
	background: #fff;
	border: 1px solid #e2e8f0;
	border-radius: 8px;
	margin: 9px 0;
}
.space-choice:hover {
	border-color: #a4b7a8;
}
.space-choice--selected {
	border-color: #7e9c85;
	background: #eff5ef;
}
.space-choice:focus-visible,
.member-toggle:focus-visible {
	outline: 2px solid #466b54;
	outline-offset: 3px;
}
.space-choice-name {
	font-size: 13px;
	font-weight: 600;
	color: #334155;
	word-break: break-word;
}
.space-choice-detail {
	font-size: 11px;
	color: #64748b;
	line-height: 1.5;
}
.space-pagination {
	margin-top: 14px;
}
.space-pagination :deep(.el-pagination) {
	justify-content: center;
}
.space-content {
	min-width: 0;
}
.selection-empty {
	border: 1px dashed #d4ddd6;
	border-radius: 12px;
	min-height: 180px;
	display: grid;
	place-content: center;
	color: #64748b;
	font-size: 14px;
	padding: 20px;
}
.space-summary {
	padding: 16px 20px;
	border: 1px solid #e2e8f0;
	border-radius: 12px;
	background: #fbfcfa;
	margin-bottom: 18px;
}
.space-summary h2 {
	font-size: 22px;
	font-weight: 600;
	margin: 5px 0 0;
	word-break: break-word;
}
.eyebrow {
	font-size: 11px;
	letter-spacing: 0.08em;
	color: #466b54;
	margin: 0;
}
.hint,
.empty-note {
	color: #64748b;
	font-size: 12px;
	line-height: 1.7;
}
.space-summary .hint {
	margin: 7px 0 0;
}
.people-panel {
	padding: 20px;
	border: 1px solid #e2e8f0;
	border-radius: 12px;
	margin-bottom: 18px;
	background: #fff;
}
.section-heading h3 {
	font-size: 16px;
	font-weight: 600;
	margin: 0;
}
.section-heading .hint {
	margin: 6px 0 0;
}
.people-search {
	max-width: 500px;
}
.people-pagination {
	display: flex;
	justify-content: space-between;
	flex-wrap: wrap;
	align-items: center;
	gap: 10px;
	margin-top: 18px;
}
.row-subtitle {
	display: block;
	color: #64748b;
	font-size: 11px;
	line-height: 1.8;
}
.row-actions {
	display: flex;
	align-items: center;
	gap: 14px;
}
.row-actions a {
	font-size: 13px;
}
.row-actions a:hover {
	text-decoration: underline;
}
.members-panel {
	padding: 0;
}
.member-toggle {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 18px;
	width: 100%;
	padding: 20px;
	text-align: left;
}
.member-toggle strong {
	font-size: 16px;
	font-weight: 600;
	display: block;
}
.member-toggle small {
	display: block;
	margin-top: 7px;
	color: #64748b;
	line-height: 1.65;
	font-size: 12px;
}
.member-toggle-action {
	font-size: 12px;
	color: #466b54;
	white-space: nowrap;
}
.members-content {
	padding: 0 20px 20px;
}
.ownership-note,
.space-warning {
	font-size: 12px;
	line-height: 1.75;
	color: #886336;
	background: #fcf8ef;
	padding: 10px 12px;
	border-radius: 6px;
	margin: 0 0 16px;
}
.retry-button {
	margin: 14px 0;
}
@media (max-width: 1000px) {
	.directory-layout {
		grid-template-columns: 210px minmax(0, 1fr);
		gap: 14px;
	}
	.section-heading {
		flex-wrap: wrap;
		align-items: flex-start;
	}
	.people-panel {
		padding: 16px;
	}
	.members-panel {
		padding: 0;
	}
}
@media (max-width: 760px) {
	.directory-layout {
		grid-template-columns: minmax(0, 1fr);
	}
	.directory-heading {
		align-items: flex-start;
		flex-direction: column;
		gap: 12px;
	}
	.space-list {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 8px;
	}
	.space-choice {
		margin: 0;
	}
	.space-navigation {
		padding: 14px;
	}
	.space-summary {
		padding: 16px;
	}
	.member-toggle {
		padding: 16px;
	}
	.members-content {
		padding: 0 16px 16px;
	}
	.people-pagination :deep(.el-pagination) {
		flex-wrap: wrap;
		row-gap: 10px;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 空间成员总览
</route>
