<template>
	<div
		class="inline-flex items-center"
		:class="compact ? 'gap-2' : 'flex-col gap-2'"
	>
		<span
			class="brand-logo-mark relative flex items-center justify-center rounded-lg text-white"
			:class="compact ? 'h-8 w-8' : 'h-12 w-12'"
		>
			<CloudArrowUpIcon
				:class="compact ? 'h-5 w-5' : 'h-7 w-7'"
				aria-hidden="true"
			/>
		</span>
		<span
			class="brand-logo-name font-bold"
			:class="compact ? 'text-lg' : 'text-xl'"
			>学生云盘</span
		>
	</div>
</template>

<script setup>
import { CloudArrowUpIcon } from '@heroicons/vue/24/outline'

defineProps({
	compact: {
		type: Boolean,
		default: false,
	},
})
</script>

<style scoped>
.brand-logo-mark {
	flex-shrink: 0;
	background: var(--cloud-primary);
}

.brand-logo-name {
	color: var(--cloud-ink);
	letter-spacing: -0.025em;
	white-space: nowrap;
}
</style>
