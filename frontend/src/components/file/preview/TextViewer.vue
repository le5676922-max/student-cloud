<template>
	<VueCodeEditor
		v-if="fileContent"
		:file-name="fileName"
		:model-value="fileContent"
		:language="fileSuffix"
		:readonly="!storageConfigStore.permission.upload"
	></VueCodeEditor>
</template>

<script setup>
import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import { getFileSuffix } from '~/utils'
import VueCodeEditor from '~/components/vue-codemirror/index.vue'

// 组件接收的属性：
//  fileUrl:    文件下载路径
//  fileName:   文件名
const props = defineProps({
	fileUrl: { type: String, default: undefined },
	fileName: { type: String, default: undefined },
})

// 获取文件名后缀
const fileSuffix = getFileSuffix(props.fileName)

// 文件内容
const fileContent = ref(null)

import { getFileTextReq } from '~/api/home/common'

// 挂载时，加载文件内容，并初始化播放器
onMounted(() => {
	let fileUrl = props.fileUrl
	getFileTextReq(fileUrl)
		.then((res) => {
			fileContent.value = res.request.response
		})
		.catch(() => {
			ElMessage({
				message: `加载文本文件失败. 请检查此地址是否有效或是否限制了跨域访问.`,
				type: 'error',
				duration: 0,
				showClose: true,
				grouping: true,
			})
		})
})
</script>
