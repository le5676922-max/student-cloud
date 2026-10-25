<template>
	<div class="storage-list-page">
		<admin-form-header title="存储源管理">
			<template #subTitle>
				<div class="flex justify-between items-center flex-wrap">
					<div>统一维护云盘连接。状态仅表示配置，不代表实时连通性。</div>
					<div class="storage-list-tools">
						<el-input
							v-model="searchKey"
							placeholder="搜索名称、空间、备注或存储类型"
							clearable
							aria-label="搜索存储源"
							:prefix-icon="MagnifyingGlassIcon"
						></el-input>
						<el-button :loading="loading" :disabled="sorting" @click="init">
							刷新
						</el-button>
						<el-button type="primary" :disabled="sorting" @click="addStorage">
							<PlusIcon aria-hidden="true" />添加存储源
						</el-button>
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
				<span v-if="hasStorageFilters"> · 筛选期间暂停拖动排序</span>
				<span v-else> · 拖动卡片调整顺序</span>
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
			<span>{{ loadError }}</span>
			<el-button :disabled="sorting" @click="init">重新加载</el-button>
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
			:aria-busy="loading || sorting"
			class="storage-container grid grid-cols-1 gap-6 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 3xl:grid-cols-5"
		>
			<li
				v-for="storage in storageListSearchResult"
				:key="storage.id"
				class="storage-item group col-span-1 flex flex-col text-center bg-white rounded-lg border hover:shadow"
				@dblclick="editStorage(storage)"
			>
				<div class="flex-1 flex flex-col p-8 pb-4 relative">
					<button
						type="button"
						class="storage-open-button"
						:aria-label="`在新窗口打开 ${storage.name}`"
						title="在新窗口打开云盘"
						@click.stop="openStorage(storage.key)"
						@dblclick.stop
					>
						<i-material-symbols-open-in-new aria-hidden="true" />
					</button>

					<div class="storage-icon-disc rounded-full w-fit mx-auto">
						<img
							class="w-20 h-20 flex-shrink-0 mx-auto p-4"
							:src="getStorageIcon(storage.type?.key)"
							:alt="storage.type?.description || '存储源'"
						/>
					</div>
					<h3
						class="storage-card-name mt-2 text-gray-900 text-sm font-medium"
						:title="storage.name"
					>
						{{ storage.name }}
					</h3>
					<p v-if="isGlobalAdmin" class="text-xs text-gray-500 mt-1">
						{{
							storage.tenantName || `工作空间 ${storage.tenantId ?? '未分配'}`
						}}
					</p>
					<dl class="mt-1 flex-grow flex flex-col justify-between">
						<dd class="text-gray-500 text-sm line-clamp-1">
							{{ storage.remark }}
						</dd>
						<dd class="mt-2 space-x-1">
							<el-tag type="primary">{{
								storage.type?.description || '未知类型'
							}}</el-tag>
							<el-tag
								v-show="storage.enable"
								type="success"
								@click="switchEnableStatus(storage)"
								>启用</el-tag
							>
							<el-tag
								v-show="!storage.enable"
								type="danger"
								@click="switchEnableStatus(storage)"
								>停用</el-tag
							>
							<el-tag v-show="storage.enableCache" type="success">缓存</el-tag>

							<el-popover placement="top" :width="250" trigger="hover">
								<div>
									<div class="text-sm font-medium">
										<i-clarity-success-standard-solid
											class="inline text-green-500"
										/>
										刷新令牌成功
									</div>
									<div class="text-xs text-gray-500">
										上次刷新时间:
										{{ storage?.refreshTokenInfo?.lastRefreshTime }}
									</div>
									<div class="text-xs text-gray-500">
										令牌到期时间:
										{{ storage?.refreshTokenInfo?.data?.expiredAtDate }}
									</div>
								</div>
								<template #reference>
									<el-tag
										v-show="storage?.refreshTokenInfo?.success"
										type="success"
										>刷新令牌成功</el-tag
									>
								</template>
							</el-popover>
							<el-popover placement="top" :width="250" trigger="hover">
								<div>
									<div class="text-sm font-medium">
										<i-clarity-error-standard-solid
											class="inline text-red-500"
										/>
										刷新令牌失败
									</div>
									<div class="text-xs text-gray-500">
										上次刷新时间:
										{{ storage?.refreshTokenInfo?.lastRefreshTime }}
									</div>
									<div class="text-xs text-red-500">
										失败信息: {{ storage?.refreshTokenInfo?.msg }}
									</div>
								</div>
								<template #reference>
									<el-tag
										v-show="storage?.refreshTokenInfo?.success === false"
										type="danger"
										>刷新令牌失败</el-tag
									>
								</template>
							</el-popover>
						</dd>
					</dl>
				</div>
				<div class="-mt-px border-t flex divide-x divide-gray-200">
					<button
						type="button"
						class="storage-card-action"
						:disabled="!isGlobalAdmin && storage.type?.key === 'local'"
						:aria-label="`编辑 ${storage.name}`"
						@click.stop="editStorage(storage)"
						@dblclick.stop
					>
						<i-mdi-rename-outline aria-hidden="true" /><span>编辑</span>
					</button>
					<el-dropdown
						trigger="click"
						class="cursor-pointer flex-1"
						@command="handleOperator"
					>
						<button
							type="button"
							class="storage-card-action"
							:aria-label="`${storage.name} 的更多操作`"
							aria-haspopup="menu"
							@dblclick.stop
						>
							<i-material-symbols-more-horiz aria-hidden="true" /><span
								>更多</span
							>
						</button>

						<template #dropdown>
							<el-dropdown-menu>
								<el-dropdown-item
									v-if="isGlobalAdmin || storage.type?.key !== 'local'"
									:icon="PencilIcon"
									:command="{ operator: 'edit', storage }"
									>编辑</el-dropdown-item
								>
								<el-dropdown-item
									v-if="!storage.enable"
									:icon="PlayCircleIcon"
									:command="{ operator: 'enable', storage }"
									>启用</el-dropdown-item
								>
								<el-dropdown-item
									v-else
									:icon="PauseCircleIcon"
									:command="{ operator: 'enable', storage }"
									>停用</el-dropdown-item
								>
								<div class="divider"></div>
								<el-dropdown-item
									v-if="isGlobalAdmin"
									:icon="LockOpenIcon"
									:command="{ operator: 'permissionManager', storage }"
								>
									<span class="line-through">权限管理</span>
								</el-dropdown-item>
								<div class="divider"></div>
								<!--<el-dropdown-item :icon="Operation" :command="{operator: 'cacheManager', storage}">缓存管理</el-dropdown-item>-->
								<el-dropdown-item
									v-if="isGlobalAdmin"
									:icon="DocumentTextIcon"
									:command="{ operator: 'readmeManager', storage }"
									>目录文档</el-dropdown-item
								>
								<el-dropdown-item
									v-if="isGlobalAdmin"
									:icon="EyeSlashIcon"
									:command="{ operator: 'filterManager', storage }"
									>文件过滤</el-dropdown-item
								>
								<el-dropdown-item
									v-if="isGlobalAdmin"
									:icon="KeyIcon"
									:command="{ operator: 'pwdManager', storage }"
									>密码设置</el-dropdown-item
								>
								<div class="divider"></div>
								<el-dropdown-item
									v-if="isGlobalAdmin || storage.type?.key !== 'local'"
									:icon="ClipboardDocumentIcon"
									:command="{ operator: 'copy', storage }"
									>复制存储源</el-dropdown-item
								>
								<div class="divider"></div>
								<el-dropdown-item
									:icon="TrashIcon"
									:command="{ operator: 'delete', storage }"
									>删除</el-dropdown-item
								>
							</el-dropdown-menu>
						</template>
					</el-dropdown>
				</div>
			</li>
			<li
				v-if="!emptyState"
				class="add-storage-btn cursor-pointer col-span-1 flex flex-col text-center bg-white rounded-lg border hover:shadow"
			>
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

