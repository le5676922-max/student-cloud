import { h, ref, watch } from 'vue'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import {
	batchDeleteReq,
	newFolderReq,
	renameFileReq,
	renameFolderReq,
	moveFolderReq,
	moveFileReq,
	copyFileReq,
	copyFolderReq,
} from '~/api/home/file-operator'
import useStorageConfigStore from '~/stores/storage-config'
import useFileData from '~/composables/file/useFileData'
import useRouterData from '~/composables/useRouterData'
import useFileSelect from '~/composables/file/useFileSelect'
import useFilePwd from '~/composables/file/useFilePwd'
import useBatchOperatorResult from '~/composables/file/useBatchOperatorResult'
import selectFolder from '~/components/file/selectFolder'
import selfAxios from '~/http/request'
import {
	MAX_PRIVATE_DOWNLOAD_BYTES,
	pickPrivateDownloadFile,
	privateDownloadSize,
	privateDownloadUrl,
	validatePrivateDownloadResponse,
	writePrivateDownloadStream,
} from '~/utils/private-file-download.mjs'
import { uploadAccountIdentity } from './upload-lifecycle.mjs'
import {
	captureFileOperationContext,
	fileOperationSelectionType,
	isFileOperationContextCurrent,
} from './file-operation-context.mjs'

const storageConfigStore = useStorageConfigStore()
const { storageKey, currentPath } = useRouterData()
const { selectRows } = useFileSelect()
const { getPathPwd } = useFilePwd()
const { open: openBatchOperatorResultDialog } = useBatchOperatorResult()

// Shared across toolbar, context menu, keyboard shortcuts and folder picker.
export const operationBusy = ref(false)
let activeOperation = null
let pendingPrivateDownloads = 0

const readContext = () => ({
	storageKey: storageKey.value,
	path: currentPath.value,
	rootPath: storageConfigStore.user?.rootPath || '',
	accountIdentity: uploadAccountIdentity(storageConfigStore.loginInfo),
})
const permissionNames = {
	newFolder: '新建文件夹',
	rename: '重命名',
	move: '移动',
	copy: '复制',
	delete: '删除',
}
const hasPermission = (action, rows) => {
	if (storageConfigStore.folderConfig?.permission?.[action] !== true) {
		ElMessage.warning(`当前目录没有${permissionNames[action]}权限`)
		return false
	}
	if (rows.some((row) => row.type === 'FOLDER')) {
		const supportKey = {
			rename: 'supportRenameFolder',
			move: 'supportMoveFolder',
			copy: 'supportCopyFolder',
		}[action]
		if (
			supportKey &&
			storageConfigStore.folderConfig?.metadata?.[supportKey] !== true
		) {
			ElMessage.warning(`当前存储源不支持${permissionNames[action]}文件夹！`)
			return false
		}
	}
	return true
}
const beginOperation = (action, rows = []) => {
	if (operationBusy.value) {
		ElMessage.warning('已有文件操作正在进行，请完成或取消后再试')
		return null
	}
	if (!hasPermission(action, rows)) return null
	let context
	try {
		context = captureFileOperationContext(readContext(), rows)
	} catch (error) {
		ElMessage.warning(error.message)
		return null
	}
	const operation = { context, changed: false }
	// Navigating away and back also invalidates a pending confirmation.
	operation.stop = watch(
		() => {
			const current = readContext()
			return [
				current.storageKey,
				current.path,
				current.rootPath,
				current.accountIdentity,
			]
		},
		() => {
			operation.changed = true
		},
		{ flush: 'sync' }
	)
	activeOperation = operation
	operationBusy.value = true
	return operation
}
const isCurrent = (operation) =>
	activeOperation === operation &&
	!operation.changed &&
	isFileOperationContextCurrent(operation.context, readContext())
const canContinue = (operation, action) => {
	if (!isCurrent(operation)) {
		ElMessage.warning('目录、存储源或账号已变化，已取消本次操作，请重新选择')
		return false
	}
	return hasPermission(action, operation.context.rows)
}
const releaseOperation = (operation) => {
	operation.stop()
	if (activeOperation === operation) {
		activeOperation = null
		operationBusy.value = false
	}
}
const runOperation = async (operation, task, propagateErrors = false) => {
	try {
		return await task()
	} catch (error) {
		if (propagateErrors) throw error
		// HTTP presents request errors. DOM handlers and success-only callers
		// must not leak cancellation or request rejections.
		return false
	} finally {
		releaseOperation(operation)
	}
}
const showBatchResult = async (operation, data, title) => {
	if (!isCurrent(operation)) return
	if (!data.some((item) => item.success === false)) {
		ElMessage.success(`${title}成功`)
		return
	}
	const confirmed = await ElMessageBox.confirm(
		`部分文件${title}失败，是否查看详情？`,
		'提示',
		{
			confirmButtonText: '确定',
			cancelButtonText: '取消',
			closeOnClickModal: false,
			closeOnPressEscape: false,
			type: 'warning',
		}
	).catch(() => false)
	if (confirmed && isCurrent(operation)) openBatchOperatorResultDialog(data)
}

