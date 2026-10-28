<template>
	<div class="student-cloud-header">
		<div class="student-cloud-header-breadcrumb box flex flex-1">
			<header-logo></header-logo>
			<Breadcrumb
				class="h-12"
				:items="breadcrumbData"
				@breadcrumb-click="clickBreadcrumb"
			>
			</Breadcrumb>
		</div>

		<div class="student-cloud-header-right box">
			<!-- 功能区 -->
			<div v-if="isNotMobile" class="student-cloud-header-btn">
				<!-- debug 模式 -->
				<template v-if="storageConfigStore.globalConfig.debugMode">
					<el-tooltip placement="bottom">
						<template #content>
							此功能为 DEBUG 模式下重置管理员密码功能, 使用完后请关闭 DEBUG
							模式并重启服务.
						</template>
						<el-button
							size="default"
							type="danger"
							@click="openResetAdminPwdDialog"
						>
							重置管理员密码
						</el-button>
					</el-tooltip>
				</template>

				<!-- 如果已登录，则显示 <el-dropdown> 下拉框，下拉中显示注销和修改密码 -->
				<el-dropdown
					v-if="storageConfigStore.loginInfo.isLogin"
					trigger="click"
					popper-class="student-cloud-header-dropdown"
				>
					<el-button
						native-type="button"
						class="header-account-button"
						aria-label="账户菜单"
						aria-haspopup="menu"
					>
						<span class="header-account-avatar" aria-hidden="true">{{
							accountInitial
						}}</span>
						<span class="header-account-name">{{ accountDisplayName }}</span>
						<ChevronDownIcon
							class="header-account-chevron"
							aria-hidden="true"
						/>
					</el-button>
					<template #dropdown>
						<el-dropdown-menu class="font-medium">
							<el-dropdown-item>
								<span class="text-gray-500 text-center mx-auto"
									>{{ storageConfigStore.loginInfo.username
									}}{{
										storageConfigStore.loginInfo.nickname
											? '(' + storageConfigStore.loginInfo.nickname + ')'
											: ''
									}}</span
								>
							</el-dropdown-item>
							<el-dropdown-item
								v-if="storageConfigStore.loginInfo.isAdmin"
								@click="toAdmin"
							>
								<i-mdi-account-settings-variant class="mr-3" />
								前往后台
							</el-dropdown-item>
							<el-dropdown-item
								v-if="storageConfigStore.loginInfo.role === 'TENANT_ADMIN'"
								@click="router.push('/workspace-admin')"
								>工作空间管理</el-dropdown-item
							>
							<el-dropdown-item
								v-if="
									['USER', 'TENANT_ADMIN'].includes(
										storageConfigStore.loginInfo.role
									)
								"
								@click="router.push('/courses')"
								>共享课程 / 邀请加入</el-dropdown-item
							>
							<el-dropdown-item
								v-if="
									['ADMIN', 'USER', 'TENANT_ADMIN'].includes(
										storageConfigStore.loginInfo.role
									)
								"
								@click="
									router.push(
										storageConfigStore.loginInfo.role === 'ADMIN'
											? '/admin/support'
											: '/support'
									)
								"
							>
								{{
									storageConfigStore.loginInfo.role === 'ADMIN'
										? '客服工作台'
										: '人工客服'
								}}
							</el-dropdown-item>
							<el-dropdown-item @click="openUpdatePwdDialog">
								<i-mdi-lock class="mr-3" />
								修改密码
							</el-dropdown-item>
							<el-dropdown-item @click="logout">
								<i-mdi-logout class="mr-3" />
								注销
							</el-dropdown-item>
						</el-dropdown-menu>
					</template>
				</el-dropdown>

				<!-- 登录账号 -->
				<el-tooltip
					v-else-if="storageConfigStore.globalConfig.showLogin"
					placement="bottom"
				>
					<template #content> 登录账号 </template>
					<button
						type="button"
						class="header-icon-button"
						aria-label="登录账号"
						@click="toLoginView"
					>
						<i-mdi-login-variant aria-hidden="true" />
					</button>
				</el-tooltip>

				<el-tooltip
					v-if="storageConfigStore.loginInfo.isLogin"
					placement="bottom"
				>
					<template #content> 我的分享 </template>
					<button
						type="button"
						class="header-icon-button"
						aria-label="我的分享"
						@click="openMyShare"
					>
						<i-mdi-share-variant aria-hidden="true" />
					</button>
				</el-tooltip>

				<!-- 上传 -->
				<el-button
					v-if="storageConfigStore.loginInfo.isLogin && isSharePage"
					text
					@click="router.push('/learning')"
					>课程资料</el-button
				>
				<el-dropdown
					v-if="
						route.params.storageKey &&
						(storageConfigStore.permission.upload ||
							storageConfigStore.permission.newFolder)
					"
					trigger="click"
					popper-class="student-cloud-header-dropdown"
				>
					<el-button
						v-show="route.params.storageKey"
						native-type="button"
						class="header-icon-button"
						aria-label="上传与新建菜单"
						aria-haspopup="menu"
					>
						<el-badge
							:value="uploadProgressInfoStatistics.totalUploadingAndWaiting"
							:hidden="
								uploadProgressInfoStatistics.totalUploadingAndWaiting === 0
							"
							:max="99"
							class="!block"
						>
							<CloudArrowUpIcon aria-hidden="true" />
						</el-badge>
					</el-button>
					<template #dropdown>
						<el-dropdown-menu class="font-medium">
							<el-dropdown-item
								v-if="storageConfigStore.permission.newFolder"
								@click="newFolder"
							>
								<i-lucide-folder-plus class="mr-3" />
								新建文件夹
							</el-dropdown-item>

							<el-dropdown-item
								v-if="storageConfigStore.permission.upload"
								:divided="
									storageConfigStore.permission.upload &&
									storageConfigStore.permission.newFolder
								"
								@click="openUploadDialog"
							>
								<i-ci-file-upload class="mr-3" />
								上传文件
							</el-dropdown-item>

							<el-dropdown-item
								v-if="storageConfigStore.permission.uploadFolder"
								@click="openUploadFolderDialog"
							>
								<i-ci-folder-upload class="mr-3" />
								上传文件夹
							</el-dropdown-item>
						</el-dropdown-menu>
					</template>
				</el-dropdown>

				<!-- 画廊模式切换 -->
				<button
					v-show="route.params.storageKey"
					type="button"
					class="header-icon-button"
					:class="{ 'is-active': fileDataStore.imgMode }"
					aria-label="画廊模式"
					:aria-pressed="fileDataStore.imgMode"
					@click="fileDataStore.imgMode = !fileDataStore.imgMode"
				>
					<i-solar-gallery-wide-linear aria-hidden="true" />
				</button>

				<!-- 设置按钮 -->
				<button
					type="button"
					class="header-icon-button"
					aria-label="显示设置"
					@click="openSettingVisible"
				>
					<Cog8ToothIcon aria-hidden="true" />
				</button>
			</div>

			<!-- 存储源选择（分享页不显示） -->
			<div
				v-if="isNotMobile && !isSharePage"
				class="student-cloud-header-storage-select"
			>
				<el-select
					v-model="currentStorageKey"
					size="default"
					placeholder="请选择存储源"
					aria-label="切换云盘"
				>
					<el-option
						v-for="item in storageList"
						:key="item.key"
						:label="item.name"
						:value="item.key"
					>
					</el-option>
				</el-select>
			</div>
		</div>

		<div v-if="isMobile" class="header-mobile-menu">
			<el-dropdown trigger="click" popper-class="student-cloud-header-dropdown">
				<el-button
					native-type="button"
					class="header-icon-button"
					aria-label="打开文件菜单"
					aria-haspopup="menu"
				>
					<Bars3Icon aria-hidden="true" />
				</el-button>
				<template #dropdown>
					<el-dropdown-menu>
						<el-dropdown-item
							v-if="storageConfigStore.globalConfig.debugMode"
							@click="openResetAdminPwdDialog"
						>
							<i-mdi-key class="w-4 h-4 mr-2 text-gray-500" name="key" />
							重置管理员密码
						</el-dropdown-item>
						<el-dropdown-item
							v-if="
								route.params.storageKey &&
								storageConfigStore.permission.newFolder
							"
							@click="newFolder"
						>
							<i-lucide-folder-plus
								class="w-4 h-4 mr-2 text-gray-500"
								name="add-folder"
							/>
							新建文件夹
						</el-dropdown-item>
						<el-dropdown-item
							v-if="
								route.params.storageKey && storageConfigStore.permission.upload
							"
							@click="openUploadDialog"
						>
							<i-ci-file-upload
								class="w-4 h-4 mr-2 text-gray-500"
								name="upload"
							/>
							上传文件
						</el-dropdown-item>
						<el-dropdown-item
							v-if="
								route.params.storageKey &&
								storageConfigStore.permission.uploadFolder
							"
							@click="openUploadFolderDialog"
						>
							<i-ci-folder-upload
								class="w-4 h-4 mr-2 text-gray-500"
								name="upload-folder"
							/>
							上传文件夹
						</el-dropdown-item>
						<el-dropdown-item
							v-if="route.params.storageKey && !fileDataStore.imgMode"
							@click="fileDataStore.imgMode = true"
						>
							<i-ri-image-line
								class="w-4 h-4 mr-2 text-gray-500"
								name="image"
							/>
							打开画廊模式
						</el-dropdown-item>
						<el-dropdown-item
							v-else-if="route.params.storageKey && fileDataStore.imgMode"
							@click="fileDataStore.imgMode = false"
						>
							<i-ri-image-line
								class="w-4 h-4 mr-2 text-blue-500"
								name="image"
							/>
							关闭画廊模式
						</el-dropdown-item>
						<el-dropdown-item @click="openSettingVisible">
							<Cog8ToothIcon class="w-4 h-4 mr-2 text-gray-500"></Cog8ToothIcon>
							更多设置
						</el-dropdown-item>

						<div class="border-gray-200 border-t"></div>

						<template v-if="storageConfigStore.loginInfo.isLogin">
							<el-dropdown-item
								v-if="storageConfigStore.loginInfo.role === 'USER'"
								@click="router.push('/cloud-access')"
								>申请云盘 / 查看容量</el-dropdown-item
							>
							<el-dropdown-item
								v-if="
									['USER', 'TENANT_ADMIN'].includes(
										storageConfigStore.loginInfo.role
									)
								"
								@click="router.push('/courses')"
								>共享课程 / 邀请加入</el-dropdown-item
							>
							<el-dropdown-item @click="router.push('/learning')"
								>个人资料库</el-dropdown-item
							>
							<el-dropdown-item @click="openMyShare">
								<i-mdi-share-variant
									class="w-4 h-4 mr-2 text-gray-500"
									name="share"
								/>
								我的分享
							</el-dropdown-item>
							<el-dropdown-item
								v-if="storageConfigStore.loginInfo.isAdmin"
								@click="toAdmin"
							>
								<i-mdi-account-settings-variant
									class="w-4 h-4 mr-2 text-gray-500"
									name="admin"
								/>
								前往后台
							</el-dropdown-item>
							<el-dropdown-item
								v-if="storageConfigStore.loginInfo.role === 'TENANT_ADMIN'"
								@click="router.push('/workspace-admin')"
								>工作空间管理</el-dropdown-item
							>
							<el-dropdown-item
								v-if="
									['ADMIN', 'USER', 'TENANT_ADMIN'].includes(
										storageConfigStore.loginInfo.role
									)
								"
								@click="
									router.push(
										storageConfigStore.loginInfo.role === 'ADMIN'
											? '/admin/support'
											: '/support'
									)
								"
							>
								{{
									storageConfigStore.loginInfo.role === 'ADMIN'
										? '客服工作台'
										: '人工客服'
								}}
							</el-dropdown-item>
							<el-dropdown-item @click="openUpdatePwdDialog">
								<i-mdi-lock class="w-4 h-4 mr-2 text-gray-500" name="lock" />
								修改密码
							</el-dropdown-item>
							<el-dropdown-item @click="logout">
								<i-mdi-logout
									class="w-4 h-4 mr-2 text-gray-500"
									name="logout"
								/>
								注销
							</el-dropdown-item>
						</template>
						<el-dropdown-item
							v-else-if="storageConfigStore.globalConfig.showLogin"
							@click="toLoginView"
						>
							<i-mdi-login-variant
								class="w-4 h-4 mr-2 text-gray-500"
								name="login"
							/>
							登录账号
						</el-dropdown-item>
					</el-dropdown-menu>
				</template>
			</el-dropdown>
		</div>
	</div>
	<Setting></Setting>

	<update-pwd ref="updatePwdDialogRef" />
	<update-user-name-and-pwd ref="resetAdminPwdDialogRef" />
	<MyShareDialog
		v-model:visible="myShareDialogVisible"
		:storage-list="storageList"
		:active-storage-key="shareActiveStorageKey"
	/>
