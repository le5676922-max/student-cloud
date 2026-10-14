<template>
	<div class="storage-list-page">
		<admin-form-header title="存储源信息">
			<template #subTitle>
				<div class="flex justify-between items-center flex-wrap">
					<div>维护存储源连接，拖动卡片调整显示顺序。筛选时暂停排序。</div>
					<div class="storage-list-tools">
						<el-input
							v-model="searchKey"
							placeholder="请输入搜索内容"
							clearable
							aria-label="搜索存储源"
							:prefix-icon="MagnifyingGlassIcon"
						></el-input>
						<el-button :loading="loading" :disabled="sorting" @click="init">
							刷新
						</el-button>
					</div>
				</div>
			</template>
		</admin-form-header>
		<div v-if="loadError" class="storage-list-notice" role="alert">
			<span>{{ loadError }}</span>
			<el-button :disabled="sorting" @click="init">重新加载</el-button>
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
		<p
			v-if="
				!loading &&
				!loadError &&
				searchKey.trim() &&
				!storageListSearchResult.length
			"
			class="storage-list-progress"
		>
			没有匹配的存储源，请尝试其他名称或清空筛选。
		</p>

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
					<i-material-symbols-open-in-new
						class="absolute left-3 top-3 text-sm cursor-pointer text-blue-500 opacity-0 group-hover:opacity-100"
						@click="openStorage(storage.key)"
					/>
					<i-solar-trash-bin-minimalistic-bold
						name="delete"
						class="absolute right-3 top-3 cursor-pointer group-hover:text-red-500"
						@click="deleteStorage(storage)"
					/>

					<div class="rounded-full bg-blue-50 w-fit mx-auto">
						<img
							class="w-20 h-20 flex-shrink-0 mx-auto p-4"
							:src="getStorageIcon(storage.type?.key)"
							:alt="storage.type?.description || '存储源'"
						/>
					</div>
					<h3 class="mt-2 text-gray-900 text-sm font-medium">
						{{ storage.name }}
					</h3>
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
					<div
						class="w-0 flex-1 flex cursor-pointer"
						@click="editStorage(storage)"
					>
						<span
							class="relative -mr-px w-0 flex-1 inline-flex items-center justify-center py-2 text-sm text-gray-700 font-medium border border-transparent rounded-bl-lg hover:text-gray-500 ml-3"
						>
							<i-mdi-rename-outline class="mr-2" />
							<span>编辑</span>
						</span>
					</div>
					<el-dropdown class="cursor-pointer flex-1" @command="handleOperator">
						<div class="w-0 flex-1 flex cursor-pointer">
							<span
								class="relative -mr-px w-0 flex-1 inline-flex items-center justify-center py-2 text-sm text-gray-700 font-medium border border-transparent rounded-bl-lg hover:text-gray-500 ml-3"
							>
								<span class="text-sm font-medium">
									<i-material-symbols-more-horiz class="mr-2 inline" />
									<span>更多</span>
								</span>
							</span>
						</div>

						<template #dropdown>
							<el-dropdown-menu>
								<el-dropdown-item
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
									:icon="LockOpenIcon"
									:command="{ operator: 'permissionManager', storage }"
								>
									<span class="line-through">权限管理</span>
								</el-dropdown-item>
								<div class="divider"></div>
								<!--<el-dropdown-item :icon="Operation" :command="{operator: 'cacheManager', storage}">缓存管理</el-dropdown-item>-->
								<el-dropdown-item
									:icon="DocumentTextIcon"
									:command="{ operator: 'readmeManager', storage }"
									>目录文档</el-dropdown-item
								>
								<el-dropdown-item
									:icon="EyeSlashIcon"
									:command="{ operator: 'filterManager', storage }"
									>文件过滤</el-dropdown-item
								>
								<el-dropdown-item
									:icon="KeyIcon"
									:command="{ operator: 'pwdManager', storage }"
									>密码设置</el-dropdown-item
								>
								<div class="divider"></div>
								<el-dropdown-item
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
				class="add-storage-btn cursor-pointer col-span-1 flex flex-col text-center bg-white rounded-lg border hover:shadow"
				@click="addStorage"
			>
				<div class="flex-1 flex flex-col p-8 mx-auto justify-center">
					<PlusIcon class="h-20 text-gray-300" />
				</div>
			</li>
		</ul>
	</div>
	<storage-copy @close="init"></storage-copy>
</template>

<script setup>
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
	storageListSearchResult,
	handleOperator,
	editStorage,
	addStorage,
	deleteStorage,
	switchEnableStatus,
} = useStorageList(router)

onMounted(() => {
	init()
})

const openStorage = (key) => {
	window.open(`/${key}`)
}
</script>

<style scoped>
.storage-container li {
	@apply h-72;
}

.storage-list-tools {
	display: flex;
	gap: 8px;
	align-items: center;
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

.storage-list-progress {
	color: var(--cloud-muted, #6d7974);
}

@media (max-width: 640px) {
	.storage-list-tools {
		width: 100%;
		margin-top: 8px;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 存储源管理
</route>
