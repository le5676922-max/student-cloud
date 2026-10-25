<template>
	<div class="student-cloud-select-folder-body">
		<el-dialog
			width="400px"
			:model-value="props.show"
			:title="title"
			@update:model-value="
				(value) => {
					if (!value) handlerClose('cancel')
				}
			"
		>
			<div class="student-cloud-select-folder-breadcrumb h-12">
				<breadcrumb
					class="h-12"
					:items="breadcrumbData"
					@breadcrumb-click="onClickBreadcrumb"
				/>
			</div>
			<div v-loading="listLoading" class="student-cloud-select-folder-body">
				<nav class="space-y-1" aria-label="Sidebar">
					<a
						v-for="item in fileList"
						:key="item.name"
						:href="item.href"
						:class="[
							'text-gray-600 hover:bg-gray-50 hover:text-gray-900',
							'group flex items-center rounded-md px-3 py-2 text-sm font-medium select-none',
						]"
						@click="onChangePath(item)"
					>
						<i-custom-file-type-folder
							name="file-type-folder"
							class="text-2xl mr-3 h-6 w-6 flex-shrink-0"
						/>
						<span class="truncate select-none">{{ item.name }}</span>
					</a>
					<div v-if="fileList.length === 0">
						<div class="flex items-center justify-center h-20">
							<span class="text-gray-500">无文件夹</span>
						</div>
					</div>
				</nav>
			</div>
			<div class="student-cloud-select-folder-footer">
				<div>
					<i-lucide-folder-plus
						v-show="storageConfigStore.permission.newFolder"
						class="h-6 w-6 cursor-pointer text-blue-400"
						:class="{
							'pointer-events-none opacity-50': folderCreating || loading,
						}"
						:aria-disabled="folderCreating || loading"
						:title="folderCreating ? '正在新建文件夹...' : '新建文件夹'"
						@click="onNewFolder"
					/>
				</div>
				<div>
					<el-button type="info" @click="handlerClose('cancel')"
						>取消</el-button
					>
					<el-button
						type="primary"
						:loading="loading"
						:disabled="folderCreating"
						@click="handlerClose('confirm')"
						>确认</el-button
					>
				</div>
			</div>
		</el-dialog>
	</div>
</template>

<script setup>
import { concatPath } from '~/utils'
import { ElMessage } from 'element-plus'

import { inject } from 'vue'
import { loadFileListReq } from '~/api/home/home'

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

import useFilePwd from '~/composables/file/useFilePwd'
let { getPathPwd, putPathPwd, popPassword } = useFilePwd()

import useFileOperator from '~/composables/file/useFileOperator'
const { newFolder } = useFileOperator()

const folderCreating = ref(false)
let pickerActive = true
onBeforeUnmount(() => {
	pickerActive = false
})
const onNewFolder = async () => {
	if (folderCreating.value || loading.value) return
	folderCreating.value = true
	try {
		const createFolder = props.createFolder || newFolder
		const result = await createFolder(selectPath.value)
		if (result !== false && pickerActive) await loadFileList()
	} catch {
		// Request errors are already displayed by the HTTP layer.
	} finally {
		folderCreating.value = false
	}
}

// 组件传参及回写
const props = defineProps({
	show: {
		type: Boolean,
		default: false,
	},
	title: {
		type: String,
		default: '选择文件夹',
	},
	storageKey: {
		type: String,
		required: true,
	},
	basePath: {
		type: String,
		default: '/',
	},
	onClose: {
		type: Function,
		default: () => {},
	},
	createFolder: {
		type: Function,
		default: null,
	},
})

const loading = inject('loading')

const selectPath = ref('')
const fileList = ref([])
const breadcrumbData = computed(() => {
	let data = [
		{
			name: '根目录',
			href: '/',
			disable: false,
		},
	]

	let paths = selectPath.value.split('/')
	if (paths) {
		// 去除 paths 中所有空白字符
		paths = paths.filter((item) => {
			return !!item
		})

		paths.forEach((item, index, arr) => {
			let breadcrumbItem = {
				name: item,
				href: concatPath('/', arr.slice(0, index + 1).join('/')),
				disable: index === arr.length - 1,
			}
			data.push(breadcrumbItem)
		})
	}
	return data
})

onMounted(() => {
	// 初始化路径
	selectPath.value = props.basePath
	// 加载文件列表
	loadFileList()
})

const listLoading = ref(false)

const loadFileList = async (password, rememberPassword) => {
	const param = {
		storageKey: props.storageKey,
		path: selectPath.value,
		password: password || getPathPwd(selectPath.value, true),
	}
	listLoading.value = true
	loadFileListReq(param)
		.then((res) => {
			// 过滤所有 type 为 FOLDER 的数据
			res.data.files = res.data.files.filter((item) => {
				return item.type === 'FOLDER'
			})

			let passwordPattern = res.data.passwordPattern
			putPathPwd(passwordPattern, param.password, rememberPassword)

			fileList.value = res.data.files
		})
		.catch((error) => {
			const onConfirm = (password, rememberPassword) => {
				loadFileList(password, rememberPassword)
			}
			let data = error.response.data
			// 如果需要密码或密码错误进行提示, 并弹出输入密码的框.
			if (data.code === constant.responseCode.INVALID_PASSWORD) {
				ElMessage.warning('文件夹访问密码不正确，请重新输入（不是登录密码）。')
				popPassword(onConfirm)
			} else if (data.code === constant.responseCode.REQUIRED_PASSWORD) {
				popPassword(onConfirm)
			} else {
				ElMessage.error(data.msg)
			}
		})
		.finally(() => {
			listLoading.value = false
		})
}

const onChangePath = (file) => {
	selectPath.value = concatPath(file.path + '/' + file.name)
	loadFileList()
}

const onClickBreadcrumb = (item) => {
	if (item.href) {
		selectPath.value = decodeURI(item.href)
		loadFileList()
	}
}

// 响应关闭事件
const emit = defineEmits(['update:show'])
const handlerClose = (type) => {
	const result = {
		value: selectPath.value,
		type,
	}

	if (type === 'confirm') {
		loading.value = true
	}

	emit('update:show', false)
	props.onClose(result)
}
</script>

<style lang="scss" scoped>
.student-cloud-select-folder-body {
	@apply h-96 overflow-y-auto;

	:deep(.el-dialog) {
		@apply w-96;
	}

	:deep(.el-dialog__body) {
		@apply py-0;
	}
}

.student-cloud-select-folder-footer {
	@apply h-20 flex items-center justify-between;
}
</style>