</template>

<script setup>
import {
	Bars3Icon,
	Cog8ToothIcon,
	CloudArrowUpIcon,
	ChevronDownIcon,
} from '@heroicons/vue/24/outline'

let router = useRouter()
let route = useRoute()

import { isMobile, isNotMobile } from '~/utils'

// 存储源列表.
import useHeaderStorageList from '~/composables/header/useHeaderStorageList'
const { loadStorageSourceList, currentStorageKey, storageList } =
	useHeaderStorageList()

// 面包屑数据和操作
import useBreadcrumb from '~/composables/header/useHeaderBreadcrumb'
const { buildBreadcrumbData, breadcrumbData } = useBreadcrumb()

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()
const accountDisplayName = computed(
	() =>
		String(storageConfigStore.loginInfo.nickname || '').trim() ||
		String(storageConfigStore.loginInfo.username || '').trim() ||
		'我的账号'
)
const accountInitial = computed(
	() => Array.from(accountDisplayName.value.trim())[0]?.toUpperCase() || '我'
)

import useFileDataStore from '~/stores/file-data'
let fileDataStore = useFileDataStore()

onMounted(async () => {
	try {
		await loadStorageSourceList()
	} catch {
		// The loader clears stale storage state and the request layer shows errors.
	}
	buildBreadcrumbData()
})

