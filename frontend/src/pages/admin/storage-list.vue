<template>
	<div class="storage-list-page">
		<admin-form-header title="存储源管理">
			<template #subTitle>
				<div class="storage-list-heading">
					<p>统一维护云盘连接。状态仅表示配置，不代表实时连通性。</p>
					<div class="storage-list-tools">
						<el-input
							ref="searchInputRef"
							v-model="searchKey"
							placeholder="搜索名称、空间、备注或类型"
							maxlength="120"
							aria-label="搜索存储源"
							:prefix-icon="MagnifyingGlassIcon"
							@compositionstart="beginSearchComposition"
							@compositionend="finishSearchComposition"
						>
							<template #suffix>
								<button
									v-if="searchKey"
									type="button"
									class="storage-search-clear"
									aria-label="清空搜索"
									@click="clearSearch"
								>
									×
								</button>
							</template>
						</el-input>
						<el-button
							:loading="loading"
							:disabled="sorting || operationPending"
							@click="init"
							>刷新</el-button
						>
						<el-button type="primary" :disabled="sorting" @click="addStorage"
							><PlusIcon aria-hidden="true" />添加存储源</el-button
						>
					</div>
				</div>
			</template>
		</admin-form-header>
		<LocalStorageRequests @changed="init" />
		<div
			class="storage-status-filters"
			role="group"
			aria-label="按存储源状态筛选"
		>
			<button
				v-for="option in statusOptions"
				:key="option.value"
				type="button"
				:aria-pressed="statusFilter === option.value"
				:disabled="sorting"
				@click="statusFilter = option.value"
			>
				{{ option.label }}<span>{{ option.count ?? '—' }}</span>
			</button>
		</div>
		<div class="storage-results-summary" aria-live="polite">
			<p>
				{{
					loading
						? '正在读取…'
						: hasLoaded
						? `当前显示 ${storageListSearchResult.length} 个存储源`
						: '尚未读取存储源'
				}}
				<span v-if="hasStorageFilters"> · 清空筛选后可调整顺序</span>
				<span v-else-if="!isIndependent || isGlobalAdmin">
					· 拖动卡片，或在“更多”中前移 / 后移</span
				>
			</p>
			<button
				v-if="hasStorageFilters"
				type="button"
				:disabled="sorting"
				@click="clearFilters"
			>
				清空筛选
			</button>
		</div>
		<div v-if="loadError" class="storage-list-notice" role="alert">
			<span>{{ loadError }}</span
			><el-button :disabled="sorting || operationPending" @click="init"
				>重新加载</el-button
			>
			<small v-if="showingPreviousData"
				>下方保留上次读取的数据，并非本次刷新结果。</small
			>
		</div>
		<div v-else-if="loading" class="storage-list-progress" role="status">
			正在读取存储源，可继续切换其他页面。
		</div>
		<div v-if="operationError" class="storage-list-notice" role="alert">
			{{ operationError }}
		</div>
		<div v-if="sorting" class="storage-list-progress" role="status">
			正在保存顺序…
		</div>
		<section v-if="emptyState" class="storage-list-empty" role="status">
			<h2>
				{{
					emptyState === 'filtered' ? '没有匹配的存储源' : '还没有配置存储源'
				}}
			</h2>
			<p>
				{{
					emptyState === 'filtered'
						? '试试其他名称、类型，或清空筛选查看全部。'
						: '添加本地目录、对象存储或其他云盘，开始管理文件。'
				}}
			</p>
			<el-button v-if="emptyState === 'filtered'" @click="clearFilters"
				>清空筛选</el-button
			>
			<el-button v-else type="primary" @click="addStorage"
				>添加第一个存储源</el-button
			>
		</section>
		<ul
			ref="storageContainerRef"
			role="list"
			:aria-busy="loading || sorting || operationPending"
			class="storage-container"
		>
			<li
				v-for="storage in storageListSearchResult"
				:key="storage.id"
				class="storage-item"
				:aria-busy="isRowPending(storage)"
			>
				<div class="storage-card-body">
					<div class="storage-card-heading">
						<div class="storage-icon-disc">
							<img
								:src="getStorageIcon(storage.type?.key)"
								alt=""
								width="44"
								height="44"
							/>
						</div>
						<div class="storage-card-identity">
							<h3 class="storage-card-name">{{ storage.name }}</h3>
							<p>{{ storage.type?.description || '未知类型' }}</p>
						</div>
						<button
							type="button"
							class="storage-open-button"
							:disabled="
								storage.enable !== true || sorting || isRowPending(storage)
							"
							:aria-label="`在新窗口打开 ${storage.name}`"
							:title="
								storage.enable === true
									? '在新窗口打开云盘'
									: '此存储源已停用，启用后才能打开'
							"
							@click="openStorage(storage)"
						>
							<i-material-symbols-open-in-new aria-hidden="true" />
						</button>
					</div>
					<p v-if="isGlobalAdmin" class="storage-card-workspace">
						{{
							storage.tenantName || `工作空间 ${storage.tenantId ?? '未分配'}`
						}}
					</p>
					<p v-if="storage.remark" class="storage-card-remark">
						{{ storage.remark }}
					</p>
					<div class="storage-card-status">
						<el-tag
							:type="storage.enable === true ? 'success' : 'info'"
							size="small"
							>{{
								storage.enable === true
									? '已启用'
									: storage.enable === false
									? '已停用'
									: '状态未获取'
							}}</el-tag
						>
						<el-tag v-if="storage.enableCache" type="info" size="small"
							>缓存</el-tag
						>
						<span
							v-if="isRowPending(storage)"
							class="storage-pending-label"
							role="status"
							>正在处理…</span
						>
						<span
							v-else-if="storage.enable !== true"
							class="storage-availability"
							>启用后才能打开</span
						>
					</div>
					<details
						v-if="storage.refreshTokenInfo"
						class="storage-token-details"
					>
						<summary>
							{{
								storage.refreshTokenInfo.success === false
									? '令牌刷新失败 · 查看详情'
									: '令牌刷新记录'
							}}
						</summary>
						<p>
							上次刷新：{{
								storage.refreshTokenInfo.lastRefreshTime || '未提供'
							}}
						</p>
						<p v-if="storage.refreshTokenInfo.success === false">
							请编辑连接并检查授权信息。
						</p>
						<p v-else>
							到期时间：{{
								storage.refreshTokenInfo.data?.expiredAtDate || '未提供'
							}}
						</p>
					</details>
				</div>
				<div class="storage-card-actions">
					<button
						type="button"
						class="storage-card-action"
						:disabled="
							!canChangeStorage(storage) || sorting || isRowPending(storage)
						"
						:aria-label="`编辑 ${storage.name}`"
						@click="editStorage(storage)"
					>
						<i-mdi-rename-outline aria-hidden="true" /><span>编辑</span>
					</button>
					<button
						type="button"
						class="storage-card-action"
						:disabled="
							!canChangeStorage(storage) ||
							loading ||
							sorting ||
							isRowPending(storage)
						"
						:aria-label="`${storage.enable ? '停用' : '启用'} ${storage.name}`"
						@click="switchEnableStatus(storage)"
					>
						<component
							:is="storage.enable ? PauseCircleIcon : PlayCircleIcon"
							aria-hidden="true"
						/><span>{{ storage.enable ? '停用' : '启用' }}</span>
					</button>
					<el-dropdown
						trigger="click"
						class="storage-more-menu"
						:disabled="sorting || isRowPending(storage)"
						@command="handleOperator"
					>
						<el-button
							text
							class="storage-card-action"
							:disabled="sorting || isRowPending(storage)"
							:aria-label="`${storage.name} 的更多操作`"
							aria-haspopup="menu"
							><i-material-symbols-more-horiz aria-hidden="true" /><span
								>更多</span
							></el-button
						>
						<template #dropdown>
							<el-dropdown-menu>
								<el-dropdown-item
									v-if="canChangeStorage(storage)"
									:disabled="loading"
									:icon="PencilIcon"
									:command="{ operator: 'edit', storage }"
									>编辑</el-dropdown-item
								>
								<el-dropdown-item
									v-if="!isIndependent || isGlobalAdmin"
									:disabled="!canMoveStorage(storage, -1)"
									:command="{ operator: 'moveEarlier', storage }"
									>前移</el-dropdown-item
								>
								<el-dropdown-item
									v-if="!isIndependent || isGlobalAdmin"
									:disabled="!canMoveStorage(storage, 1)"
									:command="{ operator: 'moveLater', storage }"
									>后移</el-dropdown-item
								>
								<el-dropdown-item
									v-if="isGlobalAdmin"
									divided
									:disabled="isIndependent"
									:icon="LockOpenIcon"
									:command="{ operator: 'permissionManager', storage }"
									>{{
										isIndependent ? '成员权限由空间管理员设置' : '权限管理'
									}}</el-dropdown-item
								>
								<el-dropdown-item
									v-if="isGlobalAdmin"
									:icon="DocumentTextIcon"
									:disabled="isIndependent"
									title="当前独立后端暂未提供目录文档设置"
									:command="{ operator: 'readmeManager', storage }"
									>目录文档</el-dropdown-item
								>
								<el-dropdown-item
									v-if="isGlobalAdmin"
									:icon="EyeSlashIcon"
									:disabled="isIndependent"
									title="当前独立后端暂未提供文件过滤设置"
									:command="{ operator: 'filterManager', storage }"
									>文件过滤</el-dropdown-item
								>
								<el-dropdown-item
									v-if="isGlobalAdmin"
									:icon="KeyIcon"
									:disabled="isIndependent"
									title="当前独立后端暂未提供目录密码设置"
									:command="{ operator: 'pwdManager', storage }"
									>密码设置</el-dropdown-item
								>
								<el-dropdown-item
									v-if="
										isGlobalAdmin ||
										storage.type?.key?.toLowerCase() !== 'local'
									"
									divided
									:icon="ClipboardDocumentIcon"
									:disabled="isIndependent"
									title="当前独立后端暂未提供复制存储源"
									:command="{ operator: 'copy', storage }"
									>复制存储源</el-dropdown-item
								>
								<el-dropdown-item
									v-if="canDeleteStorage(storage)"
									divided
									:icon="TrashIcon"
									:disabled="
										loading ||
										(isIndependent && (!isGlobalAdmin || storage.enable))
									"
									:title="
										isIndependent && storage.enable
											? '先停用后才能删除连接配置'
											: '仅删除连接配置，不删除文件'
									"
									:command="{ operator: 'delete', storage }"
									>删除</el-dropdown-item
								>
							</el-dropdown-menu>
						</template>
					</el-dropdown>
				</div>
				<p v-if="!canChangeStorage(storage)" class="storage-access-note">
					{{
						storage.type?.key?.toLowerCase() === 'local'
							? '由全局管理员维护'
							: '由所属空间管理员维护'
					}}
				</p>
			</li>
			<li v-if="!emptyState" class="add-storage-btn">
				<button
					type="button"
					class="storage-add-button"
					:disabled="sorting"
					@click="addStorage"
				>
					<PlusIcon aria-hidden="true" /><span>添加存储源</span>
				</button>
			</li>
		</ul>
	</div>
	<storage-copy @close="init"></storage-copy>
