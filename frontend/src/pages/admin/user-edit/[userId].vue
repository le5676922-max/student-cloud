<template>
	<index :key="route.params.userId" />
</template>

<script setup>
import Index from '~/pages/admin/user-edit/index.vue'
const route = useRoute()
</script>

<style scoped></style>

<route lang="yaml">
meta:
  layout: admin
  name: 编辑用户
</route>
