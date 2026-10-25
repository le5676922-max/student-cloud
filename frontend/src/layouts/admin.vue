<template>
	<div class="admin-shell h-full">
		<a class="admin-skip-link" href="#admin-page-content">跳到页面内容</a>
		<TransitionRoot as="template" :show="sidebarOpen">
			<Dialog
				as="div"
				class="admin-mobile-dialog fixed inset-0 z-50 lg:hidden"
				@close="sidebarOpen = false"
			>
				<TransitionChild
					as="template"
					enter="transition-opacity duration-200"
					enter-from="opacity-0"
					enter-to="opacity-100"
					leave="transition-opacity duration-200"
					leave-from="opacity-100"
					leave-to="opacity-0"
					><div class="fixed inset-0 bg-gray-900/60"
				/></TransitionChild>
				<div class="fixed inset-0 flex">
					<DialogPanel class="relative mr-16 flex w-full max-w-xs flex-1">
						<button
							type="button"
							class="absolute left-full top-4 ml-3 p-2 text-white"
							@click="sidebarOpen = false"
						>
							<span class="sr-only">关闭管理菜单</span
							><XMarkIcon class="h-6 w-6" aria-hidden="true" />
						</button>
						<div
							class="admin-mobile-sidebar admin-sidebar flex grow flex-col overflow-y-auto"
						>
							<DialogTitle class="admin-brand flex shrink-0 items-center"
								><BrandLogo compact /><span class="sr-only"
									>全局管理菜单</span
								></DialogTitle
							>
							<AdminNavigation
								v-if="isAdmin"
								instance-id="mobile-admin"
								:path="route.path"
								:expanded="expandedGroups"
								:logging-out="loggingOut"
								@toggle="toggleGroup"
								@action="handleAction"
							/>
							<nav
								v-else-if="isWorkspaceStorageAdmin"
								class="p-5 grid gap-4"
								aria-label="工作空间存储管理"
							>
								<router-link to="/workspace-admin">工作空间管理</router-link>
								<router-link to="/admin/storage-list">我的存储源</router-link>
								<router-link to="/">返回云盘文件</router-link>
							</nav>
						</div>
					</DialogPanel>
				</div>
			</Dialog>
		</TransitionRoot>
		<div
			class="admin-desktop-sidebar hidden lg:fixed lg:inset-y-0 lg:z-auto lg:flex lg:flex-col"
		>
			<div class="admin-sidebar flex grow flex-col overflow-y-auto border-r">
				<div class="admin-brand flex shrink-0 items-center gap-2">
					<router-link to="/" aria-label="学生云盘首页"
						><BrandLogo compact
					/></router-link>
				</div>
				<AdminNavigation
					v-if="isAdmin"
					instance-id="desktop-admin"
					:path="route.path"
					:expanded="expandedGroups"
					:logging-out="loggingOut"
					@toggle="toggleGroup"
					@action="handleAction"
				/>
				<nav
					v-else-if="isWorkspaceStorageAdmin"
					class="p-5 grid gap-4"
					aria-label="工作空间存储管理"
				>
					<router-link to="/workspace-admin">工作空间管理</router-link>
					<router-link to="/admin/storage-list">我的存储源</router-link>
					<router-link to="/">返回云盘文件</router-link>
				</nav>
				<p class="admin-sidebar-version">学生云盘 · v{{ constant.version }}</p>
			</div>
		</div>
		<div
			class="admin-mobile-header sticky top-0 flex items-center gap-x-6 px-4 py-3 sm:px-6 lg:hidden"
		>
			<button
				type="button"
				class="-m-2.5 p-2.5 text-gray-700"
				:aria-expanded="sidebarOpen"
				aria-haspopup="dialog"
				@click="sidebarOpen = true"
			>
				<span class="sr-only">打开管理菜单</span
				><Bars3Icon class="h-6 w-6" aria-hidden="true" />
			</button>
			<router-link to="/" class="mx-auto" aria-label="学生云盘首页"
				><BrandLogo compact
			/></router-link>
		</div>
		<main class="admin-main-content h-full overflow-y-auto">
			<header class="admin-page-header">
				<nav class="admin-breadcrumb" aria-label="当前页面位置">
					<router-link :to="isAdmin ? '/admin/overview' : '/workspace-admin'">{{
						isAdmin ? '管理控制台' : '工作空间管理'
					}}</router-link>
					<span aria-hidden="true">/</span>
					<span aria-current="page">{{ route.meta.name || '全局管理' }}</span>
				</nav>
				<span class="admin-scope-label"
					><ShieldCheckIcon aria-hidden="true" />{{
						isAdmin ? '全局管理员' : '空间管理员'
					}}</span
				>
			</header>
			<div
				class="admin-content-panel"
				:class="{ 'admin-overview-panel': route.path === '/admin/overview' }"
			>
				<div id="admin-page-content" class="z-admin-body-wrapper" tabindex="-1">
					<router-view :key="adminIdentityKey" />
				</div>
			</div>
		</main>
	</div>
