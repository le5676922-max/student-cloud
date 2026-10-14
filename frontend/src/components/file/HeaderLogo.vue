<template>
	<button
		type="button"
		class="header-brand-button"
		aria-label="学生云盘首页"
		@click="openLink"
	>
		<img
			v-if="storageConfigStore.globalConfig.siteHomeLogo"
			id="student-cloud-home-logo"
			:src="storageConfigStore.globalConfig.siteHomeLogo"
			class="header-brand-image"
			alt="学生云盘标志"
		/>
		<BrandLogo v-else compact />
	</button>
</template>

<script setup>
import useStorageConfigStore from '~/stores/storage-config'
import BrandLogo from '~/components/BrandLogo.vue'
let storageConfigStore = useStorageConfigStore()
const router = useRouter()

const openLink = () => {
	if (storageConfigStore.globalConfig.siteHomeLogoLink) {
		if (storageConfigStore.globalConfig.siteHomeLogoTargetMode === '_blank') {
			window.open(
				storageConfigStore.globalConfig.siteHomeLogoLink,
				'_blank',
				'noopener,noreferrer'
			)
		} else {
			location.href = storageConfigStore.globalConfig.siteHomeLogoLink
		}
	} else {
		router.push('/')
	}
}
</script>

<style scoped>
.header-brand-button {
	display: inline-flex;
	align-items: center;
	flex-shrink: 0;
	padding: 4px 0;
	border: 0;
	border-radius: 6px;
	background: transparent;
	color: var(--cloud-ink);
	cursor: pointer;
	line-height: 1;
}
.header-brand-button:focus-visible {
	outline: 2px solid var(--cloud-primary);
	outline-offset: 5px;
}
.header-brand-image {
	width: auto;
	height: 28px;
	max-width: 190px;
	object-fit: contain;
}
</style>