</template>

<script setup>
import { computed, defineAsyncComponent } from 'vue'
import useStorageConfigStore from '~/stores/storage-config'
const LocalStorageRequests = defineAsyncComponent(() =>
	import('~/components/admin/LocalStorageRequests.vue')
)
const storageConfigStore = useStorageConfigStore()
const isGlobalAdmin = computed(
	() => storageConfigStore.loginInfo.role === 'ADMIN'
)
const isTenantAdmin = computed(
	() => storageConfigStore.loginInfo.role === 'TENANT_ADMIN'
)
const workspaceId = computed(() => storageConfigStore.loginInfo.tenantId)
const isIndependent = computed(
	() => storageConfigStore.globalConfig?.accountModel === 'independent'
)
import {
	PencilIcon,
	MagnifyingGlassIcon,
	TrashIcon,
	KeyIcon,
	LockOpenIcon,
	PauseCircleIcon,
	PlayCircleIcon,
	EyeSlashIcon,
	DocumentTextIcon,
	ClipboardDocumentIcon,
	PlusIcon,
} from '@heroicons/vue/24/outline'
import StorageCopy from '~/pages/admin/storage-copy/index.vue'
import useStorageList from '~/composables/admin/storage/storage-list'
import { getStorageIcon } from '~/utils/storage-icons'
import { navigationCredentialIdentity } from '~/utils/navigation-credential.mjs'

