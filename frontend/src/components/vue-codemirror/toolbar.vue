<template>
	<div class="toolbar">
		<div class="item">
			<label for="language">语言:</label>
			<el-select
				:model-value="config.language"
				placeholder="请选择"
				:disabled="disabled"
				class="w-32"
				size="small"
				@change="handleSelectLanguage"
			>
				<el-option
					v-for="option in languages"
					:key="option"
					:label="option"
					:value="option"
				>
				</el-option>
			</el-select>
		</div>
		<div class="item">
			<label for="language">编码:</label>
			<el-select
				:model-value="config.encoding"
				placeholder="请选择"
				:disabled="disabled"
				class="w-32"
				size="small"
				@change="handleSelectEncoding"
			>
				<el-option
					v-for="option in encodings"
					:key="option"
					:label="option"
					:value="option"
				>
				</el-option>
			</el-select>
		</div>
		<div class="item">
			<label for="theme">主题:</label>
			<el-select
				:model-value="config.theme"
				placeholder="请选择"
				:disabled="disabled"
				class="w-20"
				size="small"
				@update:model-value="updateConfig('theme', $event)"
			>
				<el-option
					v-for="option in ['default', ...themes]"
					:key="option"
					:label="option"
					:value="option"
				>
				</el-option>
			</el-select>
		</div>
		<div class="item">
			<label for="disabled">只读:</label>
			<el-checkbox
				id="disabled"
				:model-value="config.readonly"
				:disabled="disabled"
				size="small"
				@update:model-value="updateConfig('readonly', $event === true)"
			>
			</el-checkbox>
		</div>
		<div class="item">
			<label for="lineWrapping">自动换行:</label>
			<el-checkbox
				id="lineWrapping"
				:model-value="config.lineWrapping"
				:disabled="disabled"
				size="small"
				@update:model-value="updateConfig('lineWrapping', $event === true)"
			>
			</el-checkbox>
		</div>
		<div v-show="false" class="item">
			<label for="autofocus">autofocus:</label>
			<el-checkbox
				id="autofocus"
				:model-value="config.autofocus"
				:disabled="disabled"
				size="small"
				@update:model-value="updateConfig('autofocus', $event === true)"
			>
			</el-checkbox>
		</div>
		<div v-show="false" class="item">
			<label for="indentWithTab">indentWithTab:</label>
			<el-checkbox
				id="indentWithTab"
				:model-value="config.indentWithTab"
				:disabled="disabled"
				size="small"
				@update:model-value="updateConfig('indentWithTab', $event === true)"
			>
			</el-checkbox>
		</div>
		<div v-show="false" class="item">
			<label for="tabSize">tabSize:</label>
			<el-select
				id="tabSize"
				:model-value="config.tabSize"
				placeholder="请选择"
				:disabled="disabled"
				class="w-12"
				size="small"
				@update:model-value="updateConfig('tabSize', Number($event))"
			>
				<el-option
					v-for="option in [2, 4, 6, 8]"
					:key="option"
					:label="option"
					:value="option"
				>
				</el-option>
			</el-select>
		</div>
		<div v-show="false" class="item">
			<label for="height">height:</label>
			<el-select
				:model-value="config.height"
				placeholder="请选择"
				:disabled="disabled"
				class="w-16"
				size="small"
				@update:model-value="updateConfig('height', $event)"
			>
				<el-option
					v-for="option in ['auto', '200px', '40em', '60vh']"
					:key="option"
					:label="option"
					:value="option"
				>
				</el-option>
			</el-select>
		</div>
	</div>
</template>

<script setup lang="ts">
import { ElSelect, ElOption, ElCheckbox } from 'element-plus'
import { PropType } from 'vue'
import { ToolBarConfig } from './types'

const props = defineProps({
	disabled: {
		type: Boolean,
		default: false,
	},
	config: {
		type: Object as PropType<ToolBarConfig>,
		required: true,
	},
	languages: {
		type: Array as PropType<Array<string>>,
		required: true,
	},
	encodings: {
		type: Object as PropType<Record<string, string>>,
		required: false,
		default: () => ({}),
	},
	themes: {
		type: Array as PropType<Array<string>>,
		required: true,
	},
	readonly: {
		type: Boolean,
		default: false,
	},
})

const emits = defineEmits<{
	(e: 'language', data: string): void
	(e: 'encoding', data: string): void
	(e: 'update:config', data: ToolBarConfig): void
}>()

const updateConfig = <K extends keyof ToolBarConfig>(
	key: K,
	value: ToolBarConfig[K]
) => {
	emits('update:config', { ...props.config, [key]: value })
}

const handleSelectLanguage = (value: string) => {
	emits('language', value)
}

const handleSelectEncoding = (value: string) => {
	emits('encoding', value)
}
</script>

<style lang="scss" scoped>
.toolbar {
	@apply grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 xl:grid-cols-5;

	.item {
		@apply sm:max-w-[80%] md:max-w-[70%] xl:max-w-[60%] space-x-1 flex my-1;

		label {
			@apply whitespace-nowrap;
		}
	}
}
</style>
