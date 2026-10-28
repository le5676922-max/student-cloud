import {
	deleteStorageReq,
	loadStorageListReq,
	storageSortReq,
	switchCacheAutoRefreshReq,
	switchCacheEnableReq,
	switchEnableReq,
} from '~/api/admin/admin-storage'
import Sortable from 'sortablejs'
import { isMobile } from '~/utils'
import {
	createStorageListLoader,
	filterStorageItems,
	isStorageLoadCanceled,
	reorderStorageItems,
	normalizeStorageStatusFilter,
	storageStatusCounts,
	storageStatusFilters,
	storageCommittedFailureMessage,
	storageListErrorMessage,
	storageListRouteState,
	storageListRouteQuery,
} from '~/utils/storage-list-controller.mjs'
import useStorageCopy from '~/composables/admin/storage/storage-copy'

export default function useStorageList(router, access = {}) {
	const route = () => router.currentRoute?.value
	const initialFilters = storageListRouteState(route()?.query)
	const cacheManageVisible = ref(false)
	const currentCacheManageId = ref()
	const loading = ref(false)
	const sorting = ref(false)
	const loadError = ref('')
	const operationError = ref('')
	const storageList = ref([])
	const searchKey = ref(initialFilters.query)
	const committedSearch = ref(initialFilters.query)
	const searchInputRef = ref(null)
	const searchComposing = ref(false)
	const statusFilter = ref(initialFilters.status)
	const hasLoaded = ref(false)
	const storageContainerRef = ref(null)
	const pendingIds = ref([])
	const pendingRows = new Map()
	const operationPending = computed(() => pendingIds.value.length > 0)
	const { openDialog: openCopyStorageDialog } = useStorageCopy()
	let sortable
	let sortController
	let disposed = false
	let generation = 0
	let mutationRevision = 0
	let loadContext
	let restoringRoute = false
	const identity = () =>
		JSON.stringify([
			access.sessionIdentity?.value,
			access.credentialIdentity?.(),
			access.independent?.value,
			access.globalAdmin?.value,
			access.tenantAdmin?.value,
			access.workspaceId?.value,
		])
	const captureContext = () => ({
		identity: identity(),
		path: route()?.path,
		generation,
	})
	const isCurrentContext = (context) =>
		!disposed &&
		context.generation === generation &&
		context.identity === identity() &&
		context.path === route()?.path
	const globalOnly = () =>
		access.independent?.value === true && access.globalAdmin?.value !== true
	const isRemoteConnection = (row) =>
		['aliyun', 'aliyun_oss', 's3', 'minio', 'independent_remote'].includes(
			String(row?.type?.key || '').toLowerCase()
		)
	const canChangeStorage = (row) => {
		if (
			String(row?.type?.key || '').toLowerCase() === 'local' &&
			access.globalAdmin?.value !== true
		)
			return false
		if (!globalOnly()) return true
		return (
			access.tenantAdmin?.value === true &&
			isRemoteConnection(row) &&
			access.workspaceId?.value != null &&
			row.tenantId != null &&
			String(row.tenantId) === String(access.workspaceId.value)
		)
	}
	const canDeleteStorage = (row) =>
		access.independent?.value !== true ||
		(access.globalAdmin?.value === true &&
			String(row?.type?.key || '').toLowerCase() === 'local')
	const rejectConnectionChange = (row) => {
		if (canChangeStorage(row)) return false
		operationError.value = isRemoteConnection(row)
			? '仅可维护自己工作空间的远程存储源。'
			: '服务器本地云盘由全局管理员维护；可在上方提交本地云盘申请。'
		return true
	}
	const isRowPending = (row) => pendingIds.value.includes(String(row?.id))
	const hasStorageFilters = computed(
		() =>
			Boolean(searchKey.value.trim()) ||
			normalizeStorageStatusFilter(statusFilter.value) !== 'all'
	)
	const sortingDisabled = computed(
		() =>
			loading.value ||
			sorting.value ||
			operationPending.value ||
			hasStorageFilters.value ||
			globalOnly()
	)
	const statusOptions = computed(() => {
		const counts = storageStatusCounts(storageList.value)
		return storageStatusFilters.map((filter) => ({
			...filter,
			count: hasLoaded.value ? counts[filter.value] : null,
		}))
	})
	const listQuery = () =>
		storageListRouteQuery({
			query: committedSearch.value,
			status: statusFilter.value,
		})
	const persistFilters = () => {
		if (
			disposed ||
			restoringRoute ||
			(route()?.path && route().path !== '/admin/storage-list')
		)
			return
		const query = listQuery()
		if (JSON.stringify(route()?.query || {}) === JSON.stringify(query)) return
		router.replace?.({ path: '/admin/storage-list', query })?.catch?.(() => {})
	}
	watch(
		searchKey,
		(value) => {
			if (searchComposing.value) return
			committedSearch.value = value
			persistFilters()
		},
		{ flush: 'sync' }
	)
	watch(statusFilter, persistFilters, { flush: 'sync' })
	watch(
		() => route()?.query,
		(query) => {
			if (route()?.path !== '/admin/storage-list') return
			const restored = storageListRouteState(query)
			restoringRoute = true
			searchKey.value = restored.query
			committedSearch.value = restored.query
			statusFilter.value = restored.status
			restoringRoute = false
		}
	)
	const focusSearch = () => nextTick(() => searchInputRef.value?.focus())
	const clearSearch = () => {
		searchComposing.value = false
		searchKey.value = ''
		committedSearch.value = ''
		persistFilters()
		focusSearch()
	}
	const clearFilters = () => {
		clearSearch()
		statusFilter.value = 'all'
	}
	const beginSearchComposition = () => {
		searchComposing.value = true
	}
	const finishSearchComposition = (event) => {
		searchComposing.value = false
		searchKey.value = event?.target?.value ?? searchKey.value
		committedSearch.value = searchKey.value
		persistFilters()
	}
	const navigate = (path) => {
		if (disposed || sorting.value) return
		const query = listQuery()
		router.push(Object.keys(query).length ? { path, query } : path)
	}
	const addStorage = () => navigate('/admin/storage-edit')
	const editStorage = (row) => {
		if (isRowPending(row) || rejectConnectionChange(row)) return
		navigate('/admin/storage-edit/' + row.id)
	}
	const beginOperation = (row) => {
		if (disposed || loading.value || sorting.value || isRowPending(row))
			return null
		const context = captureContext()
		pendingRows.set(String(row.id), context)
		pendingIds.value = [...pendingRows.keys()]
		operationError.value = ''
		return context
	}
	const finishOperation = (row, context) => {
		if (pendingRows.get(String(row.id)) !== context) return
		pendingRows.delete(String(row.id))
		pendingIds.value = [...pendingRows.keys()]
	}
	const runOperation = async (row, context, request, onSuccess) => {
		if (context.started) return
		context.started = true
		try {
			if (!isCurrentContext(context) || rejectConnectionChange(row)) return
			await request()
			if (isCurrentContext(context)) {
				mutationRevision++
				await onSuccess()
			}
		} catch (error) {
			if (isCurrentContext(context) && !isStorageLoadCanceled(error)) {
				const committedMessage = storageCommittedFailureMessage(error)
				operationError.value =
					committedMessage || '操作结果尚未确认，请刷新确认后再操作。'
				// Read only: never automatically replay a mutation.
				if (committedMessage) {
					mutationRevision++
					await loader.load()
				}
			}
		} finally {
			finishOperation(row, context)
		}
	}
	const confirmOperation = (row, message, verb, request, onSuccess) => {
		const context = beginOperation(row)
		if (!context) return
		ElMessageBox.confirm(message, `${verb}存储源`, {
			confirmButtonText: verb,
			cancelButtonText: '取消',
			type: 'warning',
			autofocus: false,
			callback: (action) => {
				if (action === 'confirm') runOperation(row, context, request, onSuccess)
				else finishOperation(row, context)
			},
		})
	}
	const deleteStorage = (row) => {
		if (access.independent?.value === true && isRemoteConnection(row)) {
			operationError.value =
				'远程存储源请停用后保留连接历史；当前不提供删除连接。'
			return
		}
		if (rejectConnectionChange(row)) return
		if (access.independent?.value === true && row.enable) {
			operationError.value =
				'请先停用存储源，再删除连接配置；实际文件不会被删除。'
			return
		}
		confirmOperation(
			row,
			`删除“${row.name}”的连接配置？实际文件不会被删除。`,
			'删除',
			() => deleteStorageReq(row.id),
			async () => {
				ElMessage.success('删除成功')
				await loader.load()
			}
		)
	}
	const handleClose = (done) => {
		ElMessageBox.confirm('是否确认关闭？关闭后填写的数据不会保留。', {
			type: 'warning',
		})
			.then(() => done())
			.catch(() => {})
	}
	const showFilterPage = (row) => navigate('/admin/storage-filter/' + row.id)
	const showReadmePage = (row) => navigate('/admin/storage-readme/' + row.id)
	const showPasswordPage = (row) =>
		navigate('/admin/storage-password/' + row.id)
	const switchEnableStatus = (row) => {
		if (rejectConnectionChange(row)) return
		const wasEnabled = row.enable === true
		const verb = wasEnabled ? '停用' : '启用'
		confirmOperation(
			row,
			`${verb}“${row.name}”？${
				wasEnabled ? '停用后将无法打开此云盘。' : '启用后成员可按既有权限访问。'
			}`,
			verb,
			() => switchEnableReq(row.id, wasEnabled ? 'disable' : 'enable'),
			() => {
				row.enable = !wasEnabled
				const currentRow = storageList.value.find(
					(item) => String(item.id) === String(row.id)
				)
				if (currentRow) currentRow.enable = !wasEnabled
				ElMessage.success(verb + '成功')
			}
		)
	}
	const switchCacheEnableStatus = (row) => {
		if (rejectConnectionChange(row)) return
		if (row.cacheAvailable === false) {
			operationError.value = '当前存储源未提供缓存能力。'
			return
		}
		const context = beginOperation(row)
		if (context)
			runOperation(
				row,
				context,
				() =>
					switchCacheEnableReq(row.id, row.enableCache ? 'enable' : 'disable'),
				() => ElMessage.success('修改成功')
			)
	}
	const switchCacheAutoRefreshStatus = (row) => {
		if (rejectConnectionChange(row)) return
		if (row.cacheAvailable === false) {
			operationError.value = '当前存储源未提供缓存自动刷新能力。'
			return
		}
		const context = beginOperation(row)
		if (context)
			runOperation(
				row,
				context,
				() =>
					switchCacheAutoRefreshReq(
						row.id,
						row.autoRefreshCache ? 'start' : 'stop'
					),
				() => ElMessage.success('修改成功')
			)
	}
	const showPermissionPage = (row) =>
		navigate('/admin/storage-premission/' + row.id)
	const cacheManage = (row) => {
		if (row.cacheAvailable === false) {
			operationError.value = '当前存储源未提供缓存能力。'
			return
		}
		currentCacheManageId.value = row.id
		cacheManageVisible.value = true
	}
	const handleOperator = (command) => {
		if (sorting.value || isRowPending(command.storage)) return
		if (
			access.independent?.value === true &&
			[
				'permissionManager',
				'readmeManager',
				'filterManager',
				'pwdManager',
				'copy',
			].includes(command.operator)
		) {
			operationError.value =
				command.operator === 'permissionManager'
					? '成员权限由对应工作空间管理员在成员管理中设置。'
					: '当前独立后端暂未提供这项存储源操作。'
			return
		}
		switch (command.operator) {
			case 'edit':
				editStorage(command.storage)
				break
			case 'enable':
				switchEnableStatus(command.storage)
				break
			case 'moveEarlier':
				moveStorage(command.storage, -1)
				break
			case 'moveLater':
				moveStorage(command.storage, 1)
				break
			case 'readmeManager':
				showReadmePage(command.storage)
				break
			case 'cacheManager':
				cacheManage(command.storage)
				break
			case 'filterManager':
				showFilterPage(command.storage)
				break
			case 'pwdManager':
				showPasswordPage(command.storage)
				break
			case 'delete':
				deleteStorage(command.storage)
				break
			case 'permissionManager':
				showPermissionPage(command.storage)
				break
			case 'copy':
				openCopyStorageDialog(command.storage)
				break
		}
	}
	const destroySort = () => {
		sortable?.destroy()
		sortable = undefined
	}
	const restoreDraggedElement = (event) => {
		if (!event.item || !event.from) return
		const siblings = Array.from(event.from.children).filter(
			(element) => element !== event.item && element.matches('.storage-item')
		)
		event.from.insertBefore(
			event.item,
			siblings[event.oldDraggableIndex] ||
				event.from.querySelector('.add-storage-btn')
		)
	}
	const saveOrder = async (oldIndex, newIndex) => {
		if (sortingDisabled.value || disposed) return false
		const reordered = reorderStorageItems(storageList.value, oldIndex, newIndex)
		if (!reordered) return false
		const context = captureContext()
		sorting.value = true
		operationError.value = ''
		const controller = new AbortController()
		sortController = controller
		try {
			// Payload order is the user order. Backend acquires mount locks by ID.
			await storageSortReq(
				reordered.map(({ id }, orderNum) => ({ id, orderNum })),
				{ signal: controller.signal, showDefaultMsg: false }
			)
			if (!isCurrentContext(context)) return false
			storageList.value = reordered.map((item, orderNum) => ({
				...item,
				orderNum,
			}))
			ElMessage.success('调整排序成功')
			return true
		} catch (error) {
			if (isCurrentContext(context) && !isStorageLoadCanceled(error)) {
				const committedMessage = storageCommittedFailureMessage(error)
				operationError.value =
					committedMessage || '排序结果尚未确认，请刷新确认后再调整。'
				if (committedMessage) await loader.load()
			}
			return false
		} finally {
			if (isCurrentContext(context)) sorting.value = false
			if (sortController === controller) sortController = undefined
		}
	}
	const moveStorage = (row, direction) => {
		const index = storageList.value.findIndex(
			(item) => String(item.id) === String(row.id)
		)
		return saveOrder(index, index + direction)
	}
	const canMoveStorage = (row, direction) => {
		const index = storageList.value.findIndex(
			(item) => String(item.id) === String(row.id)
		)
		return (
			!sortingDisabled.value &&
			index >= 0 &&
			index + direction >= 0 &&
			index + direction < storageList.value.length
		)
	}
	const setSort = () => {
		destroySort()
		const el = storageContainerRef.value
		if (!el || disposed) return
		sortable = Sortable.create(el, {
			draggable: '.storage-item',
			filter: '.add-storage-btn, button, input, a, .el-dropdown, .el-tag',
			preventOnFilter: false,
			disabled: sortingDisabled.value,
			delay: isMobile.value ? 300 : 0,
			onEnd: async (event) => {
				restoreDraggedElement(event)
				return saveOrder(event.oldDraggableIndex, event.newDraggableIndex)
			},
		})
	}
	watch(sortingDisabled, (disabled) => sortable?.option('disabled', disabled))
	const loader = createStorageListLoader({
		request: ({ signal }) =>
			loadStorageListReq(undefined, { signal, showDefaultMsg: false }),
		onStart: () => {
			loadContext = { ...captureContext(), mutationRevision }
			loading.value = true
			loadError.value = ''
			destroySort()
		},
		onLoaded: async (items, { isCurrent }) => {
			if (!isCurrentContext(loadContext)) {
				invalidateView()
				return
			}
			if (loadContext.mutationRevision !== mutationRevision) {
				// A successful write supersedes a GET snapshot taken before it.
				// Re-read metadata only; never replay the write or apply the old rows.
				await loader.load()
				return
			}
			storageList.value = items
			hasLoaded.value = true
			await nextTick()
			if (isCurrent() && isCurrentContext(loadContext)) setSort()
		},
		onFailure: (error) => {
			if (isCurrentContext(loadContext))
				loadError.value = storageListErrorMessage(error)
			else invalidateView()
		},
		onSettled: () => {
			loading.value = false
		},
	})
	const invalidateView = () => {
		generation++
		loader.cancel()
		sortController?.abort()
		destroySort()
		pendingRows.clear()
		pendingIds.value = []
		storageList.value = []
		hasLoaded.value = false
		loading.value = false
		sorting.value = false
		cacheManageVisible.value = false
		currentCacheManageId.value = undefined
		operationError.value = ''
		loadError.value = '账号或访问位置已变化，请重新加载存储源。'
	}
	watch(identity, invalidateView, { flush: 'sync' })
	const onSessionStorage = (event) => {
		if (event.key === 'tongligong-student-cloud-token' || event.key === null)
			invalidateView()
	}
	window.addEventListener?.('storage', onSessionStorage)
	const init = () =>
		sorting.value || operationPending.value
			? Promise.resolve(false)
			: loader.load()
	onBeforeUnmount(() => {
		disposed = true
		generation++
		loader.dispose()
		sortController?.abort()
		window.removeEventListener?.('storage', onSessionStorage)
		destroySort()
	})
	const storageListSearchResult = computed(() =>
		filterStorageItems(
			storageList.value,
			committedSearch.value,
			statusFilter.value
		)
	)
	const emptyState = computed(() => {
		if (
			loading.value ||
			loadError.value ||
			!hasLoaded.value ||
			storageListSearchResult.value.length
		)
			return ''
		return hasStorageFilters.value ? 'filtered' : 'unconfigured'
	})
	return {
		init,
		loading,
		sorting,
		loadError,
		operationError,
		storageContainerRef,
		searchKey,
		searchInputRef,
		statusFilter,
		statusOptions,
		hasStorageFilters,
		hasLoaded,
		emptyState,
		clearFilters,
		clearSearch,
		beginSearchComposition,
		finishSearchComposition,
		sortingDisabled,
		operationPending,
		isRowPending,
		canMoveStorage,
		moveStorage,
		showingPreviousData: computed(
			() =>
				hasLoaded.value &&
				Boolean(loadError.value) &&
				storageList.value.length > 0
		),
		storageListSearchResult,
		handleOperator,
		canChangeStorage,
		canDeleteStorage,
		addStorage,
		editStorage,
		deleteStorage,
		handleClose,
		showFilterPage,
		showReadmePage,
		showPasswordPage,
		switchEnableStatus,
		cacheManageVisible,
		currentCacheManageId,
		switchCacheEnableStatus,
		switchCacheAutoRefreshStatus,
		cacheManage,
	}
}