</template>
<script setup>
import {
	Dialog,
	DialogPanel,
	DialogTitle,
	TransitionChild,
	TransitionRoot,
} from '@headlessui/vue'
import {
	XMarkIcon,
	Bars3Icon,
	ShieldCheckIcon,
} from '@heroicons/vue/24/outline'
import BrandLogo from '~/components/BrandLogo.vue'
import AdminNavigation from '~/components/admin/AdminNavigation.vue'
import useAdminLayout from '~/composables/admin/layout/admin-layout'
import useStorageConfigStore from '~/stores/storage-config'
import { logoutReq } from '~/api/home/user'
import { loadConfigReq } from '~/api/admin/admin-setting'
import { canAccessAdministration } from '~/utils/roles.mjs'
import { isWorkspaceStorageRoute } from '~/utils/auth-navigation.mjs'
import {
	adminNavigationExpansionForPath,
	toggleAdminNavigationGroup,
} from '~/utils/admin-navigation.mjs'
const router = useRouter()
const route = useRoute()
const { rebuildTitle, logDownload } = useAdminLayout(router, route)
const storageConfigStore = useStorageConfigStore()
const isAdmin = computed(() =>
	canAccessAdministration(storageConfigStore.loginInfo)
)
const isWorkspaceStorageAdmin = computed(
	() =>
		storageConfigStore.loginInfo.role === 'TENANT_ADMIN' &&
		isWorkspaceStorageRoute(route.path)
)
const adminIdentityKey = computed(() =>
	JSON.stringify([
		storageConfigStore.loginInfo.accountId,
		storageConfigStore.loginInfo.username,
		storageConfigStore.loginInfo.role,
		storageConfigStore.loginInfo.tenantId,
	])
)
const sidebarOpen = ref(false)
const expandedGroups = ref([])
const loggingOut = ref(false)
const toggleGroup = (id) => {
	expandedGroups.value = toggleAdminNavigationGroup(expandedGroups.value, id)
}
const enforceAdminRoute = () => {
	if (
		storageConfigStore.loginInfo.isLogin !== null &&
		!isAdmin.value &&
		!isWorkspaceStorageAdmin.value
	)
		router.replace('/')
}
// The route guard has already checked the current identity. Do not check twice
// or read all site settings every time a management page mounts.
watch(
	() => route.path,
	() => {
		rebuildTitle()
		enforceAdminRoute()
		sidebarOpen.value = false
		expandedGroups.value = adminNavigationExpansionForPath(route.path)
	},
	{ immediate: true }
)
async function handleAction(action) {
	if (action === 'download-log') return logDownload()
	if (action !== 'logout' || loggingOut.value) return
	loggingOut.value = true
	let secureEntry
	try {
		secureEntry = (
			await loadConfigReq({ timeout: 5000, showDefaultMsg: false })
		).data.secureLoginEntry
	} catch {
		/* Keep the known login entry when configuration is unavailable. */
	}
	try {
		await logoutReq()
	} catch {
		/* Always clear this browser's authentication on explicit logout. */
	} finally {
		window.localStorage.removeItem('tongligong-student-cloud-token')
		storageConfigStore.clearLoginInfo()
		loggingOut.value = false
		router.replace(secureEntry ? '/login/' + secureEntry : '/login')
	}
}
</script>
