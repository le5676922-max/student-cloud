<template>
	<div class="student-cloud-index-body" @contextmenu="showFileMenu">
		<section class="cloud-drive-heading" aria-label="文件概览">
			<div class="cloud-drive-heading-copy">
				<span class="cloud-drive-eyebrow"
					><FolderOpenIcon aria-hidden="true" />文件空间</span
				>
				<h1>{{ route.params.storageKey ? '我的文件' : '我的云盘' }}</h1>
				<p>
					{{
						route.params.storageKey
							? '资料放在一起，查找、整理和分享都更方便。'
							: '选择一个云盘，开始整理你的文件。'
					}}
				</p>
			</div>
			<div v-if="route.params.storageKey" class="cloud-drive-actions">
				<el-button v-if="canOrganizeDirectory" @click="openDirectoryAutomation"
					><QueueListIcon aria-hidden="true" />整理此目录</el-button
				>
				<el-button
					v-if="storageConfigStore.permission.newFolder"
					:disabled="loading || operationBusy"
					@click="createDirectory"
					><FolderPlusIcon aria-hidden="true" />新建文件夹</el-button
				>
				<el-button
					v-if="storageConfigStore.permission.upload"
					:disabled="loading || operationBusy"
					type="primary"
					@click="openUploadDialog"
					><ArrowUpTrayIcon aria-hidden="true" />上传文件</el-button
				>
			</div>
		</section>
		<nav class="cloud-drive-shortcuts" aria-label="云盘快捷入口">
			<router-link to="/" class="is-active" aria-current="page"
				><FolderIcon aria-hidden="true" />云盘文件</router-link
			>
			<router-link to="/learning"
				><BookOpenIcon aria-hidden="true" />课程资料</router-link
			>
			<router-link :to="automationRoute"
				><QueueListIcon aria-hidden="true" />自动化任务</router-link
			>
			<router-link
				v-if="storageConfigStore.loginInfo.role === 'TENANT_ADMIN'"
				to="/workspace-admin"
				><UserGroupIcon aria-hidden="true" />空间管理</router-link
			>
			<router-link
				v-if="storageConfigStore.loginInfo.isAdmin"
				to="/admin/overview"
				><Squares2X2Icon aria-hidden="true" />全局管理</router-link
			>
		</nav>
		<div
			ref="fileIndexBodyRef"
			class="cloud-file-panel"
			:class="
				'student-cloud-index-table-' +
				(isMobile
					? storageConfigStore.globalConfig?.mobileLayout
					: storageConfigStore.globalConfig?.layout)
			"
		>
			<!-- 公告 -->
			<el-alert
				v-if="
					storageConfigStore.globalConfig.showAnnouncement &&
					storageConfigStore.globalConfig.announcement
				"
				class="student-cloud-index-announcement"
				type="success"
			>
				<v-md-preview :text="storageConfigStore.globalConfig.announcement" />
			</el-alert>

			<!-- 文档模式显示 -->
			<card-readme display-mode="top" class="my-3" />

			<div class="cloud-file-toolbar">
				<div class="cloud-directory-caption">
					<span>{{
						route.params.storageKey ? '当前目录' : '可访问的云盘'
					}}</span
					><span class="cloud-directory-count" aria-live="polite">{{
						loading ? '读取中…' : directoryCount
					}}</span>
				</div>
				<form
					v-if="route.params.storageKey"
					class="directory-filter"
					@submit.prevent="applyDirectorySearch()"
				>
					<el-input
						v-model="searchParam.nameQuery"
						maxlength="200"
						clearable
						aria-label="按名称筛选当前目录"
						aria-describedby="cloud-directory-scope"
						placeholder="搜索当前目录的文件名"
						@clear="applyDirectorySearch()"
						><template #prefix
							><MagnifyingGlassIcon
								class="cloud-search-icon"
								aria-hidden="true" /></template
					></el-input>
					<el-button native-type="submit" :loading="loading">筛选</el-button>
				</form>
				<el-button
					class="cloud-directory-refresh"
					:loading="loading"
					:disabled="operationBusy"
					@click="refreshDirectory"
					><ArrowPathIcon aria-hidden="true" />刷新</el-button
				>
				<div class="cloud-view-options" role="group" aria-label="文件显示方式">
					<button
						type="button"
						aria-label="列表视图"
						:aria-pressed="
							!fileDataStore.imgMode &&
							studentCloudSettingCache.view.type === 'table'
						"
						@click="switchView('table')"
					>
						<ListBulletIcon aria-hidden="true" />
					</button>
					<button
						type="button"
						aria-label="卡片视图"
						:aria-pressed="
							!fileDataStore.imgMode &&
							studentCloudSettingCache.view.type === 'card'
						"
						@click="switchView('card')"
					>
						<Squares2X2Icon aria-hidden="true" />
					</button>
				</div>
			</div>
			<p
				v-if="route.params.storageKey"
				id="cloud-directory-scope"
				class="cloud-directory-scope"
			>
				{{
					nativeActive
						? '快速浏览：按存储源顺序分批读取，当前页数量不代表目录总数。'
						: '搜索范围：当前目录，不包含子目录。'
				}}
			</p>
			<details v-if="route.params.storageKey" class="cloud-directory-note">
				<summary>浏览选项与范围说明</summary>
				<el-switch
					v-if="nativeSupported"
					:model-value="nativeActive"
					:disabled="loading"
					active-text="快速浏览（存储源顺序）"
					inactive-text="完整目录"
					@change="setDirectoryMode"
				/>
				<p v-if="nativeActive">
					仅按存储源顺序读取本页，总数未知；搜索或排序会切换完整目录模式。
				</p>
				<p v-else>
					筛选仅查找当前目录，不包含子目录；全局排序和筛选需读取整个目录，大目录可能较慢。
				</p>
				<p
					v-if="
						fileDataStore.directoryPage?.nativePagination &&
						fileDataStore.directoryPage.count === 0 &&
						fileDataStore.directoryPage.nextCursor
					"
				>
					本页没有可显示的文件，后面仍有内容，请点击下一页。
				</p>
			</details>
			<section
				v-if="directoryLoadError"
				class="cloud-directory-error"
				role="alert"
			>
				<ExclamationCircleIcon aria-hidden="true" />
				<div>
					<h2>
						{{
							route.params.storageKey
								? '目录暂时无法完整加载'
								: '云盘列表暂时无法加载'
						}}
					</h2>
					<p>{{ directoryLoadError }}</p>
				</div>
				<el-button
					:loading="loading"
					:disabled="
						operationBusy ||
						(route.params.storageKey &&
							!navigationFailed &&
							!canRetryDirectoryLoad)
					"
					@click="retryDirectory"
					>重试</el-button
				>
			</section>
			<FileSelectionToolbar />

			<table-file-view
				v-if="studentCloudSettingCache.view.type === 'table'"
				class="student-cloud-file-view"
			/>

			<card-file-view
				v-if="studentCloudSettingCache.view.type === 'card'"
				class="student-cloud-file-view"
			/>

			<!-- 画廊模式 -->
			<file-gallery v-if="fileDataStore.imgMode" />
			<load-more-file
				v-if="
					fileDataStore.imgMode || studentCloudSettingCache.view.type === 'card'
				"
			/>

			<!-- 右键菜单 -->
			<z-contextmenu />

			<!-- 文本编辑器 -->
			<text-viewer-dialog />

			<!-- pdf 在线预览 -->
			<pdf-viewer-dialog />

			<!-- 视频播放器 -->
			<video-player-dialog />

			<!-- office 在线预览 -->
			<office-viewer-dialog />

			<!-- kkfileview 在线预览 -->
			<KkFileViewerDialog />

			<!-- 3d 在线预览 -->
			<three3d-preview-dialog />

			<!-- 生成直链 -->
			<GenerateLink v-if="generateLinkDialogVisible" />
			<GenerateLinkResult />

			<!-- 创建分享 -->
			<CreateShareDialog ref="createShareDialogRef" />

			<!-- 上传框 -->
			<ZUpload @close="loadFile" />

			<!-- 音频播放器 -->
			<audio-player />

			<!-- 回到顶部 -->
			<back-top
				v-show="globalConfigStore.studentCloudConfig.gallery.showBackTop"
			/>

			<!-- 弹窗文档 -->
			<dialog-readme />

			<!-- 底部文档 -->
			<card-readme display-mode="bottom" />

			<!-- 批量删除结果 -->
			<batch-operator-result />
		</div>
	</div>