let router = useRouter()
const {
	init,
	loading,
	sorting,
	loadError,
	operationError,
	storageContainerRef,
	searchKey,
	searchInputRef,
	clearSearch,
	beginSearchComposition,
	finishSearchComposition,
	operationPending,
	isRowPending,
	canMoveStorage,
	statusFilter,
	statusOptions,
	hasStorageFilters,
	hasLoaded,
	emptyState,
	clearFilters,
	showingPreviousData,
	storageListSearchResult,
	handleOperator,
	canChangeStorage,
	canDeleteStorage,
	editStorage,
	addStorage,
	switchEnableStatus,
} = useStorageList(router, {
	independent: isIndependent,
	globalAdmin: isGlobalAdmin,
	tenantAdmin: isTenantAdmin,
	workspaceId,
	sessionIdentity: computed(() => JSON.stringify(storageConfigStore.loginInfo)),
	credentialIdentity: () =>
		navigationCredentialIdentity({
			storage: window.localStorage,
			cookie: document.cookie,
		}),
})

onMounted(() => {
	init()
})

const openStorage = (storage) => {
	if (storage?.enable !== true || sorting.value || isRowPending(storage)) return
	window.open(
		`/${encodeURIComponent(storage.key)}`,
		'_blank',
		'noopener,noreferrer'
	)
}
</script>

