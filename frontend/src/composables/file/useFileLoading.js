const loading = ref(false)
const firstLoading = ref(false)
const directoryLoadError = ref('')
const directoryLoadState = ref('idle')

const resetDirectoryLoadState = () => {
	directoryLoadError.value = ''
	directoryLoadState.value = 'idle'
}
const beginDirectoryLoadState = () => {
	directoryLoadError.value = ''
	directoryLoadState.value = 'loading'
}
const finishDirectoryLoadState = () => {
	directoryLoadError.value = ''
	directoryLoadState.value = 'ready'
}
// Callers guard these transitions with their current request / route identity.
const setDirectoryLoadError = (message) => {
	directoryLoadError.value = message
	directoryLoadState.value = 'error'
}

import useGlobalConfigStore from '~/stores/global-config'
let globalConfigStore = useGlobalConfigStore()

// 是否启用骨架屏 loading
let skeletonLoading = computed(() => {
	let skeletonEnable = globalConfigStore.studentCloudConfig.skeleton.enable
	let skeletonShow = globalConfigStore.studentCloudConfig.skeleton.show

	// 如果启用了骨架屏
	if (skeletonEnable) {
		// 如果骨架屏模式为是 '始终加载'
		if (skeletonShow === 'always') {
			return loading.value
		} else {
			// 如果骨架屏模式为是仅 '首次加载'
			// 已经首次加载后, 则不使用骨架屏
			return firstLoading.value ? false : loading.value
		}
	} else {
		// 如果未启用骨架屏, 则直接返回 false
		return false
	}
})

// 是否显示普通 loading
let basicLoading = computed(() => {
	return skeletonLoading.value ? false : loading.value
})

export default function useFileLoading() {
	return {
		loading,
		firstLoading,
		skeletonLoading,
		basicLoading,
		directoryLoadError,
		directoryLoadState,
		resetDirectoryLoadState,
		beginDirectoryLoadState,
		finishDirectoryLoadState,
		setDirectoryLoadError,
	}
}