</template>

<script setup>
import { isMobile } from '~/utils'
import { ElMessage } from 'element-plus'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { directoryCountLabel } from '~/utils/drive-presentation.mjs'
import {
	automationContextFromRoute,
	buildAutomationRoute,
	canPreviewDirectory,
} from '~/utils/automation-context.mjs'
import {
	ArrowPathIcon,
	ArrowUpTrayIcon,
	BookOpenIcon,
	FolderIcon,
	FolderOpenIcon,
	FolderPlusIcon,
	ExclamationCircleIcon,
	ListBulletIcon,
	MagnifyingGlassIcon,
	QueueListIcon,
	Squares2X2Icon,
	UserGroupIcon,
} from '@heroicons/vue/24/outline'

// 页面
import CardReadme from '~/components/file/readme/CardReadme.vue'
import DialogReadme from '~/components/file/readme/DialogReadme.vue'
import BatchOperatorResult from '~/components/file/BatchOperatorResult.vue'
import CreateShareDialog from '~/components/file/CreateShareDialog.vue'
import FileSelectionToolbar from '~/components/file/FileSelectionToolbar.vue'
const FileGallery = defineAsyncComponent(() =>
	import('~/components/file/preview/FileGallery.vue')
)

// 业务代码
import useFileDataStore from '~/stores/file-data'
let fileDataStore = useFileDataStore()

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import useGlobalConfigStore from '~/stores/global-config'
let globalConfigStore = useGlobalConfigStore()

