<template>
	<el-config-provider :locale="zhCn">
		<router-view
			v-if="isRouterAlive && (publicRoute || siteConfig.state.ready)"
		/>
		<section
			v-else-if="!publicRoute"
			class="site-startup"
			:aria-busy="siteConfig.state.loading"
			:role="siteConfig.state.error ? 'alert' : 'status'"
		>
			<div class="site-startup-card">
				<p class="site-startup-brand">学生云盘</p>
				<h1>
					{{
						siteConfig.state.error ? '暂时无法打开页面' : '正在准备你的工作空间'
					}}
				</h1>
				<p>
					{{
						siteConfig.state.error ||
						(siteConfig.state.loading
							? '正在读取站点配置，请稍候。'
							: '正在确认登录状态，请稍候。')
					}}
				</p>
				<el-button
					v-if="siteConfig.state.error"
					type="primary"
					@click="siteConfig.retry"
					>重新读取站点配置</el-button
				>
			</div>
		</section>
		<AiAssistant v-if="showAssistant" />
	</el-config-provider>
</template>

<script setup>
import { onBeforeUnmount } from 'vue'
import { useStorage } from '@vueuse/core'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import useStorageConfigStore from '~/stores/storage-config'
import useGlobalConfigStore from '~/stores/global-config'
import useSiteConfigBootstrap from '~/composables/useSiteConfigBootstrap'
import { isPublicRoute } from '~/utils/auth-navigation.mjs'
import { navigationCredentialIdentity } from '~/utils/navigation-credential.mjs'
import { shouldMountAssistant } from '~/utils/assistant-loading.mjs'
import useRouterData from '~/composables/useRouterData'

const AiAssistant = defineAsyncComponent(() =>
	import('~/components/ai/AiAssistant.vue')
)
const storageConfigStore = useStorageConfigStore()
const globalConfigStore = useGlobalConfigStore()
const siteConfig = useSiteConfigBootstrap()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})

let router = useRouter()
let route = useRoute()
const publicRoute = computed(() => isPublicRoute(route.path))
const siteIdentity = computed(() => {
	// Re-read a cookie-only credential on navigation as well as localStorage updates.
	void route.path
	return JSON.stringify([
		storageConfigStore.loginInfo.accountId,
		storageConfigStore.loginInfo.username,
		storageConfigStore.loginInfo.role,
		storageConfigStore.loginInfo.tenantId,
		storageConfigStore.loginInfo.isLogin,
		navigationCredentialIdentity({
			storage: { getItem: () => credential.value },
			cookie: document.cookie,
			baseUrl: globalConfigStore.studentCloudConfig.baseUrl,
		}),
		globalConfigStore.studentCloudConfig.baseUrl,
	])
})
watch(
	[siteIdentity, publicRoute],
	() => {
		if (publicRoute.value || storageConfigStore.loginInfo.isLogin !== true)
			siteConfig.reset()
		else siteConfig.ensure(siteIdentity.value)
	},
	{ immediate: true, flush: 'sync' }
)
watch(
	() => siteConfig.state.needsInstall,
	(needed) => {
		if (needed) router.replace('/install')
	},
	{ flush: 'sync' }
)
onBeforeUnmount(() => siteConfig.cancel())
const showAssistant = computed(
	() =>
		siteConfig.state.ready &&
		shouldMountAssistant(route.path, storageConfigStore.loginInfo)
)
useRouterData(router, route)

import uaBrowser from 'ua-browser'
console.log('当前浏览器信息:', uaBrowser())

const isRouterAlive = ref(true)
const reload = () => {
	isRouterAlive.value = false
	nextTick(() => {
		isRouterAlive.value = true
	})
}
provide('reload', reload)
</script>

<style scoped>
.site-startup {
	display: grid;
	place-items: center;
	min-height: 100%;
	padding: 24px;
	background: var(--cloud-frame);
}
.site-startup-card {
	width: min(100%, 440px);
	padding: 32px 28px;
	border: 1px solid var(--cloud-line);
	border-radius: var(--cloud-radius);
	background: var(--cloud-surface);
	text-align: center;
}
.site-startup-brand {
	color: var(--cloud-primary);
	font-size: 13px;
}
.site-startup h1 {
	margin: 8px 0;
	color: var(--cloud-ink);
	font-size: 20px;
}
.site-startup p {
	color: var(--cloud-muted);
	line-height: 1.8;
}
</style>
