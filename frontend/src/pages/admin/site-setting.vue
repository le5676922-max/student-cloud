<template>
	<el-form
		v-if="siteSetting"
		ref="settingFormRef"
		v-loading="saveLoading"
		:model="siteSetting"
		:label-width="globalConfigStore.adminForm.labelWidth"
		:label-position="globalConfigStore.adminForm.labelPosition"
		:size="globalConfigStore.adminForm.size"
		scroll-to-error
		status-icon
		class="z-admin-form"
		:rules="rules"
		@validate="revealInvalidField"
	>
		<admin-form-header title="站点信息" />

		<el-form-item prop="siteName" label="站点名称">
			<el-input
				id="siteName"
				v-model="siteSetting.siteName"
				:prefix-icon="DocumentTextIcon"
			/>
		</el-form-item>
		<el-form-item prop="siteHomeName" label="根路径名称">
			<el-input
				id="siteHomeName"
				v-model="siteSetting.siteHomeName"
				:prefix-icon="HomeIcon"
			/>
			<div class="el-form-item-tips">首页面包屑根目录显示的名称。</div>
		</el-form-item>
		<el-form-item label="备案号">
			<el-input
				id="icp"
				v-model="siteSetting.icp"
				:prefix-icon="ShieldCheckIcon"
			/>
		</el-form-item>
		<el-form-item label="最大同时上传文件数">
			<el-input-number
				id="maxFileUploads"
				v-model="siteSetting.maxFileUploads"
				:min="1"
				:max="99"
			/>
		</el-form-item>

		<details
			class="setting-section"
			:open="expandedSections.branding"
			@toggle="setSectionOpen('branding', $event)"
		>
			<summary>品牌外观<span>浏览器图标、首页 Logo 与链接</span></summary>
			<div class="setting-section-content">
				<el-form-item prop="faviconUrl" label="站点 favicon">
					<el-input
						id="faviconUrl"
						v-model="siteSetting.faviconUrl"
						:prefix-icon="PhotoIcon"
					/>
					<div class="el-form-item-tips">
						网站在浏览器标题栏的 favicon 图标，需确保输入的地址没有 referer
						防盗链，如修改后不生效请清理浏览器缓存或再试。
					</div>
				</el-form-item>

				<el-form-item prop="siteHomeLogo" label="首页 Logo 图片地址">
					<el-input
						id="siteHomeLogo"
						v-model="siteSetting.siteHomeLogo"
						:prefix-icon="PhotoIcon"
					>
						<template #append>
							<el-upload
								ref="uploadRef"
								accept="image/*"
								:multiple="false"
								:show-file-list="false"
								:auto-upload="false"
								:on-change="onSelectFile"
							>
								<template #trigger>
									<el-tooltip content="手动上传最大支持 1MB">
										<el-button>
											<CloudArrowUpIcon class="h-4 w-4"></CloudArrowUpIcon>
										</el-button>
									</el-tooltip>
								</template>
							</el-upload>
						</template>
					</el-input>
					<div class="el-form-item-tips">
						<span>首页左上角 Logo，支持以下几种格式：</span>
						<ul class="list-disc ml-4">
							<li>http://xxx，其中 http 必须小写</li>
							<li>https://xxx，其中 https 必须小写</li>
							<li>带 data:image 前缀的 base64 图片</li>
							<li>
								不带 data:image 前缀的 base64 图片, 自动添加前缀:
								data:image/jpeg;base64,
							</li>
						</ul>
					</div>
					<div>
						<el-button
							v-show="siteSetting.siteHomeLogo && siteHomeLogoShow === false"
							type="primary"
							@click="siteHomeLogoShow = true"
						>
							预览
						</el-button>
					</div>
					<div v-show="siteSetting.siteHomeLogo" v-if="siteHomeLogoShow">
						<el-button type="primary" @click="siteHomeLogoShow = false">
							隐藏预览
						</el-button>
						<div class="mt-2">
							<auto-icon :icon="siteSetting.siteHomeLogo" class="h-6 w-auto" />
						</div>
					</div>
				</el-form-item>

				<el-form-item prop="siteHomeLogoLink" label="首页 Logo 打开链接">
					<el-input
						id="siteHomeLogoLink"
						v-model="siteSetting.siteHomeLogoLink"
						:disabled="!siteSetting.siteHomeLogo"
						:prefix-icon="LinkIcon"
					/>
					<div class="el-form-item-tips">
						点击 Logo 链接后打开的地址，为空则点击不进行任何跳转
					</div>
				</el-form-item>

				<el-form-item
					prop="siteHomeLogoTargetMode"
					label="首页 Logo 链接打开方式"
				>
					<el-radio-group v-model="siteSetting.siteHomeLogoTargetMode">
						<el-radio value="_blank">新标签打开</el-radio>
						<el-radio value="_self">当前页面打开</el-radio>
					</el-radio-group>
				</el-form-item>
			</div>
		</details>
		<details
			class="setting-section"
			:open="expandedSections.connection"
			@toggle="setSectionOpen('connection', $event)"
		>
			<summary>连接与兼容<span>服务地址、前端域名及旧版选项</span></summary>
			<div class="setting-section-content">
				<el-form-item prop="frontDomain" label="强制后端地址">
					<el-input
						v-model="siteSetting.forceBackendAddress"
						:prefix-icon="LinkIcon"
					/>
					<div class="el-form-item-tips">
						用于直链、下载和回调地址。留空自动检测：<span class="select-all">{{
							serverAddress
						}}</span
						>；当前前端检测为
						<span class="select-all">{{ frontDetectServerAddress }}</span
						>。
						<div
							v-if="frontDetectServerAddress !== serverAddress"
							class="text-red-500"
						>
							检测地址与前端不一致，使用反向代理时请检查网关配置。
							<el-popover placement="right" :width="400" trigger="click">
								<template #reference
									><el-button
										v-show="serverHeaders"
										native-type="button"
										link
										class="server-headers-button"
									>
										查看服务器头信息
									</el-button></template
								>
								<div class="text-sm h-96 overflow-auto">
									<button
										type="button"
										class="server-headers-button"
										@click="copyServerHeaders"
									>
										复制头信息
									</button>
									<pre class="whitespace-pre-wrap break-all">{{
										serverHeaders
									}}</pre>
								</div>
							</el-popover>
						</div>
					</div>
				</el-form-item>
				<el-form-item
					v-show="
						globalConfigStore.studentCloudConfig.baseUrl ||
						siteSetting.frontDomain
					"
					prop="frontDomain"
					label="前端站点域名"
				>
					<el-input v-model="siteSetting.frontDomain" :prefix-icon="LinkIcon" />
					<div class="el-form-item-tips">
						前后端分离时填写前端地址，供登录、失效链接和错误页跳转使用。
					</div>
					<el-popover placement="right" :width="400" trigger="click">
						<template #reference>
							<div v-show="fixFrontDomain.flag" class="mt-2">
								<span class="text-red-500 text-sm">设置异常，点击查看</span
								><ExclamationTriangleIcon class="inline w-5 text-red-500" />
							</div>
						</template>
						<div class="text-sm">
							<div class="mb-2">
								实际前端域名应为
								<span class="text-red-500">{{ fixFrontDomain.except }}</span
								>，不修正会影响错误页跳转。
							</div>
							<div class="flex justify-end">
								<el-button
									size="small"
									type="warning"
									:icon="CheckBadgeIcon"
									@click="fixFrontDomain.action"
									>自动修正</el-button
								>
							</div>
						</div>
					</el-popover>
				</el-form-item>

				<el-form-item
					prop="siteAdminLogoTargetMode"
					label="管理员页面 logo 链接打开方式"
				>
					<el-radio-group v-model="siteSetting.siteAdminLogoTargetMode">
						<el-radio value="_blank">新标签打开</el-radio>
						<el-radio value="_self">当前页面打开</el-radio>
					</el-radio-group>
					<div class="el-form-item-tips">
						管理员页面左上角的“学生云盘”标识点击会回到首页
					</div>
				</el-form-item>

				<el-form-item
					prop="siteAdminVersionOpenChangeLog"
					label="管理员页面点击版本号打开更新日志"
				>
					<el-switch v-model="siteSetting.siteAdminVersionOpenChangeLog" />
				</el-form-item>
			</div>
		</details>

		<el-form-item class="admin-from-footer">
			<el-button
				class="ml-auto"
				type="primary"
				:icon="CheckBadgeIcon"
				@click="saveData"
				>保存设置</el-button
			>
		</el-form-item>
	</el-form>