export default function useFileOperator() {
	const { loadFile } = useFileData()
	const refreshOriginal = async (operation) => {
		if (isCurrent(operation)) await loadFile({ path: operation.context.path })
	}
	const downloadFileUseWindowOpenMode = (url) => window.open(url)
	const downloadPrivateFile = async (row) => {
		if (pendingPrivateDownloads >= 2) {
			ElMessage.warning('已有文件正在下载，请稍后重试')
			return
		}
		let stop = () => {}
		let closeConfirmation = () => {}
		let closeProgress = () => {}
		const controller = new AbortController()
		let changed = false
		pendingPrivateDownloads++
		try {
			const original = captureFileOperationContext(readContext(), [row])
			if (!original.accountIdentity)
				throw new Error('请登录后下载不超过50MiB的文件')
			const expectedSize = privateDownloadSize(row.size)
			const name = original.rows[0].name
			// eslint-disable-next-line no-control-regex -- Strip unsafe filename control characters.
			const filename = name.replace(/[\\/\u0000-\u001f]/g, '_') || 'download'
			const url = privateDownloadUrl(row.url, original.storageKey)
			stop = watch(
				() => {
					const current = readContext()
					return [
						current.storageKey,
						current.path,
						current.rootPath,
						current.accountIdentity,
						storageConfigStore.folderConfig.permission.download,
					]
				},
				() => {
					changed = true
					controller.abort()
					const close = closeConfirmation
					closeConfirmation = () => {}
					close()
				},
				{ flush: 'sync' }
			)
			const current = () =>
				!changed &&
				!controller.signal.aborted &&
				isFileOperationContextCurrent(original, readContext()) &&
				storageConfigStore.folderConfig.permission.download === true
			if (storageConfigStore.globalConfig.enableNormalDownloadConfirm) {
				const confirmed = await ElMessageBox.confirm(
					({ close }) => {
						// Close this confirmation only; unrelated dialogs must stay open.
						closeConfirmation = close
						return `是否确认下载文件「${name}」？`
					},
					'提示',
					{
						confirmButtonText: '确定',
						cancelButtonText: '取消',
						type: 'info',
					}
				).catch(() => false)
				closeConfirmation = () => {}
				if (!confirmed) return
			}
			if (!current()) return
			// This must be the first awaited operation after the user's confirmation.
			// Browsers without an active save gesture retain the bounded Blob path.
			const handle = await pickPrivateDownloadFile(window, filename)
			if (!current()) return
			const downloadedBytes = ref(0)
			if (handle) {
				const progress = ElMessage.info({
					message: () =>
						h(
							'span',
							`正在保存「${name}」 ${Math.floor(
								(downloadedBytes.value / (expectedSize || 1)) * 100
							)}%`
						),
					duration: 0,
					showClose: true,
				})
				closeProgress = () => progress.close()
			}
			const response = await selfAxios({
				method: 'get',
				url,
				config: {
					...(handle ? { adapter: 'fetch' } : {}),
					responseType: handle ? 'stream' : 'blob',
					maxContentLength: MAX_PRIVATE_DOWNLOAD_BYTES,
					responseIntercept: false,
					showDefaultMsg: false,
					timeout: 120000,
					signal: controller.signal,
				},
			})
			if (handle) {
				await writePrivateDownloadStream({
					response,
					handle,
					expectedSize,
					signal: controller.signal,
					isCurrent: current,
					abortNetwork: () => controller.abort(),
					onProgress: (received) => {
						downloadedBytes.value = received
					},
				})
				if (current()) ElMessage.success(`已保存「${name}」`)
				return
			}
			if (!current()) return
			validatePrivateDownloadResponse(response, expectedSize)
			if (
				!(response.data instanceof Blob) ||
				response.data.size !== expectedSize
			)
				throw new Error('文件下载响应无效')
			const objectUrl = URL.createObjectURL(response.data)
			const anchor = document.createElement('a')
			try {
				anchor.href = objectUrl
				anchor.download = filename
				anchor.style.display = 'none'
				document.body.append(anchor)
				anchor.click()
			} finally {
				anchor.remove()
				setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
			}
		} catch (error) {
			controller.abort()
			if (
				!changed &&
				error?.name !== 'AbortError' &&
				error?.code !== 'ERR_CANCELED'
			)
				ElMessage.error('下载未完成，请刷新目录并检查权限或网络后重试')
		} finally {
			closeProgress()
			stop()
			pendingPrivateDownloads--
		}
	}
	const batchDownloadFile = (row) => {
		if (storageConfigStore.folderConfig.permission.download === false) return
		if (!row?.name && !selectRows.value?.length) {
			ElMessage.warning('请至少选择一个文件')
			return
		}
		if (!row?.name && selectRows.value.length === 1) row = selectRows.value[0]
		if (row?.authenticatedContent === true) {
			void downloadPrivateFile(row)
			return
		}
		const performDownload = () => {
			if (row?.name) downloadFileUseWindowOpenMode(row.url)
		}
		if (storageConfigStore.globalConfig.enableNormalDownloadConfirm) {
			ElMessageBox.confirm(`是否确认下载文件「${row?.name}」？`, '提示', {
				confirmButtonText: '确定',
				cancelButtonText: '取消',
				type: 'info',
			})
				.then(performDownload)
				.catch(() => {})
		} else performDownload()
	}

	const createFolderAt = async (
		operation,
		path,
		{ stillOpen = () => true, refresh = true, onCreated } = {}
	) => {
		const result = await ElMessageBox.prompt(
			`在「${path}」下创建文件夹，请输入要创建的文件夹名称`,
			'提示',
			{
				confirmButtonText: '确定',
				cancelButtonText: '取消',
				draggable: true,
				inputValidator(value) {
					if (!value) return '文件夹名称不能为空'
					if (value.includes('/'))
						return '文件夹名称不能包含 / (不支持多级创建)'
					return true
				},
			}
		).catch(() => null)
		if (!result || !stillOpen() || !canContinue(operation, 'newFolder'))
			return false
		try {
			await newFolderReq(
				{ storageKey: operation.context.storageKey, path, name: result.value },
				{ uploadContext: operation.context }
			)
			onCreated?.()
			if (isCurrent(operation)) ElMessage.success('创建成功')
		} finally {
			if (refresh) await refreshOriginal(operation)
		}
	}
	// Keep the public promise's success/failure contract; UI cancellation is benign.
	const newFolder = (basePath) => {
		const operation = beginOperation('newFolder')
		if (!operation) return Promise.resolve(false)
		const path =
			typeof basePath === 'string' ? basePath : operation.context.path
		const pending = runOperation(
			operation,
			() => createFolderAt(operation, path),
			true
		)
		// A raw DOM caller may ignore the returned promise; awaiting callers still
		// receive request failures and must never continue as though mkdir succeeded.
		void pending.catch(() => {})
		return pending
	}
	const rename = () => {
		if (selectRows.value.length !== 1) {
			ElMessage.warning('请先选中一个文件或文件夹！')
			return
		}
		const operation = beginOperation('rename', selectRows.value)
		if (!operation) return
		const row = operation.context.rows[0]
		void runOperation(operation, async () => {
			const result = await ElMessageBox.prompt(
				`将「${row.name}」修改为：`,
				'提示',
				{
					confirmButtonText: '确定',
					cancelButtonText: '取消',
					inputValue: row.name,
					inputValidator: (value) => !!value,
					inputErrorMessage: '名称不能为空。',
				}
			).catch(() => null)
			if (!result || !canContinue(operation, 'rename')) return
			const reqMethod = row.type === 'FILE' ? renameFileReq : renameFolderReq
			const loading = ElLoading.service({
				fullscreen: true,
				text: '重命名中...',
				background: 'rgba(255, 255, 255, 0.6)',
			})
			try {
				await reqMethod({
					storageKey: operation.context.storageKey,
					path: row.path,
					name: row.name,
					newName: result.value,
				})
				if (isCurrent(operation)) ElMessage.success('重命名成功')
			} finally {
				loading.close()
				await refreshOriginal(operation)
			}
		})
	}
	const moveOrCopy = (action) => {
		const rows = selectRows.value
		if (!rows.length) {
			ElMessage.warning('请先至少选中一个文件或文件夹！')
			return
		}
		const type = fileOperationSelectionType(rows)
		if (!type) {
			ElMessage.warning('请分别选择文件或文件夹进行移动、复制')
			return
		}
		const operation = beginOperation(action, rows)
		if (!operation) return
		const title = permissionNames[action]
		const reqMethod =
			action === 'copy'
				? type === 'FILE'
					? copyFileReq
					: copyFolderReq
				: type === 'FILE'
				? moveFileReq
				: moveFolderReq
		const nameList = operation.context.rows.map((row) => row.name)
		void runOperation(
			operation,
			() =>
				new Promise((resolve) => {
					operation.pickerOpen = true
					operation.requesting = false
					operation.childBusy = false
					operation.childChanged = false
					let finishingCanceledPicker = false
					const finishCanceledPicker = () => {
						if (
							operation.pickerOpen ||
							operation.requesting ||
							operation.childBusy ||
							finishingCanceledPicker
						)
							return
						finishingCanceledPicker = true
						// Child mkdir refreshes only the picker while it is open. On
						// closing, reconcile the main listing once if mkdir succeeded.
						const refreshed = operation.childChanged
							? refreshOriginal(operation)
							: Promise.resolve()
						void refreshed.then(
							() => resolve(false),
							() => resolve(false)
						)
					}
					// This capability is passed only to this picker, never exposed by the
					// public newFolder API. Child work cannot unlock its parent operation.
					const createPickerFolder = (basePath) => {
						if (
							!operation.pickerOpen ||
							operation.requesting ||
							operation.childBusy
						) {
							ElMessage.warning('请先完成当前文件操作')
							return Promise.resolve(false)
						}
						if (!canContinue(operation, 'newFolder'))
							return Promise.resolve(false)
						operation.childBusy = true
						return createFolderAt(operation, basePath, {
							stillOpen: () => operation.pickerOpen,
							refresh: false,
							onCreated: () => {
								operation.childChanged = true
							},
						})
							.then((result) =>
								operation.pickerOpen && isCurrent(operation) ? result : false
							)
							.finally(() => {
								operation.childBusy = false
								finishCanceledPicker()
							})
					}
					selectFolder(
						title,
						operation.context.storageKey,
						operation.context.path,
						({ value: targetPath, callback }) => {
							if (!operation.pickerOpen || operation.requesting) return
							if (!canContinue(operation, action)) {
								operation.pickerOpen = false
								callback.stopLoading()
								callback.closeDialog()
								finishCanceledPicker()
								return
							}
							if (operation.childBusy) {
								ElMessage.warning('请先完成新建文件夹操作')
								callback.stopLoading()
								return
							}
							if (targetPath === operation.context.path) {
								ElMessage.warning('你选择的目标文件夹与源文件夹相同！')
								callback.stopLoading()
								return
							}
							operation.requesting = true
							const submit = async () => {
								try {
									const response = await reqMethod({
										storageKey: operation.context.storageKey,
										path: operation.context.path,
										nameList,
										targetPath,
										targetNameList: [...nameList],
										srcPathPassword: getPathPwd(
											operation.context.path,
											false,
											operation.context
										),
										targetPathPassword: getPathPwd(
											targetPath,
											false,
											operation.context
										),
									})
									await showBatchResult(operation, response.data, title)
								} finally {
									operation.pickerOpen = false
									callback.stopLoading()
									callback.closeDialog()
									await refreshOriginal(operation)
								}
							}
							void submit().then(resolve, () => resolve(false))
						},
						() => {
							operation.pickerOpen = false
							finishCanceledPicker()
						},
						createPickerFolder
					)
				})
		)
	}
	const copyTo = () => moveOrCopy('copy')
	const moveTo = () => moveOrCopy('move')
	const batchDelete = () => {
		if (!selectRows.value.length) {
			ElMessage.warning('请先至少选中一个文件或文件夹！')
			return
		}
		const operation = beginOperation('delete', selectRows.value)
		if (!operation) return
		const rows = operation.context.rows
		const folders = rows.filter((row) => row.type === 'FOLDER').length
		const files = rows.filter((row) => row.type === 'FILE').length
		let message = rows.length === 1 ? '是否确认删除 ' : '是否确认批量删除 '
		if (storageConfigStore.folderConfig?.metadata?.recycleBin === true)
			message = '是否将以下内容移入回收站（保留7天，仍占用容量）：'
		message += [folders && `${folders} 个文件夹`, files && `${files} 个文件`]
			.filter(Boolean)
			.join('，')
		if (
			folders &&
			!storageConfigStore.folderConfig.metadata.supportDeleteNotEmptyFolder
		)
			message +=
				'<span class="font-bold text-red-400">(不支持删除非空文件夹)</span>'
		void runOperation(operation, async () => {
			const confirmed = await ElMessageBox.confirm(`${message}?`, '提示', {
				confirmButtonText: '确定',
				cancelButtonText: '取消',
				draggable: true,
				dangerouslyUseHTMLString: true,
			}).catch(() => false)
			if (!confirmed || !canContinue(operation, 'delete')) return
			const loading = ElLoading.service({
				text: '删除中...',
				background: 'rgba(0, 0, 0, .3)',
			})
			try {
				const response = await batchDeleteReq({
					storageKey: operation.context.storageKey,
					deleteItems: rows.map((row) => ({
						...row,
						password: getPathPwd(row.path, false, operation.context),
					})),
				})
				await showBatchResult(operation, response.data, '删除')
			} finally {
				loading.close()
				await refreshOriginal(operation)
			}
		})
	}
	return {
		operationBusy,
		batchDownloadFile,
		rename,
		newFolder,
		moveTo,
		copyTo,
		batchDelete,
	}
}