// 右键, 长按菜单相关
import useFileContextMenu from '~/composables/file/useFileContextMenu'
const { showFileMenu } = useFileContextMenu()
const fileIndexBodyRef = ref()
import useFileLongPressEvent from '~/composables/file/useFileLongPressEvent'
useFileLongPressEvent(fileIndexBodyRef)

// 文件数据相关
import useFileData from '~/composables/file/useFileData'
const {
	loadFile,
	cancelLoadFile,
	loading,
	searchParam,
	nativeSupported,
	nativeActive,
	applyDirectorySearch,
	setDirectoryMode,
	resetDirectoryQuery,
	directoryLoadError,
	retryDirectoryLoad,
	canRetryDirectoryLoad,
	beginDirectoryLoadState,
	finishDirectoryLoadState,
	setDirectoryLoadError,
} = useFileData()
import useHeaderStorageList from '~/composables/header/useHeaderStorageList'
const { findStorageByKey, loadStorageSourceList } = useHeaderStorageList()
const navigationRequests = createLatestRequest()
const navigationFailed = ref(false)

import useSetting from '~/composables/header/useSetting'
const { studentCloudSettingCache } = useSetting()
const directoryCount = computed(() =>
	fileDataStore.directoryPage?.nativePagination
		? `${fileDataStore.directoryPage.count} 项（本页，总数未知）`
		: directoryCountLabel(
				fileDataStore.fileListSource,
				fileDataStore.directoryPage,
				Boolean(route.params.storageKey)
		  )
)
const switchView = (type) => {
	fileDataStore.imgMode = false
	studentCloudSettingCache.value.view.type = type
}
import useFileUpload from '~/composables/file/useFileUpload'
import useFileOperator from '~/composables/file/useFileOperator'
const { openUploadDialog } = useFileUpload()
const { newFolder, operationBusy } = useFileOperator()
const createDirectory = () => {
	if (loading.value || operationBusy.value) return
	// The HTTP layer presents request failures; do not leak them into a DOM event.
	return newFolder().catch(() => {})
}

