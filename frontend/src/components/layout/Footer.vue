<template>
	<div>
		<a
			v-show="storageConfigStore.globalConfig?.icp"
			target="_blank"
			class="text-gray-500"
			href="https://beian.miit.gov.cn/"
		>
			{{ storageConfigStore.globalConfig?.icp }} ·
		</a>
		<span class="text-gray-500">学生云盘</span>
	</div>
</template>

<script setup>
import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()
</script>

<style scoped></style>
