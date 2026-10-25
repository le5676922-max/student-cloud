import { getSourceListReq } from '~/api/home/home'

import MessageBox from '~/components/messageBox/messageBox'
import {
	emptyStorageNotice,
	loadHeaderStorageSources,
} from '~/utils/header-storage-load.mjs'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { uploadAccountIdentity } from '~/composables/file/upload-lifecycle.mjs'

import useFileLoading from '~/composables/file/useFileLoading'
const { loading, setDirectoryLoadError, finishDirectoryLoadState } =
	useFileLoading()

import useFileDataStore from '~/stores/file-data'
let fileDataStore = useFileDataStore()

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import useRouterData from '~/composables/useRouterData'
let { routerRef, fullpath, storageKey, routeRef } = useRouterData()

let storageList = ref([])
let currentStorageKey = ref()
const storageRequests = createLatestRequest()
let pendingStorageLoad
const storageRouteContext = () =>
	JSON.stringify([
		routeRef.value?.path,
		storageKey.value,
		fullpath.value,
		uploadAccountIdentity(storageConfigStore.loginInfo),
	])
const canceledStorageLoad = () =>
	Object.assign(new Error('存储列表请求已取消'), { code: 'ERR_CANCELED' })

let initialized = false

export default function useHeaderStorageList() {
	let rootShowStorage = storageConfigStore.globalConfig.rootShowStorage

	// A missing/failed list must not leave a previous cloud or its permissions active.
	const clearUnavailableStorage = () => {
		storageList.value = []
		// Public shares have their own file list and permissions.
		if (routeRef.value?.path?.startsWith('/share')) return
		currentStorageKey.value = undefined
		fileDataStore.updateCurrentStorageSource({
			id: null,
			type: { description: '', key: '' },
		})
		fileDataStore.updateFileList([])
		storageConfigStore.folderConfig.permission = {}
		storageConfigStore.folderConfig.readmeText = ''
		loading.value = false
	}

	// 加载存储源列表
	let loadStorageSourceList = () => {
		const context = storageRouteContext()
		// Header and a direct directory URL can need the same bootstrap concurrently.
		if (
			pendingStorageLoad?.context === context &&
			pendingStorageLoad.isCurrent()
		)
			return pendingStorageLoad.promise
		const request = storageRequests.begin()
		const isCurrent = () =>
			request.isCurrent() && storageRouteContext() === context
		const pending = { context, isCurrent }
		pendingStorageLoad = pending
		pending.promise = loadHeaderStorageSources({
			request: async () => {
				try {
					const response = await getSourceListReq({
						signal: request.signal,
						timeout: 10000,
						showDefaultMsg: false,
					})
					if (!isCurrent()) throw canceledStorageLoad()
					return response
				} catch (error) {
					if (!isCurrent()) throw canceledStorageLoad()
					throw error
				}
			},
			onLoaded: (response) => {
				if (!isCurrent()) throw canceledStorageLoad()
				storageList.value = response.data
				if (!storageKey.value && !routeRef.value?.path?.startsWith('/share'))
					finishDirectoryLoadState()

				// 空列表也正常完成加载；只有全局管理员可以配置全局存储。
				if (storageList.value.length === 0) {
					clearUnavailableStorage()
					if (routeRef.value?.path?.startsWith('/share')) return
					const notice = emptyStorageNotice(storageConfigStore.loginInfo)
					if (notice.configurePath) {
						// Do not block the list request on a user's dialog choice.
						MessageBox.confirm(notice.message, '提示', {
							confirmButtonText: '检查配置',
							cancelButtonText: '暂不前往',
							type: 'info',
						})
							.then(() => routerRef.value.push(notice.configurePath))
							.catch(() => {})
					} else {
						ElMessage.warning(notice.message)
					}
					return
				}

				rootPathAction(rootShowStorage)
			},
			onFailure: (error) => {
				if (!isCurrent() || error?.code === 'ERR_CANCELED') return
				clearUnavailableStorage()
				if (!storageKey.value && !routeRef.value?.path?.startsWith('/share'))
					setDirectoryLoadError('云盘列表加载失败，请检查网络后重试。')
				// HTTP errors are already shown by the shared request interceptor.
				if (error?.code === 'INVALID_STORAGE_LIST') {
					ElMessage.error(error.message)
				}
			},
		}).finally(() => {
			if (pendingStorageLoad === pending) pendingStorageLoad = undefined
		})
		return pending.promise
	}

	// 如果路由处理根目录的操作.
	const rootPathAction = (rootShowStorage) => {
		// 在分享页面不干预文件列表（避免覆盖分享文件列表为存储源列表）
		if (routeRef.value?.path?.startsWith('/share')) {
			return
		}
		// 如果当前 URL 参数中有存储源 ID, 则直接用当前的.
		if (storageKey.value) {
			// 判断 url 中的 storageKey 是否存在于 storageList 中. 如果不存在, 则跳转到首页
			let storageByKey = findStorageByKey(storageKey.value)

			if (storageByKey) {
				currentStorageKey.value = storageKey.value
			} else {
				// 否则读取存储源列表中的第一个, 并跳转到响应的 URL 中.
				routerToFirstStorage()
			}
		} else {
			if (rootShowStorage) {
				fileDataStore.updateFileList(storageListAsFileList.value)
				document.title = storageConfigStore.globalConfig.siteName + ' | 首页'
				loading.value = false
			} else {
				routerToFirstStorage()
			}
		}
		refreshCurrentStorageSource()
	}

	// 将存储源类别转换到文件列表
	const storageListAsFileList = computed(() => {
		let fileList = []
		storageList.value.forEach((item) => {
			fileList.push({
				name: item.name,
				path: item.key,
				size: 0,
				time: '-',
				type: 'ROOT',
			})
		})
		return fileList
	})

	// 更新当前存储策略到 pinia 中
	const refreshCurrentStorageSource = () => {
		storageList.value.some((item) => {
			if (item.key === currentStorageKey.value) {
				fileDataStore.updateCurrentStorageSource(item)
			}
		})
	}

	if (!initialized) {
		watch(storageRouteContext, () => storageRequests.cancel(), {
			flush: 'sync',
		})
		// 当存储策略变化时, 切换路由中的值
		watch(
			() => currentStorageKey.value,
			(newVal, oldVal) => {
				// Clearing a missing cloud must never navigate to /undefined.
				if (!newVal) return
				// 如果切换到了新存储源，且没有指定路径，则进行切换
				if ((newVal && !fullpath.value) || oldVal !== undefined) {
					storageConfigStore.folderConfig.readmeText = ''
					routerRef.value.push('/' + newVal)
					refreshCurrentStorageSource()
				}
			}
		)

		watch(
			() => storageKey.value,
			() => {
				rootPathAction(true)
			}
		)
		initialized = true
	}

	// 跳转到第一个存储器
	const routerToFirstStorage = () => {
		// 否则读取存储源列表中的第一个, 并跳转到响应的 URL 中.
		if (storageList.value.length > 0) {
			let firstStorageKey = storageList.value[0].key
			currentStorageKey.value = firstStorageKey
			routerRef.value.push('/' + firstStorageKey)
		}
	}

	/**
	 * 根据存储源 key 查找对象
	 */
	const findStorageByKey = (key) => {
		return storageList.value.find((item) => {
			if (item.key == key) {
				return item
			}
		})
	}

	return {
		loadStorageSourceList,
		storageList,
		findStorageByKey,
		currentStorageKey,
		storageListAsFileList,
	}
}
