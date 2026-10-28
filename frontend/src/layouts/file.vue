<template>
	<el-container class="cloud-workspace">
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
</template>

<script setup>
import Header from '~/components/layout/Header.vue'
import Footer from '~/components/layout/Footer.vue'

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

// App resolves the shared public site configuration before mounting this layout.
</script>

<style lang="scss" scoped>
.cloud-workspace {
	height: calc(100% - 32px);
	width: calc(100% - 32px);
	margin: 16px;
	box-sizing: border-box;
	border: 1px solid #ffffff24;
	border-radius: 24px;
	overflow: hidden;
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
		background: var(--cloud-surface);
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
		background: var(--cloud-surface);
	}
}

@media (max-width: 767px) {
	.cloud-workspace {
		width: calc(100% - 16px);
		height: calc(100% - 16px);
		margin: 8px;
		border-radius: 18px;
		.el-header {
			height: 64px;
		}
	}
}
</style>