<style scoped>
.storage-list-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	flex-wrap: wrap;
	gap: 12px;
}
.storage-list-heading > p {
	margin: 0;
}
.storage-list-tools {
	display: flex;
	gap: 8px;
	align-items: center;
}
.storage-list-tools .el-input {
	width: 270px;
}
.storage-list-tools > .el-button svg {
	width: 16px;
	height: 16px;
	margin-right: 5px;
}
.storage-search-clear {
	display: grid;
	place-items: center;
	min-width: 28px;
	min-height: 28px;
	border-radius: 6px;
	font-size: 20px;
	color: var(--cloud-muted);
}
.storage-container {
	display: grid;
	grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr));
	gap: 14px;
	align-items: start;
	padding: 0;
}
.storage-container > li {
	min-width: 0;
	list-style: none;
	border: 1px solid var(--cloud-line);
	border-radius: 12px;
	background: var(--cloud-surface);
	overflow: hidden;
}
.storage-card-body {
	padding: 16px;
}
.storage-card-heading {
	display: flex;
	align-items: center;
	gap: 12px;
}
.storage-icon-disc {
	display: grid;
	place-items: center;
	flex: 0 0 44px;
	width: 44px;
	height: 44px;
	border-radius: 10px;
	background: var(--cloud-soft);
}
.storage-icon-disc img {
	width: 44px;
	height: 44px;
	padding: 9px;
	object-fit: contain;
}
.storage-card-identity {
	min-width: 0;
	flex: 1;
}
.storage-card-name {
	margin: 0;
	overflow-wrap: anywhere;
	color: var(--cloud-ink);
	font-size: 15px;
	font-weight: 600;
	line-height: 1.45;
}
.storage-card-identity > p {
	margin: 3px 0 0;
	color: var(--cloud-muted);
	font-size: 12px;
	line-height: 1.4;
}
.storage-card-workspace,
.storage-card-remark {
	margin: 10px 0 0;
	overflow-wrap: anywhere;
	color: var(--cloud-muted);
	font-size: 12px;
	line-height: 1.6;
}
.storage-card-status {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 7px;
	margin-top: 12px;
	min-height: 24px;
}
.storage-availability,
.storage-pending-label {
	color: var(--cloud-muted);
	font-size: 12px;
}
.storage-token-details {
	margin-top: 10px;
	font-size: 12px;
	color: var(--cloud-muted);
}
.storage-token-details summary {
	cursor: pointer;
	padding: 3px 0;
}
.storage-token-details p {
	margin: 6px 0 0;
	overflow-wrap: anywhere;
}
.storage-open-button {
	display: grid;
	place-items: center;
	flex: 0 0 36px;
	width: 36px;
	height: 36px;
	border: 1px solid var(--cloud-line);
	border-radius: 9px;
	color: var(--cloud-muted);
}
.storage-open-button svg {
	width: 17px;
	height: 17px;
}
.storage-card-actions {
	display: flex;
	border-top: 1px solid var(--cloud-line);
}
.storage-card-actions > :not(:first-child) {
	border-left: 1px solid var(--cloud-line);
}
.storage-card-action {
	display: inline-flex;
	flex: 1;
	justify-content: center;
	align-items: center;
	gap: 6px;
	width: 100%;
	min-height: 44px;
	height: auto;
	margin: 0;
	padding: 8px;
	border-radius: 0;
	color: var(--cloud-ink);
	font-size: 13px;
}
.storage-card-action svg {
	width: 17px;
	height: 17px;
}
.storage-more-menu {
	display: flex;
	flex: 1;
	min-width: 0;
}
.storage-access-note {
	margin: 0;
	padding: 6px 16px 9px;
	color: var(--cloud-muted);
	font-size: 12px;
	border-top: 1px solid var(--cloud-line);
}
.storage-add-button {
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 10px;
	width: 100%;
	min-height: 174px;
	padding: 20px;
	color: var(--cloud-muted);
	font-size: 14px;
}
.storage-add-button svg {
	width: 26px;
	height: 26px;
}
.storage-status-filters {
	display: flex;
	gap: 8px;
	flex-wrap: wrap;
	margin: 8px 0 12px;
}
.storage-status-filters button {
	display: inline-flex;
	align-items: center;
	gap: 8px;
	min-height: 36px;
	padding: 7px 13px;
	border: 1px solid var(--cloud-line);
	border-radius: 999px;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
	font-size: 13px;
}
.storage-status-filters button[aria-pressed='true'] {
	background: var(--cloud-primary);
	border-color: var(--cloud-primary);
	color: var(--cloud-surface);
}
.storage-status-filters button span {
	font-size: 12px;
	font-variant-numeric: tabular-nums;
	opacity: 0.85;
}
.storage-results-summary {
	display: flex;
	justify-content: space-between;
	align-items: center;
	flex-wrap: wrap;
	gap: 10px;
	margin-bottom: 16px;
	color: var(--cloud-muted);
	font-size: 12px;
}
.storage-results-summary p {
	margin: 0;
}
.storage-results-summary button {
	min-height: 32px;
	padding: 4px 8px;
	border-radius: 8px;
	color: var(--cloud-primary);
	text-decoration: underline;
	text-underline-offset: 3px;
}
.storage-list-empty {
	padding: 34px 20px;
	margin-bottom: 16px;
	border: 1px dashed var(--cloud-line);
	border-radius: 12px;
	background: var(--cloud-soft);
	text-align: center;
}
.storage-list-empty h2 {
	margin: 0 0 8px;
	font-size: 17px;
	font-weight: 600;
}
.storage-list-empty p {
	margin: 0 0 18px;
	color: var(--cloud-muted);
	font-size: 13px;
}
.storage-card-action:not(:disabled):hover,
.storage-add-button:not(:disabled):hover,
.storage-open-button:not(:disabled):hover,
.storage-search-clear:hover,
.storage-status-filters button:not(:disabled):hover:not([aria-pressed='true']) {
	background: var(--cloud-lilac);
	color: var(--cloud-ink);
}
.storage-list-page button:not(:disabled) {
	cursor: pointer;
}
.storage-card-action:not(:disabled):active,
.storage-add-button:not(:disabled):active,
.storage-open-button:not(:disabled):active,
.storage-search-clear:active,
.storage-status-filters button:not(:disabled):active {
	background: var(--cloud-soft);
	color: var(--cloud-ink);
}
.storage-list-page button:disabled {
	opacity: 0.6;
	cursor: not-allowed;
}
.storage-list-page button:focus-visible,
.storage-token-details summary:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: -3px;
}
.storage-list-notice,
.storage-list-progress {
	margin: 0 0 16px;
	font-size: 13px;
	line-height: 1.6;
}
.storage-list-notice {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 12px;
	padding: 12px 16px;
	border: 1px solid var(--el-color-warning-light-5);
	border-radius: 8px;
	background: var(--el-color-warning-light-9);
	color: var(--el-color-warning-dark-2);
}
.storage-list-notice small {
	flex-basis: 100%;
}
.storage-list-progress {
	color: var(--cloud-muted);
}
@media (max-width: 640px) {
	.storage-list-tools {
		width: 100%;
		flex-wrap: wrap;
	}
	.storage-list-tools .el-input {
		width: 100%;
	}
	.storage-container {
		grid-template-columns: 1fr;
	}
	.storage-add-button {
		min-height: 88px;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 存储源管理
</route>