const updatePwdDialogRef = ref(null)
const openUpdatePwdDialog = () => {
	updatePwdDialogRef.value.openDialog()
}

const resetAdminPwdDialogRef = ref(null)
const openResetAdminPwdDialog = () => {
	resetAdminPwdDialogRef.value.openDialog()
}

const clickBreadcrumb = (item) => {
	if (item.href) {
		router.push(item.href)
	}
}

watch(
	() => route.params.storageKey,
	(value) => {
		if (value === undefined) {
			currentStorageKey.value = ''
			fileDataStore.imgMode = false
		}
	}
)

import useFileOperator from '~/composables/file/useFileOperator'
const { newFolder } = useFileOperator()

import useFileUpload from '~/composables/file/useFileUpload'
const {
	openUploadDialog,
	openUploadFolderDialog,
	uploadProgressInfoStatistics,
} = useFileUpload()

import useSetting from '~/composables/header/useSetting'
const { openSettingVisible } = useSetting()

// 监听存储源设置 -> 默认打开图片模式, 如果为是, 则打开图片模式.
watch(
	() => [
		storageConfigStore.folderConfig.defaultSwitchToImgMode,
		fileDataStore.oldStorageKey,
	],
	(val, oldValue) => {
		let defaultSwitchToImgMode = val[0]
		let storageKey = val[1]
		let oldStorageKey = oldValue[1]

		if (storageKey !== oldStorageKey) {
			fileDataStore.imgMode = defaultSwitchToImgMode
		}
	}
)

