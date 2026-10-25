<template>
	<main class="cloud-access-page"><WorkspaceCloudAccess /></main>
</template>
<script setup>
import WorkspaceCloudAccess from '~/components/auth/WorkspaceCloudAccess.vue'
</script>
<style scoped>
.cloud-access-page {
	max-width: 1100px;
	margin: 28px auto;
	padding: 28px;
	border-radius: 24px;
	background: var(--cloud-surface, white);
	min-height: 320px;
}
@media (max-width: 640px) {
	.cloud-access-page {
		margin: 12px;
		padding: 18px;
	}
}
</style>
