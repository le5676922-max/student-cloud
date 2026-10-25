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
} from '~/utils/storage-list-controller.mjs'
import useStorageCopy from '~/composables/admin/storage/storage-copy'

export default function useStorageList(router) {
	// These refs belong to this page instance, never a previously visited session.
	const cacheManageVisible = ref(false)
	const currentCacheManageId = ref()
	const loading = ref(false)
	const sorting = ref(false)
	const loadError = ref('')
	const operationError = ref('')
	const storageList = ref([])
	const searchKey = ref('')
	const statusFilter = ref('all')
	const hasLoaded = ref(false)
	const storageContainerRef = ref(null)
	const { openDialog: openCopyStorageDialog } = useStorageCopy()
	let sortable
	let sortController
	let disposed = false
	const hasStorageFilters = computed(
		() =>
			Boolean(searchKey.value.trim()) ||
			normalizeStorageStatusFilter(statusFilter.value) !== 'all'
	)
	const sortingDisabled = computed(
		() => loading.value || sorting.value || hasStorageFilters.value
	)
	const statusOptions = computed(() => {
		const counts = storageStatusCounts(storageList.value)
		return storageStatusFilters.map((filter) => ({
			...filter,
			count: hasLoaded.value ? counts[filter.value] : null,
		}))
	})
	const clearFilters = () => {
		searchKey.value = ''
		statusFilter.value = 'all'
	}

	const runOperation = async (request, onSuccess) => {
		operationError.value = ''
		try {
			await request()
			if (!disposed) await onSuccess()
		} catch (error) {
			if (!disposed && !isStorageLoadCanceled(error)) {
				const committedMessage = storageCommittedFailureMessage(error)
				operationError.value =
					committedMessage || '操作结果尚未确认，请刷新页面确认后再操作。'
				// Refresh metadata only. A mutation must never be automatically retried.
				if (committedMessage) await init()
			}
		}
	}

	// 添加存储源
	const addStorage = () => {
		router.push('/admin/storage-edit')
	}

	// 编辑存储源
	const editStorage = (row) => {
		router.push('/admin/storage-edit/' + row.id)
	}

	// 删除存储源
	const deleteStorage = (row) => {
		ElMessageBox.confirm('是否确认删除？', '提示', {
			confirmButtonText: '确定',
			cancelButtonText: '取消',
			type: 'warning',
			callback: (action) => {
				if (action === 'confirm') {
					runOperation(
						() => deleteStorageReq(row.id),
						() => {
							ElMessage.success('删除成功')
							return init()
						}
					)
				}
			},
		})
	}

	// 响应关闭操作提示.
	const handleClose = (done) => {
		ElMessageBox.confirm('是否确认关闭？关闭后填写的数据不会保留。', {
			type: 'warning',
		})
			.then(() => {
				done()
			})
			.catch(() => {})
	}

	// 跳转到文件夹过滤设置
	const showFilterPage = (row) => {
		router.push('/admin/storage-filter/' + row.id)
	}

	// 跳转到文件夹文档设置
	const showReadmePage = (row) => {
		router.push('/admin/storage-readme/' + row.id)
	}

	// 跳转到文件夹密码设置
	const showPasswordPage = (row) => {
		router.push('/admin/storage-password/' + row.id)
	}

	// 切换存储源启用/关闭状态
	const switchEnableStatus = (row) => {
		ElMessageBox.confirm(
			`是否确认${row.enable ? '停用' : '启用'}存储源。`,
			'提示',
			{
				confirmButtonText: '确定',
				cancelButtonText: '取消',
				type: 'warning',
				callback: (action) => {
					if (action === 'confirm') {
						let enableStatus = row.enable ? 'disable' : 'enable'
						runOperation(
							() => switchEnableReq(row.id, enableStatus),
							() => {
								ElMessage.success((row.enable ? '停用' : '启用') + '成功')
								row.enable = !row.enable
							}
						)
					}
				},
			}
		)
	}

	// 切换缓存启用/关闭状态
	const switchCacheEnableStatus = (row) => {
		let action = row.enableCache ? 'enable' : 'disable'
		runOperation(
			() => switchCacheEnableReq(row.id, action),
			() => ElMessage.success('修改成功')
		)
	}

	// 切换缓存启用/关闭自动刷新状态
	const switchCacheAutoRefreshStatus = (row) => {
		let action = row.autoRefreshCache ? 'start' : 'stop'
		runOperation(
			() => switchCacheAutoRefreshReq(row.id, action),
			() => ElMessage.success('修改成功')
		)
	}

	// 权限管理
	const showPermissionPage = (row) => {
		router.push('/admin/storage-premission/' + row.id)
	}

	// 缓存管理
	const cacheManage = (row) => {
		currentCacheManageId.value = row.id
		cacheManageVisible.value = true
	}

	// 响应操作指令
	const handleOperator = (command) => {
		switch (command.operator) {
			case 'edit':
				editStorage(command.storage)
				break
			case 'enable':
				switchEnableStatus(command.storage)
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

	// 设置可拖动排序
	const destroySort = () => {
		sortable?.destroy()
		sortable = undefined
	}

	// Sortable mutates the DOM. Restore it first so Vue alone renders saved order.
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
				if (sortingDisabled.value || disposed) return
				const reordered = reorderStorageItems(
					storageList.value,
					event.oldDraggableIndex,
					event.newDraggableIndex
				)
				if (!reordered) return
				sorting.value = true
				operationError.value = ''
				sortController = new AbortController()
				try {
					await storageSortReq(
						reordered.map(({ id, orderNum }) => ({ id, orderNum })),
						{
							signal: sortController.signal,
							showDefaultMsg: false,
						}
					)
					if (disposed) return
					storageList.value = reordered.map((item, orderNum) => ({
						...item,
						orderNum,
					}))
					ElMessage.success('调整排序成功')
				} catch (error) {
					if (!disposed && !isStorageLoadCanceled(error)) {
						const committedMessage = storageCommittedFailureMessage(error)
						operationError.value =
							committedMessage ||
							'排序未保存到当前界面，尚未确认后台结果。请刷新确认后再调整。'
						if (committedMessage) await loader.load()
					}
				} finally {
					if (!disposed) sorting.value = false
					sortController = undefined
				}
			},
		})
	}

	watch(sortingDisabled, (disabled) => sortable?.option('disabled', disabled))

	const loader = createStorageListLoader({
		request: ({ signal }) =>
			loadStorageListReq(undefined, { signal, showDefaultMsg: false }),
		onStart: () => {
			loading.value = true
			loadError.value = ''
			destroySort()
		},
		onLoaded: async (items, { isCurrent }) => {
			storageList.value = items
			hasLoaded.value = true
			await nextTick()
			if (isCurrent()) setSort()
		},
		onFailure: (error) => {
			loadError.value = storageListErrorMessage(error)
		},
		onSettled: () => {
			loading.value = false
		},
	})

	const init = () => (sorting.value ? Promise.resolve(false) : loader.load())
	onBeforeUnmount(() => {
		disposed = true
		loader.dispose()
		sortController?.abort()
		destroySort()
	})

	// 存储源列表计算属性, 用于显示右上角搜索框对应的搜索结果.
	const storageListSearchResult = computed(() =>
		filterStorageItems(storageList.value, searchKey.value, statusFilter.value)
	)
	const emptyState = computed(() => {
		if (loading.value || loadError.value || !hasLoaded.value) return ''
		if (storageListSearchResult.value.length) return ''
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
		statusFilter,
		statusOptions,
		hasStorageFilters,
		hasLoaded,
		emptyState,
		clearFilters,
		sortingDisabled,
		showingPreviousData: computed(
			() =>
				hasLoaded.value &&
				Boolean(loadError.value) &&
				storageList.value.length > 0
		),
		storageListSearchResult,
		handleOperator,
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