const toLoginView = () => {
	window.location.href = '/login'
}

// 自定义 css js 功能.
import { useStyleTag } from '@vueuse/core'

if (storageConfigStore.globalConfig.customCss) {
	try {
		useStyleTag(storageConfigStore.globalConfig.customCss)
	} catch (e) {
		console.error(
			'加载自定义 css 加载失败:',
			storageConfigStore.globalConfig.customCss,
			e
		)
	}
}
import HeaderLogo from '~/components/file/HeaderLogo.vue'
import { logoutReq } from '~/api/home/user'
import UpdateUserNameAndPwd from '~/components/user/UpdateUserNameAndPwd.vue'
const MyShareDialog = defineAsyncComponent(() =>
	import('~/components/share/MyShareDialog.vue')
)

const loadScriptDom = (scriptDom) => {
	if (scriptDom) {
		document.getElementsByTagName('head')[0].appendChild(scriptDom)
		console.log(`加载自定义 js, src:${scriptDom.src}, text:${scriptDom.text}`)
	}
}

const loadScriptText = (scriptText) => {
	if (scriptText && scriptText.trim()) {
		let scriptDom = document.createElement('script')
		scriptDom.type = 'text/javascript'
		scriptDom.text = scriptText
		loadScriptDom(scriptDom)
	}
}

