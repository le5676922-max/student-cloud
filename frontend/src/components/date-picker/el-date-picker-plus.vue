<template>
	<el-date-picker
		v-if="isNotMobile"
		v-bind="$attrs"
		v-model="dataRange"
		type="daterange"
		:default-time="defaultTime"
		:value-format="valueFormat"
		:range-separator="rangeSeparator"
		:start-placeholder="startPlaceholder"
		:end-placeholder="endPlaceholder"
	/>
	<div v-else class="el-date-editor">
		<el-date-picker
			v-model="startDate"
			class="el-range-input-plus is-left"
			:value-format="valueFormat"
			:default-time="defaultTime[0]"
			:editable="false"
			:disabled-date="disableStartDate"
			:placeholder="props.startPlaceholder"
		/>
		<span class="el-range-separator-plus">{{ props.rangeSeparator }}</span>
		<el-date-picker
			v-model="endDate"
			class="el-range-input-plus is-right"
			:value-format="valueFormat"
			:default-time="defaultTime[1]"
			:editable="false"
			:disabled-date="disableEndDate"
			:placeholder="props.endPlaceholder"
		/>
	</div>
</template>

<script setup>
import { defaultTime as defaultRangeTime, isNotMobile } from '~/utils'

defineOptions({
	inheritAttrs: false,
})

const props = defineProps({
	modelValue: {
		type: Array,
		default: () => [],
	},
	type: {
		type: String,
		default: 'date',
	},
	rangeSeparator: {
		type: String,
		default: '-',
	},
	startPlaceholder: {
		type: String,
		default: '开始日期',
	},
	endPlaceholder: {
		type: String,
		default: '结束日期',
	},
	valueFormat: {
		type: String,
		default: 'YYYY-MM-DD HH:mm:ss',
	},
	defaultTime: {
		type: Array,
		default: () => [...defaultRangeTime],
	},
})

const emit = defineEmits(['update:modelValue'])
const dataRange = computed({
	get: () => props.modelValue || [],
	set: (value) => {
		emit('update:modelValue', value)
	},
})

const startDate = computed({
	get: () => props.modelValue?.[0],
	set: (value) => emit('update:modelValue', [value, props.modelValue?.[1]]),
})
const endDate = computed({
	get: () => props.modelValue?.[1],
	set: (value) => emit('update:modelValue', [props.modelValue?.[0], value]),
})

const disableStartDate = (time) => {
	let endDate = props.modelValue?.[1]
	if (typeof endDate === 'string') {
		endDate = new Date(endDate)
	}
	return endDate ? time.getTime() > endDate.getTime() : false
}

const disableEndDate = (time) => {
	let startDate = props.modelValue?.[0]
	if (typeof startDate === 'string') {
		startDate = new Date(startDate)
	}
	return startDate ? time.getTime() < startDate.getTime() : false
}
</script>

<style lang="scss" scoped>
.el-date-editor {
	width: var(--el-date-editor-daterange-width);
	border-radius: var(--el-border-radius-base);
	@apply inline-flex border-[1.5px] border-gray-200 hover:border-gray-300;

	// 隐藏原始的输入框边框
	:deep(.el-input__wrapper) {
		box-shadow: none;
	}

	// 隐藏右侧的默认日期图标
	:deep(.el-range-input-plus.is-right .el-input__prefix) {
		display: none;
	}

	// 输入框居中
	:deep(.el-input__inner) {
		@apply text-center ml-3;
	}

	// 清理图标固定宽度，避免抖动
	:deep(.el-input__suffix-inner) {
		@apply w-3;
	}
}

// 分隔符样式
.el-range-separator-plus {
	@apply text-xs text-gray-700 my-auto;
}
</style>
