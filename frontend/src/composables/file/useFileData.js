import { ElMessage } from 'element-plus'
import { concatPathAndEncodeAll } from '~/utils'
import {
	directoryRequestOptions,
	reusableDirectoryListing,
	supportsNativeDirectoryPagination,
} from '~/utils/directory-page.mjs'
import {
	createLatestRequest,
	loadDirectoryInParallel,
} from '~/utils/latest-request.mjs'

// Shared by all callers (sorting, navigation, upload completion and file operations).
const fileRequests = createLatestRequest()
let currentListingParams
const failedDirectoryRequest = ref(null)
// Describe the rendered listing, not the unsubmitted search input.
const directoryNameQuery = ref('')

import useFileContextMenu from '~/composables/file/useFileContextMenu'
const { contextMenuTargetFile, contextMenuTargetBlank } = useFileContextMenu()

import path from 'path-browserify'

import { loadFileListReq, loadStorageConfigReq } from '~/api/home/home'

import useRouterData from '~/composables/useRouterData'
let { routerRef, fullpath, storageKey, currentPath } = useRouterData()

import useFilePwd from '~/composables/file/useFilePwd'
let { getPathPwd, putPathPwd, popPassword } = useFilePwd()

import useHeaderStorageList from '~/composables/header/useHeaderStorageList'
const { storageListAsFileList, findStorageByKey } = useHeaderStorageList()

import useFileLoading from '~/composables/file/useFileLoading'
const {
	loading,
	firstLoading,
	basicLoading,
	skeletonLoading,
	directoryLoadError,
	directoryLoadState,
	resetDirectoryLoadState,
	beginDirectoryLoadState,
	finishDirectoryLoadState,
	setDirectoryLoadError,
} = useFileLoading()

const canRetryDirectoryLoad = computed(() => {
	const failed = failedDirectoryRequest.value
	return (
		directoryLoadState.value === 'error' &&
		!loading.value &&
		failed?.params.storageKey === storageKey.value &&
		failed?.routePath === currentPath.value
	)
})

import useFileDataStore from '~/stores/file-data'
let fileDataStore = useFileDataStore()

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

const title = useTitle(storageConfigStore.globalConfig.siteName)

import useGlobalConfigStore from '~/stores/global-config'
let globalConfigStore = useGlobalConfigStore()

// 引入文件预览组件
import useFilePreview from '~/composables/file/useFilePreview'
const {
	openAudio,
	openImage,
	openOffice,
	openPdf,
	openText,
	openVideo,
	open3d,
	openKkFileView,
} = useFilePreview()

// 文件操作相关
import useFileOperator from '~/composables/file/useFileOperator'

// ------------- loading start ------------

let skeletonData = reactive([])
if (skeletonData.length === 0) {
	// 动态配置骨架屏行数
	for (let i = 0; i < globalConfigStore.studentCloudConfig.skeleton.size; i++) {
		skeletonData.push({})
	}
}
// ------------- loading end   ------------

// 文件列表查询条件
let searchParam = reactive({
	path: '',
	orderBy: '',
	orderDirection: '',
	nameQuery: '',
	nativePagination: true,
})

const nativeSupported = computed(() =>
	supportsNativeDirectoryPagination(
		findStorageByKey(storageKey.value)?.type?.key
	)
)
const nativeActive = computed(
	() => nativeSupported.value && searchParam.nativePagination
)

import useFileSelect from '~/composables/file/useFileSelect'
let { selectRows, clearSelection } = useFileSelect()