</template>

<script setup>
import { toClipboard } from '@soerenmartius/vue3-clipboard'
import {
	DocumentTextIcon,
	LinkIcon,
	CheckBadgeIcon,
	ShieldCheckIcon,
	HomeIcon,
	PhotoIcon,
	ExclamationTriangleIcon,
} from '@heroicons/vue/24/solid'

import { CloudArrowUpIcon } from '@heroicons/vue/24/outline'
import { fileToBase64 } from '~/utils'

import { getUserListReq } from '~/api/admin/admin-user'
import {
	updateSiteSettingReq,
	getServerAddressReq,
	getServerHeadersReq,
} from '~/api/admin/admin-setting'

import useGlobalConfigStore from '~/stores/global-config'
let globalConfigStore = useGlobalConfigStore()

const settingFormRef = ref()
import useAdminSetting from '~/composables/admin/useAdminSetting'
import { loadStorageListReq } from '~/api/admin/admin-storage'
const { siteSetting, siteSettingLoading, saveData, saveLoading } =
	useAdminSetting(updateSiteSettingReq, settingFormRef)

const expandedSections = ref({ branding: false, connection: false })
const sectionFields = {
	branding: [
		'faviconUrl',
		'siteHomeLogo',
		'siteHomeLogoLink',
		'siteHomeLogoTargetMode',
	],
	connection: [
		'forceBackendAddress',
		'frontDomain',
		'siteAdminLogoTargetMode',
		'siteAdminVersionOpenChangeLog',
	],
}
function setSectionOpen(section, event) {
	if (Object.hasOwn(expandedSections.value, section))
		expandedSections.value[section] = event.currentTarget.open
}
async function revealInvalidField(prop, valid) {
	if (valid) return
	const field = Array.isArray(prop) ? prop.join('.') : String(prop || '')
	if (!field) return
	for (const [section, fields] of Object.entries(sectionFields)) {
		if (fields.includes(field)) expandedSections.value[section] = true
	}
	// Closed native details keep all fields registered; reveal before scrolling.
	await nextTick()
	settingFormRef.value?.scrollToField?.(prop)
}

