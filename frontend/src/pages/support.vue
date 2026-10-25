<template>
	<main class="support-page">
		<SupportChat />
	</main>
</template>
<script setup>
import SupportChat from '~/components/support/SupportChat.vue'
useTitle('人工客服 · 学生云盘')
</script>
<style scoped>
.support-page {
	max-width: 1280px;
	margin: 0 auto;
	padding: 32px 20px;
}
@media (max-width: 640px) {
	.support-page {
		padding: 18px 12px;
	}
}
</style>
