<template>
	<el-container v-if="loadedConfig" class="cloud-workspace">
		<el-header>
			<Suspense>
				<template #default>
					<Header></Header>
				</template>
			</Suspense>
		</el-header>
		<el-main :class="'el-main-' + storageConfigStore.globalConfig?.layout">
			<router-view />
		</el-main>
		<el-footer>
			<Footer></Footer>
		</el-footer>
	</el-container>
	<section
		v-else
		class="cloud-startup"
		:role="loadError ? 'alert' : 'status'"
		aria-live="polite"
	>
		<div class="cloud-startup-card">
			<div class="cloud-startup-mark">
				<CloudArrowUpIcon aria-hidden="true" />
			</div>
			<p class="cloud-startup-brand">学生云盘</p>
			<h1>{{ loadError ? '暂时无法打开云盘' : '正在准备你的文件空间' }}</h1>
			<p class="cloud-startup-description">
				{{ loadError || '正在读取站点设置，请稍候。' }}
			</p>
			<el-button
				v-if="loadError"
				type="primary"
				:loading="loadingConfig"
				@click="loadGlobalSiteSetting"
				>重新加载</el-button
			>
			<div v-else class="cloud-startup-placeholder" aria-hidden="true">
				<span></span><span></span><span></span>
			</div>
		</div>
	</section>
</template>

<script setup>
import Header from '~/components/layout/Header.vue'
import Footer from '~/components/layout/Footer.vue'
import { loadGlobalSiteConfigReq } from '~/api/home/home'
import { CloudArrowUpIcon } from '@heroicons/vue/24/outline'

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

// 初始化时，加载全局设置
onBeforeMount(() => {
	loadGlobalSiteSetting()
})

let router = useRouter()

let loadedConfig = ref(false)
const loadingConfig = ref(false)
const loadError = ref('')
let pendingRequest

onUnmounted(() => pendingRequest?.abort())

const loadGlobalSiteSetting = () => {
	if (loadingConfig.value) return
	loadingConfig.value = true
	loadError.value = ''
	pendingRequest = new AbortController()
	loadGlobalSiteConfigReq({
		timeout: 10000,
		signal: pendingRequest.signal,
		showDefaultMsg: false,
	})
		.then((res) => {
			// 如果未安装, 则跳转到安装页
			if (!res.data.installed) {
				router.push('/install')
				return
			}
			storageConfigStore.updateGlobalConfig(res.data)

			if (res.data.customAudioSuffix) {
				constant.fileTypeMap.audio = res.data.customAudioSuffix.split(',')
			}
			if (res.data.customImageSuffix) {
				constant.fileTypeMap.image = res.data.customImageSuffix.split(',')
			}
			if (res.data.customTextSuffix) {
				constant.fileTypeMap.text = res.data.customTextSuffix.split(',')
			}
			if (res.data.customVideoSuffix) {
				constant.fileTypeMap.video = res.data.customVideoSuffix.split(',')
			}
			if (res.data.customOfficeSuffix) {
				constant.fileTypeMap.office = res.data.customOfficeSuffix.split(',')
			}
			loadedConfig.value = true
		})
		.catch((err) => {
			if (err.code === 'ERR_CANCELED') return
			loadError.value =
				err.message === 'Network Error' || err.code === 'ECONNABORTED'
					? '服务连接超时或网络不可用，请稍后重试。'
					: '站点设置加载失败，请重试；若仍无法打开，请联系管理员。'
		})
		.finally(() => {
			loadingConfig.value = false
		})
}
</script>

<style lang="scss" scoped>
.cloud-startup {
	display: grid;
	place-items: center;
	min-height: 100%;
	padding: 24px;
	background: var(--cloud-canvas);
}
.cloud-startup-card {
	width: min(100%, 440px);
	padding: 36px 28px;
	border: 1px solid var(--cloud-line);
	border-radius: 16px;
	background: var(--cloud-surface);
	text-align: center;
	.cloud-startup-mark {
		display: grid;
		place-items: center;
		width: 48px;
		height: 48px;
		margin: 0 auto 16px;
		border-radius: 14px;
		background: var(--cloud-soft);
		color: var(--cloud-primary);
	}
	svg {
		width: 26px;
		height: 26px;
	}
	h1 {
		margin: 8px 0;
		font-size: 20px;
		font-weight: 600;
	}
}
.cloud-startup-brand {
	margin: 0;
	color: var(--cloud-primary);
	font-size: 13px;
}
.cloud-startup-description {
	margin: 12px 0 24px;
	color: var(--cloud-muted);
	font-size: 14px;
	line-height: 1.8;
}
.cloud-startup-placeholder {
	display: grid;
	gap: 10px;
	span {
		height: 10px;
		border-radius: 5px;
		background: #edf1ea;
	}
	span:nth-child(2) {
		width: 84%;
	}
	span:nth-child(3) {
		width: 64%;
	}
}
.el-container {
	height: 100%;
	width: 100%;
	position: relative;
	background: var(--cloud-canvas);

	.el-header,
	.el-footer {
		color: var(--el-text-color-primary);
		padding: 0;
		text-align: center;
	}

	.el-header {
		--el-header-height: unset;
		height: 72px;
		z-index: 2;
	}

	// 去除文件区 padding
	.el-main {
		padding: 0;
		overflow-x: hidden;
		position: relative;
	}

	.el-main-card {
		background: transparent;
	}

	// 定义脚部高度，边框
	.el-footer {
		border-top: 1px solid var(--cloud-line);
		height: 36px;
		line-height: 36px;
		font-size: 12px;
		background: var(--cloud-canvas);
	}
}
</style>
