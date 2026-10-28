import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import useStorageConfigStore from '~/stores/storage-config'
import useRouterData from '~/composables/useRouterData'
import { captureControlledShare } from '~/utils/controlled-sharing.mjs'

const createShareDialogRef = ref()
let controlledShareDialogRef

import useFileSelect from './useFileSelect'
const { selectStatistics, selectRows } = useFileSelect()

/**
 * 文件分享相关功能
 */
export default function useFileShare() {
	const store = useStorageConfigStore()
	const { storageKey, currentPath } = useRouterData()
	/**
	 * 打开创建分享对话框
	 */
	const openCreateShareDialog = () => {
		if (store.globalConfig?.accountModel === 'independent') {
			if (
				store.loginInfo?.isLogin !== true ||
				!['USER', 'TENANT_ADMIN'].includes(store.loginInfo?.role)
			) {
				ElMessage.warning('请使用工作空间成员账号登录后分享文件')
				return
			}
			if (
				store.folderConfig?.permission?.read !== true &&
				store.folderConfig?.permission?.download !== true
			) {
				ElMessage.warning('当前目录没有文件读取权限')
				return
			}
			try {
				const snapshot = captureControlledShare(selectRows.value, {
					storageKey: storageKey.value,
					path: currentPath.value,
				})
				controlledShareDialogRef?.value?.openDialog(
					snapshot,
					selectRows.value[0].name
				)
			} catch (failure) {
				ElMessage.warning(failure.message)
			}
			return
		}
		// 至少选择 1 个即可
		if (!selectStatistics.value.isSelected) {
			ElMessage.warning('请先选择要分享的文件或文件夹')
			return
		}

		createShareDialogRef.value?.openDialog()
	}

	/**
	 * 设置创建分享对话框引用
	 * @param {Object} ref - 对话框组件引用
	 */
	const setCreateShareDialogRef = (ref) => {
		createShareDialogRef.value = ref.value
	}
	const setControlledShareDialogRef = (ref) => {
		controlledShareDialogRef = ref
	}

	return {
		// 对话框相关
		openCreateShareDialog,
		setCreateShareDialogRef,
		setControlledShareDialogRef,
	}
}