// 直链
import useFileLink from '~/composables/file/useFileLink'
import TableFileView from '~/components/file/view/TableFileView.vue'
import CardFileView from '~/components/file/view/CardFileView.vue'
import { loadUserRootPathReq } from '~/api/home/home'
const { generateLinkDialogVisible } = useFileLink()

// 分享
import useFileShare from '~/composables/file/useFileShare'
const { setCreateShareDialogRef } = useFileShare()
const createShareDialogRef = ref()

// 初始化时，加载文件列表
onBeforeMount(() => {
	loadDirectory()
})

// 设置分享对话框引用
onMounted(() => {
	setCreateShareDialogRef(createShareDialogRef)
})

// 切换存储源或路径时，重新加载文件列表
let route = useRoute()
const router = useRouter()
const automationContext = computed(() => automationContextFromRoute(route))
const automationRoute = computed(() =>
	buildAutomationRoute(automationContext.value)
)
const canOrganizeDirectory = computed(() =>
	canPreviewDirectory(
		automationContext.value,
		fileDataStore.currentStorageSource,
		storageConfigStore.folderConfig.permission,
		'ORGANIZE_FILES'
	)
)
function openDirectoryAutomation() {
	router.push(
		buildAutomationRoute({ ...automationContext.value, type: 'ORGANIZE_FILES' })
	)
}
// Refresh only the directory that received files, even if the user navigated during upload.
useEventBus('cloud-files-changed').on((change) => {
	const context = automationContextFromRoute(route)
	if (
		context.storageKey === change?.storageKey &&
		context.path?.replace(/\/+$/, '') === change?.path?.replace(/\/+$/, '')
	) {
		loadFile()
	}
})
watch(
	() => [route.params.storageKey, route.params.fullpath],
	() => {
		loadDirectory()
	},
	{ flush: 'sync' }
)

const loadDirectory = async () => {
	const request = navigationRequests.begin()
	cancelLoadFile()
	navigationFailed.value = false
	// A newly selected source must never inherit the previous source's user root.
	storageConfigStore.updateUserRootPath('')
	resetDirectoryQuery()
	if (!route.params.storageKey) return
	fileDataStore.updateFileList([])
	loading.value = true
	beginDirectoryLoadState()
	try {
		// On a direct URL, identify the source before choosing a mode; never accidentally enumerate a huge S3 bucket.
		if (!findStorageByKey(route.params.storageKey))
			await loadStorageSourceList()
		if (!request.isCurrent()) return
		const res = await loadUserRootPathReq(route.params.storageKey, {
			signal: request.signal,
			timeout: 30000,
			showDefaultMsg: false,
		})
		if (!request.isCurrent()) return
		storageConfigStore.updateUserRootPath(res.data)
		await loadFile({ init: true })
	} catch (error) {
		if (request.isCurrent() && error?.code !== 'ERR_CANCELED') {
			loading.value = false
			navigationFailed.value = true
			const message =
				error?.response?.data?.msg ||
				error?.data?.msg ||
				'加载用户目录失败，请重试'
			setDirectoryLoadError(message)
			ElMessage.error(message)
		}
	}
}

const retryDirectory = () => {
	if (loading.value || operationBusy.value) return
	if (!route.params.storageKey) return refreshDirectory()
	return navigationFailed.value ? loadDirectory() : retryDirectoryLoad()
}

const refreshDirectory = async () => {
	if (loading.value || operationBusy.value) return
	if (route.params.storageKey) {
		return navigationFailed.value ? loadDirectory() : loadFile()
	}
	const request = navigationRequests.begin()
	cancelLoadFile()
	navigationFailed.value = false
	loading.value = true
	beginDirectoryLoadState()
	try {
		await loadStorageSourceList()
		if (request.isCurrent()) finishDirectoryLoadState()
	} catch (error) {
		if (!request.isCurrent() || error?.code === 'ERR_CANCELED') return
		navigationFailed.value = true
		setDirectoryLoadError('云盘列表加载失败，请检查网络后重试。')
	} finally {
		if (request.isCurrent()) loading.value = false
	}
}