onMounted(() => {
	nextTick(() => {
		if (storageConfigStore.globalConfig.customJs) {
			// 创建一个新的 div 元素
			const tempDivDom = document.createElement('div')
			// 将字符串作为 HTML 插入到新的 div 中
			tempDivDom.innerHTML = storageConfigStore.globalConfig.customJs
			// 遍历新 div 中的所有 script 元素
			Array.from(tempDivDom.getElementsByTagName('script')).forEach(
				(script) => {
					// 如果该 script 元素有 src 属性，则动态引入外部 JavaScript
					if (script.src) {
						const newScript = document.createElement('script')
						for (let i = 0; i < script.attributes.length; i++) {
							const attr = script.attributes[i]
							newScript.setAttribute(attr.name, attr.value)
						}
						loadScriptDom(newScript)
					} else {
						// 否则，执行内联的 JavaScript 代码
						loadScriptText(script.innerHTML)
					}
					tempDivDom.removeChild(script)
				}
			)
			loadScriptText(tempDivDom.innerHTML)
		}
	})
})

// The router has already checked this navigation. A second request here both
// delays the file header and can overwrite a newer account with a late reply.

const logout = () => {
	logoutReq().finally(() => {
		window.localStorage.removeItem('tongligong-student-cloud-token')
		storageConfigStore.clearLoginInfo()
		router.replace('/login')
	})
}

const toAdmin = () => {
	router.push('/admin/overview')
}

const isSharePage = computed(() => !!route.params.shareKey)
const shareActiveStorageKey = computed(() =>
	route.params.storageKey ? String(route.params.storageKey) : ''
)
const myShareDialogVisible = ref(false)
const openMyShare = () => {
	myShareDialogVisible.value = true
}
</script>

<style scoped lang="scss">
.student-cloud-header {
	display: flex;
	flex-flow: row nowrap;
	justify-content: space-between;
	align-items: center;
	gap: 16px;
	height: 72px;
	min-height: 72px;
	line-height: 1.4;
	padding: 0 clamp(14px, 2vw, 30px);

	background: var(--cloud-surface);
	color: var(--cloud-ink);
	border-bottom: 1px solid var(--cloud-line);

	.el-scrollbar {
		@apply w-full;
		:deep(.el-scrollbar__bar.is-vertical) {
			display: none !important;
		}
	}

	.student-cloud-header-breadcrumb {
		min-width: 0;
		align-items: center;

		:deep(.el-breadcrumb) {
			line-height: 48px;
			font-size: 13px;
			white-space: nowrap;
			margin-left: 22px;

			.el-breadcrumb__item {
				display: inline;
				float: none;
			}
		}
	}

	.student-cloud-header-right {
		display: flex;
		align-items: center;
		gap: 14px;
		flex-shrink: 0;

		:deep(.el-dropdown) {
			line-height: 1 !important;
		}

		.student-cloud-header-btn {
			display: flex;
			align-items: center;
			gap: 8px;

			> .el-button:not(.el-button--danger) {
				border-radius: 8px;
				color: var(--cloud-primary);
				font-weight: 600;
			}
		}

		.student-cloud-header-storage-select {
			width: 210px;
			flex-shrink: 0;
		}
	}
}

