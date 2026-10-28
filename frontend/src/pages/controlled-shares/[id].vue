<template>
	<main class="controlled-share-page" :aria-busy="loading || downloading">
		<router-link to="/">返回云盘</router-link>
		<section class="controlled-share-card">
			<p class="share-eyebrow">登录后受控访问</p>
			<h1>文件分享</h1>
			<p v-if="loading" role="status">正在确认分享权限与源文件…</p>
			<p v-if="error" class="share-error" role="alert">{{ error }}</p>
			<template v-if="share">
				<h2>{{ share.name }}</h2>
				<p>
					{{ (share.size / 1024).toFixed(1) }} KiB ·
					{{ new Date(share.expiresAt).toLocaleString('zh-CN') }} 到期
				</p>
				<p>
					{{
						share.audience === 'OWN_WORKSPACE'
							? '仅本空间已登录成员可访问'
							: '仅所选课程当前已登录成员可访问'
					}}
				</p>
				<p class="share-hint">
					下载时会重新核验访问权限。源文件变化、移走、授权收回、分享撤销或到期后，链接失效。
				</p>
				<el-button
					type="primary"
					:loading="downloading"
					:disabled="loading || downloading"
					@click="download"
					>下载文件</el-button
				>
			</template>
			<el-button v-if="!loading" :disabled="downloading" @click="load"
				>重新检查</el-button
			>
		</section>
	</main>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useStorage } from '@vueuse/core'
import useStorageConfigStore from '~/stores/storage-config'
import {
	controlledSharesRequest,
	downloadControlledShare,
} from '~/api/home/controlled-shares'
import { controlledSharePath } from '~/utils/controlled-sharing.mjs'
const route = useRoute(),
	store = useStorageConfigStore()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
const share = ref(null),
	loading = ref(false),
	downloading = ref(false),
	error = ref('')
let generation = 0,
	activeRequest
const message = (failure) =>
	failure?.response?.data?.msg ||
	failure?.data?.msg ||
	failure?.message ||
	'分享不可用，或当前账号没有访问权限'
function cancel() {
	generation++
	activeRequest?.abort()
	activeRequest = null
	share.value = null
	loading.value = false
	downloading.value = false
}
async function load() {
	cancel()
	const current = generation
	const id = String(route.params.id || '')
	loading.value = true
	error.value = ''
	activeRequest = new AbortController()
	try {
		controlledSharePath(id)
		const response = await controlledSharesRequest(`/${id}`, 'get', undefined, {
			signal: activeRequest.signal,
		})
		if (current === generation) share.value = response.data
	} catch (failure) {
		if (current === generation) error.value = message(failure)
	} finally {
		if (current === generation) loading.value = false
	}
}
async function download() {
	if (downloading.value || !share.value) return
	const current = generation,
		selected = share.value
	downloading.value = true
	error.value = ''
	activeRequest = new AbortController()
	try {
		const blob = await downloadControlledShare(selected.id, {
			signal: activeRequest.signal,
		})
		if (current !== generation) return
		const url = URL.createObjectURL(blob),
			anchor = document.createElement('a')
		try {
			anchor.href = url
			anchor.download = selected.name
			document.body.append(anchor)
			anchor.click()
		} finally {
			anchor.remove()
			setTimeout(() => URL.revokeObjectURL(url), 1000)
		}
	} catch (failure) {
		if (current === generation) {
			error.value = message(failure)
			share.value = null
		}
	} finally {
		if (current === generation) downloading.value = false
	}
}
watch(
	() => [
		route.params.id,
		store.loginInfo?.id,
		store.loginInfo?.accountId,
		store.loginInfo?.username,
		store.loginInfo?.role,
		store.loginInfo?.tenantId,
		store.loginInfo?.isLogin,
		credential.value,
	],
	load,
	{ immediate: true }
)
onBeforeUnmount(cancel)
</script>

<style scoped>
.controlled-share-page {
	max-width: 860px;
	margin: 0 auto;
	padding: 32px 20px;
	color: var(--el-text-color-primary);
}
.controlled-share-page > a {
	color: var(--el-color-primary);
}
.controlled-share-card {
	margin-top: 24px;
	padding: clamp(24px, 5vw, 48px);
	border: 1px solid var(--el-border-color-lighter);
	border-radius: 20px;
	background: var(--el-bg-color);
	box-shadow: var(--el-box-shadow-light);
}
.controlled-share-card h1 {
	font-size: 28px;
	margin: 8px 0 28px;
}
.controlled-share-card h2 {
	font-size: 20px;
	overflow-wrap: anywhere;
}
.controlled-share-card p {
	line-height: 1.8;
}
.share-eyebrow {
	color: var(--el-color-primary);
	font-size: 13px;
}
.share-hint {
	color: var(--el-text-color-secondary);
	font-size: 14px;
}
.share-error {
	color: var(--el-color-danger);
}
</style>