onBeforeUnmount(() => {
	navigationRequests.cancel()
	cancelLoadFile()
})
</script>

<style lang="scss" scoped>
.student-cloud-index-body {
	display: flex;
	flex-direction: column;
	gap: 18px;
	min-height: 100%;
	padding: 24px clamp(16px, 3vw, 42px);
	box-sizing: border-box;
}

.cloud-drive-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 20px;
	padding: 22px 26px;
	border: 1px solid #dce6d9;
	border-radius: 16px;
	background: #eaf1e8;
}
.cloud-drive-heading-copy {
	min-width: 0;
}
.cloud-drive-eyebrow {
	display: inline-flex;
	align-items: center;
	gap: 6px;
	margin-bottom: 8px;
	color: #597361;
	font-size: 12px;
}
.cloud-drive-eyebrow svg {
	width: 16px;
	height: 16px;
}
.cloud-drive-actions {
	display: flex;
	flex-shrink: 0;
	gap: 10px;
}
.cloud-drive-actions .el-button {
	height: 40px;
	margin-left: 0;
}
.cloud-drive-actions svg {
	width: 18px;
	height: 18px;
	margin-right: 7px;
}

.cloud-drive-heading h1 {
	margin: 0;
	font-size: clamp(22px, 2.2vw, 28px);
	font-weight: 600;
	color: var(--cloud-ink);
}

.cloud-drive-heading p {
	margin: 5px 0 0;
	font-size: 13px;
	color: var(--cloud-muted);
}
.cloud-drive-shortcuts {
	display: flex;
	align-items: center;
	gap: 8px;
	flex-wrap: wrap;
}
.cloud-drive-shortcuts a {
	display: inline-flex;
	align-items: center;
	gap: 7px;
	min-height: 38px;
	padding: 8px 13px;
	border: 1px solid transparent;
	border-radius: 9px;
	color: var(--cloud-muted);
	font-size: 13px;
	text-decoration: none;
}
.cloud-drive-shortcuts svg {
	width: 17px;
	height: 17px;
}
.cloud-drive-shortcuts .is-active {
	border-color: var(--cloud-line);
	background: #fff;
	color: var(--cloud-primary);
	font-weight: 500;
}
.cloud-drive-shortcuts a:hover {
	background: var(--cloud-soft);
	color: var(--cloud-primary);
}