let router = useRouter()
const {
	init,
	loading,
	sorting,
	loadError,
	operationError,
	storageContainerRef,
	searchKey,
	statusFilter,
	statusOptions,
	hasStorageFilters,
	hasLoaded,
	emptyState,
	clearFilters,
	showingPreviousData,
	storageListSearchResult,
	handleOperator,
	editStorage,
	addStorage,
	switchEnableStatus,
} = useStorageList(router)

onMounted(() => {
	init()
})

const openStorage = (key) => {
	window.open(`/${key}`, '_blank', 'noopener,noreferrer')
}
</script>

<style scoped>
.storage-container li {
	min-height: 288px;
	border-radius: 18px;
	border-color: var(--cloud-line);
}
.storage-icon-disc {
	background: var(--cloud-soft);
}
.storage-card-name {
	display: -webkit-box;
	-webkit-box-orient: vertical;
	-webkit-line-clamp: 2;
	overflow: hidden;
	overflow-wrap: anywhere;
}

.storage-list-tools {
	display: flex;
	gap: 8px;
	align-items: center;
}
.storage-list-tools .el-input {
	width: min(280px, 100%);
}
.storage-list-tools > .el-button svg {
	width: 16px;
	height: 16px;
	margin-right: 5px;
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
	color: #fff;
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
	border-radius: 18px;
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
.storage-open-button {
	position: absolute;
	top: 10px;
	left: 10px;
	display: grid;
	place-items: center;
	width: 32px;
	height: 32px;
	border: 1px solid var(--cloud-line);
	border-radius: 10px;
	background: var(--cloud-surface);
	color: var(--cloud-muted);
}
.storage-open-button svg {
	width: 16px;
	height: 16px;
}
.storage-card-action {
	display: inline-flex;
	flex: 1;
	justify-content: center;
	align-items: center;
	gap: 7px;
	width: 100%;
	min-height: 40px;
	padding: 8px;
	color: var(--cloud-ink);
	font-size: 13px;
}
.storage-card-action svg {
	width: 17px;
	height: 17px;
}
.storage-add-button {
	display: flex;
	flex: 1;
	align-items: center;
	justify-content: center;
	flex-direction: column;
	gap: 12px;
	width: 100%;
	border-radius: inherit;
	color: var(--cloud-muted);
	font-size: 14px;
}
.storage-add-button svg {
	width: 38px;
	height: 38px;
}
.storage-card-action:hover,
.storage-add-button:not(:disabled):hover,
.storage-open-button:hover,
.storage-status-filters button:not(:disabled):hover:not([aria-pressed='true']) {
	background: var(--cloud-lilac);
	color: var(--cloud-ink);
}
.storage-list-page button:disabled {
	opacity: 0.6;
	cursor: not-allowed;
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
	border: 1px solid #e6d8c7;
	border-radius: 8px;
	background: #fcf7ee;
	color: #795b39;
}
.storage-list-notice small {
	flex-basis: 100%;
}

.storage-list-progress {
	color: var(--cloud-muted, #6d7974);
}

@media (max-width: 640px) {
	.storage-list-tools {
		width: 100%;
		flex-wrap: wrap;
		margin-top: 8px;
	}
	.storage-list-tools .el-input {
		width: 100%;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 存储源管理
</route>
