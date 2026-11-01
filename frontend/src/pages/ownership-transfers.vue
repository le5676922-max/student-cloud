<template>
  <main class="ownership-inbox">
    <header><div><h1>管理交接</h1><p>核对管理范围后再确认接任；私人文件不随职责转移。</p></div><router-link to="/">返回云盘</router-link></header>
    <OwnershipTransfer resource-type="workspace" :enabled="enabled" />
    <p>课程交接请在「共享课程」打开对应课程查看。</p>
    <router-link to="/courses">查看共享课程</router-link>
  </main>
</template>
<script setup>
import OwnershipTransfer from '~/components/auth/OwnershipTransfer.vue'
const enabled = import.meta.env.VITE_OWNERSHIP_TRANSFER_ENABLED === 'true'
</script>
<style scoped>
.ownership-inbox{max-width:960px;margin:0 auto;padding:24px;color:var(--cloud-ink)}.ownership-inbox header{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:20px}.ownership-inbox h1{font-size:24px;margin:0}.ownership-inbox p{font-size:14px;color:var(--cloud-muted)}.ownership-inbox a{color:var(--cloud-ink);text-decoration:underline;text-underline-offset:4px}
</style>
