<template>
	<el-config-provider :locale="zhCn">
		<router-view v-if="isRouterAlive" />
		<AiAssistant v-if="showAssistant" />
	</el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import useStorageConfigStore from '~/stores/storage-config'
import { shouldMountAssistant } from '~/utils/assistant-loading.mjs'
import useRouterData from '~/composables/useRouterData'

const AiAssistant = defineAsyncComponent(() =>
	import('~/components/ai/AiAssistant.vue')
)
const storageConfigStore = useStorageConfigStore()

let router = useRouter()
let route = useRoute()
const showAssistant = computed(() =>
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