export default function useFileData() {
	// 排序并重新加载数据
	const sortChangeMethod = ({ prop, order }) => {
		if (nativeActive.value)
			ElMessage.info(
				'已切换完整目录模式，全局排序需要读取整个目录，大目录可能较慢'
			)
		searchParam.nativePagination = false
		searchParam.orderBy = prop
		searchParam.orderDirection = order === 'descending' ? 'desc' : 'asc'
		loadFile()
	}

	/**
	 * 加载数据
	 * @param   initParam
	 *              path: 指定加载的路径, 如果不指定则使用路由路径
	 *              password: 指定加载的密码, 如果不指定则获取 path 对应的密码，如果 path 为空，则获取当前路由路径
	 *              rememberPassword: 是否记住密码, 如果不指定则不记住密码
	 */
	const cancelLoadFile = () => {
		fileRequests.cancel()
		currentListingParams = undefined
		failedDirectoryRequest.value = null
		directoryNameQuery.value = ''
		resetDirectoryLoadState()
		loading.value = false
		selectRows.value = []
		storageConfigStore.folderConfig.permission = {}
		storageConfigStore.folderConfig.readmeText = ''
	}

	const loadFile = async (initParam) => {
		const loadPath = initParam?.path || currentPath.value
		const previousListing = reusableDirectoryListing(
			currentListingParams,
			storageKey.value,
			loadPath
		)
		const previousNameQuery = previousListing ? directoryNameQuery.value : ''
		cancelLoadFile()
		// 未指定 storageKey 时, 不执行任何操作.
		if (!storageKey.value) {
			return
		}
		// Retain rows only when retrying or refreshing the same directory.
		if (!previousListing) fileDataStore.updateFileList([])
		directoryNameQuery.value = previousNameQuery
		loading.value = true
		beginDirectoryLoadState()
		// Keep the visible page retryable if the next request fails. No old grants are retained.
		currentListingParams = previousListing

		let param = { ...initParam }
		param.storageKey = storageKey.value
		param.path = loadPath
		// Explicit refresh/navigation fetches fresh metadata; cursor pages reuse a short snapshot.
		param.refreshMetadata = !initParam?.pageCursor
		param.nativePagination = initParam?.nativePagination ?? nativeActive.value
		Object.assign(
			param,
			directoryRequestOptions(initParam, {
				pageSize: storageConfigStore.globalConfig.maxShowSize,
				nativePagination: param.nativePagination,
				nameQuery: searchParam.nameQuery,
				orderBy:
					searchParam.orderBy ||
					storageConfigStore.globalConfig.defaultSortField,
				orderDirection:
					searchParam.orderDirection ||
					storageConfigStore.globalConfig.defaultSortOrder,
			})
		)
		if (param.nativePagination) {
			delete param.orderBy
			delete param.orderDirection
			param.nameQuery = ''
		}
		param.password = param.password || getPathPwd(loadPath, param.init)

		const request = fileRequests.begin()
		const routePath = currentPath.value
		const isCurrent = () =>
			request.isCurrent() &&
			param.storageKey === storageKey.value &&
			routePath === currentPath.value
		const config = {
			signal: request.signal,
			timeout: 30000,
			showDefaultMsg: false,
		}
		let filesLoaded = false
		try {
			await loadDirectoryInParallel({
				loadFiles: () => loadFileListReq(param, config),
				loadConfig: () =>
					loadStorageConfigReq(
						{
							storageKey: param.storageKey,
							path: loadPath,
							password: param.password,
						},
						config
					),
				isCurrent,
				onFiles: (response) => {
					// 将加载成功的密码存储起来.
					let passwordPattern = response.data.passwordPattern
					putPathPwd(
						passwordPattern,
						param.password,
						initParam?.rememberPassword
					)

					let fileList = [...response.data.files]

					// 如果不是根路径, 则构建 back 上级路径的数据.
					if (loadPath !== '' && loadPath !== '/') {
						let parentPathName = path.basename(path.resolve(loadPath, '../'))
						fileList.unshift({
							name: parentPathName ? parentPathName : '/',
							path: path.resolve(loadPath, '../'),
							type: 'BACK',
						})
					}

					fileDataStore.updateFileList(fileList, response.data)
					directoryNameQuery.value = param.nameQuery
					filesLoaded = true
					currentListingParams = { ...param, pageCursor: undefined }
					loading.value = false
					firstLoading.value = true
					selectRows.value = []

					// 修改标题
					if (fullpath.value) {
						title.value =
							storageConfigStore.globalConfig.siteName +
							' | ' +
							fullpath.value[fullpath.value.length - 1]
					} else {
						title.value = storageConfigStore.globalConfig.siteName + ' | 首页'
					}
				},
				onConfig: (response) => {
					storageConfigStore.updateFolderConfig(response.data)
					fileDataStore.updateOldStorageKey(param.storageKey)
				},
			})
			if (isCurrent()) finishDirectoryLoadState()
		} catch (error) {
			if (!isCurrent()) return
			if (error?.code === 'ERR_CANCELED') {
				resetDirectoryLoadState()
				return
			}
			const onConfirm = (password, rememberPassword) => {
				if (isCurrent())
					loadFile({ password, rememberPassword, path: loadPath })
			}
			const onCancel = () => {
				if (!isCurrent()) return
				resetDirectoryLoadState()
				if (
					(loadPath === '/' || loadPath === '') &&
					storageConfigStore.globalConfig.rootShowStorage === true
				) {
					fileDataStore.updateFileList(storageListAsFileList.value)
					routerRef.value.push('/')
					title.value = storageConfigStore.globalConfig.siteName + ' | 首页'
					loading.value = false
				} else {
					let parentPath = path.resolve(loadPath, '../')
					routerRef.value.push('/' + storageKey.value + parentPath)
				}
			}
			const data = error?.response?.data || error?.data || {}
			if (
				data.code === constant.responseCode.DIRECTORY_CHANGED &&
				param.pageCursor
			) {
				ElMessage.warning('目录内容已变化，已重新加载第一页，请重新选择文件')
				fileDataStore.updateFileList([])
				await loadFile({ ...param, pageCursor: undefined })
				return
			}
			// 如果需要密码或密码错误进行提示, 并弹出输入密码的框.
			if (data.code === constant.responseCode.INVALID_PASSWORD) {
				directoryLoadState.value = 'password'
				ElMessage.warning('密码错误，请重新输入！')
				popPassword(onConfirm, onCancel)
			} else if (data.code === constant.responseCode.REQUIRED_PASSWORD) {
				directoryLoadState.value = 'password'
				popPassword(onConfirm, onCancel)
			} else {
				const detail =
					data.msg ||
					(error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT'
						? '目录加载超时，请重试'
						: '目录加载失败，请检查网络后重试')
				const message = filesLoaded
					? `文件列表已载入，但目录权限和说明未能加载。${detail}`
					: detail
				storageConfigStore.folderConfig.permission = {}
				storageConfigStore.folderConfig.readmeText = ''
				failedDirectoryRequest.value = { params: { ...param }, routePath }
				setDirectoryLoadError(message)
				ElMessage.error(message)
			}
		} finally {
			if (isCurrent()) loading.value = false
		}
	}

	const retryDirectoryLoad = () => {
		if (!canRetryDirectoryLoad.value) return
		return loadFile({ ...failedDirectoryRequest.value.params })
	}

	const loadPage = (cursor) => {
		if (
			loading.value ||
			!cursor ||
			!reusableDirectoryListing(
				currentListingParams,
				storageKey.value,
				currentPath.value
			)
		)
			return
		return loadFile({ ...currentListingParams, pageCursor: cursor })
	}

	const applyDirectorySearch = () => {
		if (nativeActive.value)
			ElMessage.info(
				'已切换完整目录模式，名称筛选需要读取整个目录，大目录可能较慢'
			)
		searchParam.nativePagination = false
		return loadFile()
	}
	const setDirectoryMode = (fast) => {
		searchParam.nativePagination = Boolean(fast) && nativeSupported.value
		searchParam.nameQuery = ''
		searchParam.orderBy = ''
		searchParam.orderDirection = ''
		if (!fast)
			ElMessage.info('完整目录模式提供全局排序和筛选，大目录首次读取可能较慢')
		return loadFile()
	}
	const resetDirectoryQuery = () => {
		directoryNameQuery.value = ''
		searchParam.nameQuery = ''
		searchParam.orderBy = ''
		searchParam.orderDirection = ''
		searchParam.nativePagination = true
	}

	// 点击文件时，判断是文件夹则进入文件夹，是文件则进行预览
	const openRow = (row, fromContextmenu) => {
		if (!row.name) {
			return
		}

		// 当右键菜单打开时, 点击文件时, 不进行任何操作. 应该关闭右键菜单.
		if (
			!fromContextmenu &&
			(contextMenuTargetFile.value === true ||
				contextMenuTargetBlank.value === true)
		) {
			return
		}

		fileDataStore.updateCurrentClickRow(row)
		// 如果是文件且格式支持预览, 则进行预览, 格式不支持预览, 则直接进行下载 (ftp 模式不支持预览, 全部是下载)
		if (row.type === 'FILE') {
			// Configuration may still be loading; do not act using the previous folder's grants.
			const permission = storageConfigStore.folderConfig.permission
			if (!permission.preview && !permission.download) return
			const { batchDownloadFile } = useFileOperator()

			// 获取文件类型
			let fileType = row.fileType

			if (storageConfigStore.folderConfig.permission.preview) {
				switch (fileType) {
					case 'video':
						openVideo()
						break
					case 'image':
						openImage(row)
						break
					case 'text':
						openText()
						break
					case 'audio':
						openAudio(row)
						break
					case 'office':
						openOffice(row)
						break
					case 'pdf':
						openPdf(row)
						break
					case 'three3d':
						open3d(row)
						break
					case 'kkfileview':
						openKkFileView(row)
						break
					default:
						batchDownloadFile(row)
				}
			} else {
				batchDownloadFile(row)
			}

			clearSelection()
		} else {
			if (row.type === 'ROOT') {
				routerRef.value.push(row.path)
				storageConfigStore.folderConfig.readmeText = ''
			} else if (row.type === 'BACK') {
				let fullPath = concatPathAndEncodeAll('/', storageKey.value, row.path)
				routerRef.value.push(fullPath)
				storageConfigStore.folderConfig.readmeText = ''
			} else {
				let fullPath = concatPathAndEncodeAll(
					'/',
					storageKey.value,
					row.path,
					row.name
				)
				routerRef.value.push(fullPath)
				storageConfigStore.folderConfig.readmeText = ''
			}
		}
	}

	// ------------- folder password handled by useFilePwd  ------------

	return {
		loadFile,
		openRow,
		searchParam,
		directoryNameQuery,
		sortChangeMethod,
		skeletonLoading,
		skeletonData,
		basicLoading,
		loading,
		cancelLoadFile,
		directoryLoadError,
		directoryLoadState,
		canRetryDirectoryLoad,
		retryDirectoryLoad,
		resetDirectoryLoadState,
		beginDirectoryLoadState,
		finishDirectoryLoadState,
		setDirectoryLoadError,
		loadPage,
		nativeSupported,
		nativeActive,
		applyDirectorySearch,
		setDirectoryMode,
		resetDirectoryQuery,
	}
}
