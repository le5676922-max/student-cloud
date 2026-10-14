<template>
	<el-dialog
		v-model="isShow"
		append-to-body
		:destroy-on-close="true"
		:title="props.title"
		:show-close="false"
		draggable
		top="5vh"
		width="80%"
		@close="closeDialog"
	>
		<v-md-editor v-model="readmeText" height="70vh"></v-md-editor>
		<template #footer>
			<span class="dialog-footer">
				<el-button @click="closeDialog">取消</el-button>
				<el-button type="primary" @click="saveDialog">保存</el-button>
			</span>
		</template>
	</el-dialog>
</template>

<script setup>
// markdown editor 组件懒加载, 节约首屏打开时间
const VMdEditor = defineAsyncComponent(() =>
	import('~/components/md-preview/MarkdownEditor.vue')
)

const readmeText = ref('')
const isShow = true

const props = defineProps({
	visible: {
		type: Boolean,
		required: true,
	},
	title: {
		type: String,
		default: '',
	},
	modelValue: {
		type: String,
		default: '',
	},
})

const emit = defineEmits(['update:modelValue', 'update:visible'])

onMounted(() => {
	readmeText.value = props.modelValue
})

const closeDialog = () => {
	emit('update:visible', false)
}

const saveDialog = () => {
	emit('update:modelValue', readmeText.value)
	emit('update:visible', false)
}
</script>

<style lang="scss" scoped></style>
