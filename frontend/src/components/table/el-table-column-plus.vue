<template>
	<el-table-column
		v-if="isNotMobile && props.type === 'selection'"
		v-bind="props"
	/>
	<el-table-column v-else-if="isNotMobile" v-bind="props">
		<template #default="cellScope">
			<slot
				v-bind="{
					row: cellScope.row,
					$index: cellScope.$index,
					column: cellScope.column,
				}"
			>
				<div>
					{{ cellScope.row[props.prop] }}
				</div>
			</slot>
		</template>
		<template #header>
			<slot name="header" :column="props">
				{{ props.label }}
			</slot>
		</template>
	</el-table-column>
	<div v-else class="w-full">
		<slot v-bind="{ row: props.scope.row, $index: props.scope.$index }">
			<div>
				{{ props.scope.row[props.prop] }}
			</div>
		</slot>
	</div>
</template>

<script setup>
import { isNotMobile } from '~/utils'
import { ElTableColumn } from 'element-plus'
import 'element-plus/es/components/table-column/style/css'

defineOptions({
	inheritAttrs: false,
})

const props = defineProps({
	type: {
		type: String,
		default: 'default',
	},
	label: { type: String, default: undefined },
	className: { type: String, default: undefined },
	labelClassName: { type: String, default: undefined },
	property: { type: String, default: undefined },
	prop: { type: String, default: undefined },
	width: {
		type: [String, Number],
		default: '',
	},
	minWidth: {
		type: [String, Number],
		default: '',
	},
	renderHeader: { type: Function, default: undefined },
	sortable: {
		type: [Boolean, String],
		default: false,
	},
	sortMethod: { type: Function, default: undefined },
	sortBy: { type: [String, Function, Array], default: undefined },
	resizable: {
		type: Boolean,
		default: true,
	},
	columnKey: { type: String, default: undefined },
	align: { type: String, default: undefined },
	headerAlign: { type: String, default: undefined },
	showOverflowTooltip: {
		type: [Boolean, Object],
		default: void 0,
	},
	fixed: { type: [Boolean, String], default: undefined },
	formatter: { type: Function, default: undefined },
	selectable: { type: Function, default: undefined },
	reserveSelection: Boolean,
	filterMethod: { type: Function, default: undefined },
	filteredValue: { type: Array, default: undefined },
	filters: { type: Array, default: undefined },
	filterPlacement: { type: String, default: undefined },
	filterMultiple: {
		type: Boolean,
		default: true,
	},
	filterClassName: { type: String, default: undefined },
	index: { type: [Number, Function], default: undefined },
	sortOrders: {
		type: Array,
		default: () => {
			return ['ascending', 'descending', null]
		},
		validator: (val) => {
			return val.every((order) =>
				['ascending', 'descending', null].includes(order)
			)
		},
	},
	scope: {
		type: Object,
		default: () => ({ row: {} }),
	},
})
</script>

<style lang="scss">
.el-input,
.el-select,
.el-date-editor {
	@apply w-full;
}
</style>
