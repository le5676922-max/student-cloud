<template>
	<div
		class="mb-5"
		:class="{ 'border-b border-gray-200 pb-5': !hideBottomBorder }"
	>
		<h3
			v-show="title || $slots.title"
			class="text-lg leading-6 font-medium text-gray-900"
		>
			<slot v-if="$slots.title" name="title"></slot>
			<template v-else>
				<div class="flex">
					<span>{{ title }}</span>
					<span v-show="pro" class="badge-blue">Pro</span>
				</div>
			</template>
		</h3>
		<p v-show="subTitle || $slots.subTitle" class="mt-2 text-sm text-gray-500">
			<slot v-if="$slots.subTitle" name="subTitle"></slot>
			<template v-else>{{ subTitle }}</template>
		</p>
	</div>
</template>

<script setup>
defineProps({
	title: { type: String, default: undefined },
	subTitle: { type: String, default: undefined },
	hideBottomBorder: {
		type: Boolean,
		default: false,
	},
	pro: {
		type: Boolean,
		default: false,
	},
})
</script>