let rules = ref({
	siteName: [{ required: true, message: '请输入站点名称' }],
	faviconUrl: [
		{
			type: 'url',
			message: '请输入正确的 favicon 地址，需以 http 或 https 开头',
		},
	],
})

let fixFrontDomain = ref({
	flag: false,
	except: '',
	action: () => {},
})

watch(
	() => siteSettingLoading.value,
	(newVal, oldValue) => {
		// 如果是刚加载完成, 则检测域名配置是否正确.
		if (oldValue === true) {
			// 校验前端域名
			let siteFrontDomain = siteSetting.value.frontDomain
			let origin = window.location.origin
			// 如果是前后端分离场景，但设置的前端域名不等于当前域名，则提示用户修改
			if (
				globalConfigStore.studentCloudConfig.baseUrl !== '' &&
				siteFrontDomain !== origin
			) {
				fixFrontDomain.value.flag = true
				fixFrontDomain.value.current = siteFrontDomain
				fixFrontDomain.value.except = origin
				fixFrontDomain.value.action = () => {
					siteSetting.value.frontDomain = origin
					saveData()
					fixFrontDomain.value.flag = false
				}
				// 如果是前后端不分离场景，但设置的前端域名不为空，则提示用户修改
			} else if (
				globalConfigStore.studentCloudConfig.baseUrl === '' &&
				!!siteFrontDomain
			) {
				fixFrontDomain.value.flag = true
				fixFrontDomain.value.current = siteFrontDomain
				fixFrontDomain.value.except = '空'
				fixFrontDomain.value.action = () => {
					siteSetting.value.frontDomain = ''
					saveData()
					fixFrontDomain.value.flag = false
				}
			}
		}
	}
)