.header-icon-button {
	display: inline-flex;
	align-items: center;
	justify-content: center;
	width: 38px;
	height: 38px;
	flex-shrink: 0;
	padding: 0;
	border: 1px solid transparent;
	border-radius: 12px;
	background: var(--cloud-soft);
	color: var(--cloud-muted);
	cursor: pointer;
	line-height: 1;
	transition: color 180ms ease, background-color 180ms ease,
		transform 180ms var(--cloud-ease), box-shadow 180ms ease;
}
.header-icon-button :deep(svg),
.header-account-chevron {
	width: 20px;
	height: 20px;
	flex-shrink: 0;
}
.header-icon-button:hover {
	background: var(--cloud-lilac);
	color: var(--cloud-primary);
}
.header-icon-button.is-active {
	background: var(--cloud-primary);
	color: #fff;
}
.header-icon-button:active,
.header-account-button:active {
	transform: scale(0.94);
}
@media (hover: hover) and (pointer: fine) {
	.header-icon-button:hover,
	.header-account-button:hover {
		transform: translateY(-2px);
		box-shadow: 0 4px 10px #24242810;
	}
	.header-icon-button:active,
	.header-account-button:active {
		transform: translateY(0) scale(0.94);
	}
}
.header-icon-button:focus-visible,
.header-account-button:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 3px;
}
.header-account-button {
	display: inline-flex;
	align-items: center;
	gap: 8px;
	min-height: 38px;
	padding: 3px 7px 3px 4px;
	border: 1px solid var(--cloud-line);
	border-radius: 12px;
	background: var(--cloud-surface);
	color: var(--cloud-ink);
	cursor: pointer;
	line-height: 1;
}
.header-account-button:hover {
	border-color: #c9c0e4;
	background: var(--cloud-soft);
}
.header-account-button > :deep(span) {
	display: inline-flex;
	align-items: center;
	gap: 8px;
}
.header-account-avatar {
	display: grid;
	place-items: center;
	width: 28px;
	height: 28px;
	flex-shrink: 0;
	border-radius: 7px;
	background: var(--cloud-lilac);
	color: #6043a5;
	font-size: 13px;
	font-weight: 650;
}
.header-account-name {
	max-width: 96px;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
	font-size: 12px;
}
.header-account-chevron {
	color: var(--cloud-muted);
}
.header-mobile-menu {
	flex-shrink: 0;
}

@media only screen and (max-width: 1150px) {
	.header-account-name {
		display: none;
	}
	.student-cloud-header .student-cloud-header-right {
		gap: 10px;
		.student-cloud-header-storage-select {
			width: 180px;
		}
	}
}

@media only screen and (max-width: 767px) {
	.student-cloud-header {
		height: 64px;
		min-height: 64px;
		:deep(.el-breadcrumb__separator) {
			display: none !important;
		}

		:deep(.el-form-item__label) {
			display: none !important;
		}

		:deep(.el-select) {
			width: 120px;
			@apply truncate text-sm font-medium text-gray-700;
		}

		padding: 0 12px;
	}
}

.student-cloud-debug-tips {
	:deep(.el-form-item__label) {
		font-weight: bold;
		color: red !important;
	}
}

.student-cloud-header-storage-select {
	:deep(.el-input__wrapper) {
		@apply truncate text-sm font-medium;
	}

	:deep(.el-select__wrapper),
	:deep(.el-input__wrapper) {
		min-height: 38px;
		border: 1px solid var(--cloud-line);
		border-radius: 8px;
		background: var(--cloud-surface);
		box-shadow: none;
	}
}
@media (prefers-reduced-motion: reduce) {
	.header-icon-button,
	.header-account-button {
		transform: none !important;
		transition: none;
	}
}
</style>

<style lang="scss">
.student-cloud-header-dropdown {
	.el-dropdown-menu__item svg {
		width: 20px;
		height: 20px;
		flex-shrink: 0;
		color: var(--cloud-muted);
	}
	.el-dropdown-menu__item:hover,
	.el-dropdown-menu__item:hover svg {
		color: var(--cloud-primary);
	}
}
</style>
