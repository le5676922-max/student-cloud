<template>
	<el-table
		v-if="isNotMobile"
		ref="elTableRef"
		v-bind="props"
		:data="props.data"
		:row-key="props.rowKey"
		:border="props.border"
		@sort-change="emit('sortChange', $event)"
		@selection-change="selectionChange"
	>
		<slot></slot>
	</el-table>
	<div v-else class="h-full w-full overflow-auto">
		<template v-for="(item, i) in props.data" :key="item.id">
			<el-card shadow="never">
				<el-form label-position="top">
					<el-checkbox
						v-if="isNeedSelection"
						v-model="item['_checked']"
						label="选中"
						@change="handleSelectionChange"
					/>
					<template v-for="citem in $slots.default?.() || []" :key="citem">
						<template v-if="citem.props">
							<template v-if="citem.props.type === 'index'"> </template>
							<template v-else-if="citem.props.type === 'expand'"> </template>
							<template v-else-if="citem.props.type === 'selection'">
							</template>
							<el-form-item :label="citem.props.label">
								<template v-if="hasHeaderSlot(citem)" #label>
									<MobileHeaderRenderer :column-v-node="citem" />
								</template>
								<template v-if="citem && citem.children">
									<component
										:is="citem"
										:scope="{ row: item, $index: i }"
										:prop="citem.props.prop"
									></component>
								</template>
								<template v-else>
									<component
										:is="citem"
										:scope="{ row: item, $index: i }"
									></component>
								</template>
							</el-form-item>
						</template>
					</template>
				</el-form>
			</el-card>
			<el-divider v-if="i < props.data.length - 1"></el-divider>
		</template>
	</div>
</template>

<script setup lang="ts">
import { defineComponent } from 'vue'
import { isNotMobile } from '~/utils'
import {
	ElTable,
	ElCard,
	ElForm,
	ElFormItem,
	ElDivider,
	ElCheckbox,
	TableProps,
} from 'element-plus'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/card/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/divider/style/css'
import 'element-plus/es/components/checkbox/style/css'

defineOptions({
	inheritAttrs: false,
})

const props = withDefaults(
	defineProps<TableProps<any> & { customAttr?: string }>(),
	{
		fit: true,
		showHeader: true,
		selectOnIndeterminate: true,
		indent: 16,
		treeProps: () => {
			return {
				hasChildren: 'hasChildren',
				children: 'children',
				checkStrictly: false,
			}
		},
		style: () => ({}),
		className: '',
		tableLayout: 'fixed',
		scrollbarTabindex: void 0,
		allowDragLastColumn: true,
		data: () => [],
		customAttr: undefined,
	}
)

const slots = useSlots()
const emit = defineEmits(['selectionChange', 'sortChange'])

const hasHeaderSlot = (columnVNode: any) => {
	return typeof columnVNode?.children?.header === 'function'
}

const MobileHeaderRenderer = defineComponent({
	name: 'MobileHeaderRenderer',
	props: {
		columnVNode: {
			type: Object,
			required: true,
		},
	},
	setup(props) {
		return () => {
			const headerSlot = props.columnVNode?.children?.header
			if (typeof headerSlot !== 'function') {
				return props.columnVNode?.props?.label ?? ''
			}
			return headerSlot({
				column: props.columnVNode.props,
			})
		}
	},
})

const selectionChange = (selection: any[]) => {
	emit('selectionChange', selection)
}

const isNeedSelection = computed(() => {
	if (slots['default'] == null) {
		return false
	}
	return slots['default']().some(
		(item: any) => item && item.props && item.props.type === 'selection'
	)
})

const handleSelectionChange = () => {
	const selection = props.data.filter((item) => item['_checked'])
	selectionChange(selection)
}

const elTableRef = ref()
const getSelectionRows = () => {
	if (isNotMobile.value) {
		return elTableRef.value.getSelectionRows()
	}
	return props.data.filter((item) => item['_checked'])
}

defineExpose({
	getSelectionRows,
})
</script>