onMounted(() => {
	checkAdminStoragePermission()
	loadServerAddress()
	loadServerHeaders()
})

const router = useRouter()
const checkAdminStoragePermission = () => {
	loadStorageListReq().then((response) => {
		let storageSize = response.data?.length
		if (storageSize > 0) {
			getUserListReq({
				hideDisabledStorage: true,
			}).then((res) => {
				let data = res.data
				if (data.length > 0) {
					let user = data.find((item) => item.id === 1)
					if (user.userStorageSourceList?.length === 0) {
						ElMessageBox.confirm(
							`检测到你有 ${storageSize} 个存储源，但没有给管理员分配任何存储源，是否前往配置？（不配置将无法访问存储源）`,
							'提示',
							{
								confirmButtonText: '确定',
								cancelButtonText: '取消',
								type: 'warning',
							}
						).then(() => {
							router.push('/admin/user-list')
						})
					}
				}
			})
		}
	})
}

const siteHomeLogoShow = ref(false)
const onSelectFile = (file) => {
	if (file.size > 1024 * 1024) {
		ElMessage.error('上传的图片不能大于 1MB')
		return
	}
	if (file.status === 'ready') {
		fileToBase64(file.raw)
			.then((base64) => {
				siteSetting.value.siteHomeLogo = base64
				siteHomeLogoShow.value = true
			})
			.catch((err) => {
				ElMessage.error('图片转换失败，请重试')
				console.error(err)
			})
	}
}

const serverAddress = ref('')
const loadServerAddress = () => {
	getServerAddressReq()
		.then((response) => {
			serverAddress.value = response.data
		})
		.catch((error) => {
			console.error('获取服务器地址失败:', error)
		})
}

const serverHeaders = ref('')
const loadServerHeaders = () => {
	getServerHeadersReq()
		.then((response) => {
			serverHeaders.value = response.data
		})
		.catch((error) => {
			console.error('获取服务器头信息失败:', error)
		})
}
const copyServerHeaders = () => {
	toClipboard(serverHeaders.value)
		.then(() => {
			ElMessage.success('已复制到剪贴板')
		})
		.catch(() => {
			ElMessage.error('复制失败，请手动复制')
		})
}

const frontDetectServerAddress = computed(() => {
	let address = globalConfigStore.serverAddress
	return address.endsWith('/') ? address.slice(0, -1) : address
})
</script>

<style scoped>
.setting-section {
	margin: 20px 0;
	border: 1px solid var(--cloud-line);
	border-radius: 16px;
	background: var(--cloud-surface);
}
.setting-section > summary {
	padding: 17px 18px;
	border-radius: inherit;
	color: var(--cloud-ink);
	font-size: 14px;
	font-weight: 600;
	cursor: pointer;
}
.setting-section > summary span {
	margin-left: 12px;
	color: var(--cloud-muted);
	font-size: 12px;
	font-weight: 400;
}
.setting-section > summary:hover {
	background: var(--cloud-soft);
}
.setting-section > summary:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 3px;
}
.setting-section-content {
	padding: 0 18px;
	border-top: 1px solid var(--cloud-line);
}
.server-headers-button {
	padding: 2px 4px;
	border-radius: 4px;
	color: var(--cloud-primary);
	text-decoration: underline;
	text-underline-offset: 3px;
	cursor: pointer;
}
.server-headers-button:hover {
	background: var(--cloud-soft);
}
.server-headers-button:focus-visible {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
.admin-from-footer {
	position: sticky;
	bottom: 0;
	z-index: 1;
	padding-top: 16px;
	background: var(--cloud-surface);
}
@media (max-width: 640px) {
	.setting-section > summary span {
		display: block;
		margin: 5px 0 0 16px;
	}
	.setting-section-content {
		padding: 0 12px;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 基本设置
</route>