.cloud-file-panel {
	flex: 1;
	min-height: 350px;
	padding: 18px;
	border: 1px solid var(--cloud-line);
	border-radius: 14px;
	background: #fff;
	box-shadow: 0 3px 14px rgba(39, 57, 54, 0.025);

	:deep(.el-table) {
		--el-table-header-bg-color: #f5f7f3;
		--el-table-row-hover-bg-color: #f0f6f2;
		--el-table-border-color: #e7ebe5;
		border-radius: 8px;
	}

	:deep(.el-table th.el-table__cell) {
		color: #66736c;
		font-weight: 500;
	}
}
.cloud-file-toolbar {
	display: flex;
	align-items: center;
	gap: 16px;
	min-height: 38px;
	margin-bottom: 8px;
}
.cloud-directory-caption {
	display: flex;
	align-items: center;
	gap: 10px;
	margin-right: auto;
	color: var(--cloud-ink);
	font-size: 14px;
	font-weight: 500;
}
.cloud-directory-count {
	padding: 3px 8px;
	border-radius: 6px;
	background: #f1f3ed;
	color: #7a8579;
	font-size: 12px;
	font-weight: 400;
	white-space: nowrap;
}
.cloud-directory-note {
	margin: 0 0 14px;
	color: #7a8579;
	font-size: 12px;
}
.cloud-directory-note summary {
	width: fit-content;
	cursor: pointer;
	color: #62786b;
	padding: 5px 0;
}
.cloud-directory-note summary:focus-visible {
	outline: 2px solid var(--cloud-primary);
	outline-offset: 3px;
}
.cloud-directory-note p {
	margin: 6px 0;
	line-height: 1.7;
}
.cloud-directory-scope {
	margin: 0 0 5px;
	color: var(--cloud-muted);
	font-size: 12px;
}
.cloud-directory-refresh svg {
	width: 16px;
	height: 16px;
	margin-right: 5px;
}
.cloud-directory-error {
	display: flex;
	align-items: center;
	gap: 12px;
	margin: 12px 0;
	padding: 16px;
	border: 1px solid #ebd5bd;
	border-radius: 10px;
	background: #fff8ee;
}
.cloud-directory-error > svg {
	width: 23px;
	height: 23px;
	flex-shrink: 0;
	color: #ad7338;
}
.cloud-directory-error > div {
	flex: 1;
	min-width: 0;
}
.cloud-directory-error h2 {
	margin: 0 0 4px;
	color: var(--cloud-ink);
	font-size: 14px;
	font-weight: 500;
}
.cloud-directory-error p {
	margin: 0;
	color: #8a6a49;
	font-size: 12px;
	line-height: 1.7;
	overflow-wrap: anywhere;
}
.directory-filter {
	display: flex;
	gap: 8px;
	width: min(430px, 50%);
}
.directory-filter .el-input {
	flex: 1;
	min-width: 0;
}
.directory-filter :deep(.el-input__wrapper) {
	border-radius: 8px;
	background: #fff;
}
.cloud-search-icon {
	width: 16px;
	height: 16px;
	color: var(--cloud-muted);
}
.cloud-view-options {
	display: flex;
	gap: 3px;
	flex-shrink: 0;
	padding: 3px;
	border: 1px solid var(--cloud-line);
	border-radius: 9px;
	background: #fafbf8;
}
.cloud-view-options button {
	display: grid;
	place-items: center;
	width: 31px;
	height: 30px;
	border-radius: 6px;
	color: #89938a;
}
.cloud-view-options svg {
	width: 18px;
	height: 18px;
}
.cloud-view-options button[aria-pressed='true'] {
	background: #fff;
	color: var(--cloud-primary);
	box-shadow: 0 1px 4px #27393612;
}
.cloud-view-options button:hover {
	color: var(--cloud-primary);
}

// 公告显示样式
.student-cloud-index-announcement {
	margin: 10px 0;

	:deep(.github-markdown-body) {
		padding: unset;
	}
}

// 居中模式
.student-cloud-index-table-center {
	@apply w-full mx-auto;
}
// 卡片模式
.student-cloud-index-table-card {
	@apply w-full max-w-7xl mx-auto;

	&::after {
		content: '';
		@apply top-2 h-2.5 block relative;
	}

	.student-cloud-file-view {
		@apply my-3 p-4 rounded-2xl;
		box-shadow: none;
	}
}

@media (max-width: 767px) {
	.student-cloud-index-body {
		gap: 12px;
		padding: 12px;
	}
	.cloud-drive-heading {
		align-items: flex-start;
		flex-direction: column;
		gap: 16px;
		padding: 18px;
		border-radius: 12px;
	}
	.cloud-drive-heading h1 {
		font-size: 24px;
	}
	.cloud-drive-actions {
		width: 100%;
	}
	.cloud-drive-actions .el-button {
		flex: 1;
		min-width: 0;
	}
	.cloud-drive-shortcuts {
		flex-wrap: nowrap;
		gap: 3px;
		overflow-x: auto;
	}
	.cloud-drive-shortcuts a {
		flex-shrink: 0;
		padding: 7px 10px;
		font-size: 12px;
	}
	.cloud-file-panel {
		min-height: 300px;
		padding: 12px;
		border-radius: 10px;
	}
	.cloud-file-toolbar {
		flex-wrap: wrap;
		gap: 12px;
	}
	.directory-filter {
		order: 3;
		width: 100%;
	}
	.cloud-directory-error {
		flex-wrap: wrap;
		padding: 12px;
	}
	.cloud-directory-error .el-button {
		margin-left: 35px;
	}
	.cloud-drive-shortcuts::-webkit-scrollbar {
		height: 0;
	}
}
</style>

<route lang="yaml">
meta:
  layout: file
</route>
