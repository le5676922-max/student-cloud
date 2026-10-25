<template>
	<el-form
		ref="storageEditForm"
		:model="storageItem"
		:label-width="globalConfigStore.adminForm.labelWidth"
		:label-position="globalConfigStore.adminForm.labelPosition"
		:size="globalConfigStore.adminForm.size"
		scroll-to-error
		status-icon
		class="student-cloud-storage-edit z-admin-form"
		:rules="rules"
		:disabled="loading || initialLoading || isApplyingDetection || !!safetyBusy"
	>
		<cancel
			:title="isEditMode ? '编辑存储源' : '添加存储源'"
			to="/admin/storage-list"
		>
			粘贴地址识别，或手动选择存储方式。识别和检查填写不会保存，也不会调用 AI。
		</cancel>
		<section
			v-if="!isEditMode"
			class="storage-setup-intro"
			aria-label="选择存储方式"
		>
			<span class="storage-setup-eyebrow"
				>1 选择方式 · 2 填写连接 · 3 检查并保存</span
			>
			<h2>你的文件放在哪里？</h2>
			<div class="storage-source-detection" aria-label="智能识别存储源">
				<label for="storage-source-address">存储目录或连接地址</label>
				<div class="storage-address-actions">
					<el-input
						id="storage-source-address"
						v-model="sourceDetectionInput"
						autocomplete="off"
						:maxlength="2048"
						placeholder="例如 /data/files、sftp://主机/目录 或 OSS 地址"
						@keydown.enter.prevent="recognizeStorageSource"
					/>
					<el-button
						:disabled="
							initialLoading ||
							!!initialError ||
							!sourceDetectionInput.trim() ||
							isApplyingDetection
						"
						@click="recognizeStorageSource"
						>智能识别</el-button
					>
				</div>
				<p>
					仅识别地址格式，不探测服务。不要粘贴密码、密钥或带签名的文件链接。
				</p>
				<p v-if="sourceDetectionNotice" role="status">
					{{ sourceDetectionNotice }}
				</p>
				<div
					v-if="sourceDetectionCandidates.length"
					class="storage-detection-candidates"
				>
					<article
						v-for="candidate in sourceDetectionCandidates"
						:key="candidate.type"
					>
						<div class="storage-detection-heading">
							<strong>{{ candidate.title }}</strong>
							<span>{{
								candidate.confidence === 'certain'
									? '已识别格式'
									: '需要你确认类型'
							}}</span>
						</div>
						<p>{{ candidate.reason }}</p>
						<dl>
							<div v-for="(value, key) in candidate.suggestions" :key="key">
								<dt>{{ detectionFieldName(key) }}</dt>
								<dd>{{ value }}</dd>
							</div>
						</dl>
						<el-button
							:disabled="isApplyingDetection"
							@click="applyDetectedSource(candidate)"
						>
							按{{ candidate.title }}填入
						</el-button>
					</article>
				</div>
			</div>
			<p>也可以手动选择。其它网盘和协议仍可在下方选择。</p>
			<div class="storage-type-choices">
				<button
					v-for="item in commonTypes"
					:key="item.key"
					type="button"
					:aria-pressed="storageItem.type === item.key"
					:disabled="loading || initialLoading || isApplyingDetection"
					@click="storageItem.type = item.key"
				>
					<strong>{{ item.title }}</strong
					><small>{{ item.description }}</small>
				</button>
			</div>
		</section>
		<div v-if="initialError" class="storage-setup-error" role="alert">
			{{ initialError
			}}<el-button size="small" @click="initializeStorage">重新加载</el-button>
		</div>
		<p v-if="initialLoading" role="status">正在读取存储类型和已有设置…</p>
		<aside v-if="storageItem.type" class="storage-setup-guide">
			<strong>{{ setupGuide.description }}</strong>
			<ul>
				<li v-for="tip in setupGuide.checklist" :key="tip">{{ tip }}</li>
			</ul>
		</aside>

		<el-form-item label="存储源名称" prop="name">
			<el-input v-model="storageItem.name" />
		</el-form-item>

		<el-form-item v-if="isAdmin && !isEditMode" label="所属工作空间">
			<el-select v-model="storageItem.tenantId" class="w-full">
				<el-option
					v-for="tenant in tenantList"
					:key="tenant.id"
					:label="tenant.name"
					:value="tenant.id"
					:disabled="!tenant.enable"
				/>
			</el-select>
		</el-form-item>

		<el-form-item label="存储源别名" prop="key">
			<el-input v-model="storageItem.key" />
			<div class="el-form-item-tips">
				选填，留空由系统分配。用于 URL 中展示，如
				{{ concatPath(serverAddress, storageItem.key || '{存储源别名}') }}
			</div>
		</el-form-item>

		<el-form-item v-show="showAdvanced" label="存储源备注">
			<el-input
				v-model="storageItem.remark"
				type="textarea"
				:rows="3"
				placeholder="请输入存储源备注"
			/>
			<div class="el-form-item-tips">
				存储源备注信息, 用于辅助管理员区分不同的存储源, 此字段仅管理员可见
			</div>
		</el-form-item>

		<el-form-item label="存储策略" prop="type">
			<el-select
				v-model="storageItem.type"
				:disabled="isEditMode"
				:filterable="isNotMobile"
				placeholder="请选择存储策略"
			>
				<el-option
					v-for="item in supportStorageType"
					:key="item.key"
					:label="item.description"
					:value="item.key"
				>
				</el-option>
			</el-select>
		</el-form-item>

		<el-form-item label="是否启用">
			<el-switch v-model="storageItem.enable" />
			<div class="el-form-item-tips">如不启用，则在前台不显示，且不可访问.</div>
		</el-form-item>

		<el-form-item v-show="showAdvanced" label="是否默认打开画廊模式">
			<el-switch v-model="storageItem.defaultSwitchToImgMode" />
			<div class="el-form-item-tips">
				启用后，每次切换到此存储源，将自动打开画廊模式（适用于纯图片的存储源）
			</div>
		</el-form-item>

		<section
			v-if="canRecognizeAddress"
			class="storage-address-helper"
			aria-label="识别连接地址"
		>
			<label for="storage-connection-address"
				>已有连接地址？粘贴后识别可填写的字段</label
			>
			<div class="storage-address-actions">
				<el-input
					id="storage-connection-address"
					v-model="connectionAddress"
					autocomplete="off"
					placeholder="仅填写地址，不要粘贴密钥或带签名的链接"
				/><el-button
					:disabled="paramsLoading || !!paramsError"
					@click="recognizeAddress"
					>识别地址</el-button
				>
			</div>
			<p v-if="addressNotice" role="status">{{ addressNotice }}</p>
			<div
				v-if="addressSuggestionEntries.length"
				class="storage-address-result"
			>
				<span v-for="[key, value] in addressSuggestionEntries" :key="key"
					>{{ fieldName(key) }}：{{ value }}</span
				><el-button
					size="small"
					:disabled="paramsLoading || !!paramsError"
					@click="applyAddressSuggestions"
					>应用识别结果</el-button
				>
			</div>
		</section>
		<div v-if="storageItem.type" class="storage-setup-section-heading">
			<h2>连接信息</h2>
			<el-button size="small" @click="showAdvanced = !showAdvanced"
				>{{ showAdvanced ? '收起高级设置' : '展开高级设置' }}（{{
					advancedFieldCount
				}}
				项）</el-button
			>
		</div>
		<div v-if="paramsLoading" role="status">正在读取当前存储方式的参数…</div>
		<div v-if="paramsError" class="storage-setup-error" role="alert">
			{{ paramsError
			}}<el-button size="small" @click="reloadParams">重新加载参数</el-button>
		</div>
		<div
			v-for="item in storageSourceParamList"
			v-show="showAdvanced || isEssentialStorageField(item)"
			:key="item.key"
		>
			<el-form-item
				v-if="fieldShowMap[item.key]"
				:label="item.name"
				:required="item.required"
				:prop="'storageSourceAllParam.' + item.key"
			>
				<template v-if="item.pro" #label>
					{{ item.name }}
					<span class="badge-blue">Pro</span>
				</template>

				<!-- Endpoint -->
				<el-select
					v-if="
						item.key === 'endPoint' &&
						constant.region.hasOwnProperty(storageItem.type)
					"
					v-model="storageItem.storageSourceAllParam.endPoint"
					:filterable="isNotMobile"
					allow-create
					default-first-option
					style="width: 100%"
				>
					<el-option
						v-for="endPoint in constant.region[storageItem.type]"
						:key="endPoint.name"
						:label="endPoint.name"
						:value="endPoint.val"
					>
						<span style="float: left">{{ endPoint.name }}</span>
						<span style="float: right; color: #8492a6; font-size: 13px">{{
							endPoint.val
						}}</span>
					</el-option>
				</el-select>

				<!-- sharepoint 网站列表 -->
				<el-select
					v-else-if="item.key === 'siteId' && isSharePoint(storageItem.type)"
					v-model="storageItem.storageSourceAllParam.siteId"
					:filterable="isNotMobile"
					placeholder="请选择站点列表"
					class="input-with-select"
				>
					<el-option
						v-for="option in sharepointSites"
						:key="option.id"
						:label="option.displayName"
						:value="option.id"
					>
						<span style="float: left">
							{{ option.displayName }}
							<el-tag
								v-if="option.displayName === 'Communication site'"
								type="success"
								>默认网站</el-tag
							>
						</span>
						<el-popover
							placement="right"
							width="400"
							trigger="hover"
							:content="option.webUrl"
						>
							<template #reference>
								<el-link
									target="_blank"
									:icon="LinkIcon"
									:href="option.webUrl"
									:underline="false"
									class="float-right"
									type="primary"
									>查看网站</el-link
								>
							</template>
						</el-popover>
					</el-option>
				</el-select>

				<!-- sharepoint 网站子目录 -->
				<el-select
					v-else-if="item.key === 'listId' && isSharePoint(storageItem.type)"
					v-model="storageItem.storageSourceAllParam.listId"
					:filterable="isNotMobile"
					placeholder="请选择网站子目录"
					class="input-with-select"
				>
					<el-option
						v-for="option in sharepointSiteLists"
						:key="option.id"
						:label="option.displayName"
						:value="option.id"
					>
						<span style="float: left">
							{{ option.displayName }}
							<el-tag
								v-if="option.displayName === 'Communication site'"
								type="success"
								>默认网站</el-tag
							>
						</span>
						<el-popover
							placement="right"
							width="400"
							trigger="hover"
							:content="option.webUrl"
						>
							<template #reference>
								<el-link
									target="_blank"
									:icon="Link"
									:href="option.webUrl"
									:underline="false"
									class="float-right"
									type="primary"
									>查看网站</el-link
								>
							</template>
						</el-popover>
					</el-option>
				</el-select>

				<!-- gd 网盘选择 -->
				<el-select
					v-else-if="item.key === 'driveId' && drivesList?.length > 0"
					v-model="storageItem.storageSourceAllParam.driveId"
					:filterable="isNotMobile"
					placeholder="请选择驱动器列表"
					class="input-with-select"
				>
					<el-option value="" label="默认空间">
						<span class="float-left"> 默认空间 </span>
						<span class="float-right">
							<el-tag type="primary" class="float-right"> 个人盘 </el-tag>
						</span>
					</el-option>
					<el-option
						v-for="option in drivesList"
						:key="option.id"
						:label="option.name"
						:value="option.id"
					>
						<span class="float-left">
							{{ option.name }}
						</span>
						<el-popover
							placement="left"
							width="400"
							trigger="hover"
							:content="option.id"
						>
							<template #reference>
								<el-tag type="success" class="float-right"> 团队盘 </el-tag>
							</template>
						</el-popover>
					</el-option>
				</el-select>

				<!-- s3 存储器列表 -->
				<el-select
					v-else-if="item.key === 'bucketName' && bucketList?.length > 0"
					v-model="storageItem.storageSourceAllParam.bucketName"
					allow-create
					:filterable="isNotMobile"
					placeholder="请选择存储器列表"
					class="input-with-select"
				>
					<el-option
						v-for="option in bucketList"
						:key="option.name"
						:label="option.name"
						:value="option.name"
					>
						<span class="float-left">
							{{ option.name }}
						</span>
						<span class="float-right">
							{{ option.date }}
						</span>
					</el-option>
				</el-select>

				<!-- s3 跨域配置列表 -->
				<div v-else-if="item.key === 'corsConfigList'" class="w-full">
					<p class="storage-cors-warning">
						保存和后台重启不会修改远端跨域规则。需要远端上传跨域权限时，请先保存，再单独确认应用。
					</p>
					<Cors v-model="storageItem.storageSourceAllParam['corsConfigList']" />
					<el-button
						v-if="isEditMode && isAdmin"
						:disabled="!!safetyBusy || loading || initialLoading"
						@click="applySavedCors"
						>应用已保存的跨域规则</el-button
					>
					<SafeHtml class="text-sm text-gray-400" :html="corsTips" />
				</div>

				<!-- 通用下拉选择 -->
				<template v-else-if="item.type === 'select'">
					<el-select
						v-model="storageItem.storageSourceAllParam[item.key]"
						:allow-create="item.optionAllowCreate"
						:filterable="isNotMobile"
						default-first-option
					>
						<el-option
							v-for="option in item.options"
							:key="option.value"
							:label="option.label"
							:value="option.value"
						></el-option>
					</el-select>
				</template>

				<!-- 通用 switch 开关 -->
				<template v-else-if="item.type === 'switch'">
					<el-switch v-model="storageItem.storageSourceAllParam[item.key]" />
				</template>

				<!-- 通用 input -->
				<template v-else-if="item.type === 'input'">
					<el-input
						v-if="trimInputFields.includes(item.key)"
						v-model.trim="storageItem.storageSourceAllParam[item.key]"
						:disabled="inputIsReadOnly(item.key)"
						:type="isSecretStorageField(item.key) ? 'password' : 'text'"
						:show-password="isSecretStorageField(item.key)"
						:placeholder="
							storageFieldHint(item.key, storageItem.type).placeholder
						"
						autocomplete="off"
					/>
					<el-input
						v-else
						v-model="storageItem.storageSourceAllParam[item.key]"
						:type="isSecretStorageField(item.key) ? 'password' : 'text'"
						:show-password="isSecretStorageField(item.key)"
						:placeholder="
							storageFieldHint(item.key, storageItem.type).placeholder
						"
						autocomplete="off"
					/>
				</template>

				<!-- 通用 input-number -->
				<template v-else-if="item.type === 'number'">
					<el-input-number
						v-model="storageItem.storageSourceAllParam[item.key]"
					/>
				</template>

				<!-- 通用 textarea -->
				<template v-else-if="item.type === 'textarea'">
					<el-input
						v-model="storageItem.storageSourceAllParam[item.key]"
						type="textarea"
					/>
				</template>

				<div v-else>
					服务端配置错误, 字段 {{ item.key }} 类型为 {{ item.type }},
					不支持此类型的渲染.
				</div>

				<div class="el-form-item-tips">
					<p v-if="storageFieldHint(item.key, storageItem.type).description">
						{{ storageFieldHint(item.key, storageItem.type).description }}
					</p>
					<!-- 通用链接 -->
					<div v-if="item.link">
						<span v-if="item.key === 'accessToken'" class="flex items-center">
							<el-link
								target="_blank"
								:icon="LinkIcon"
								type="info"
								:href="
									item.link +
									'?clientId=' +
									encodeURIComponent(
										storageItem.storageSourceAllParam['clientId']
									) +
									'&clientSecret=' +
									encodeURIComponent(
										storageItem.storageSourceAllParam['clientSecret']
									) +
									'&redirectUri=' +
									encodeURIComponent(
										storageItem.storageSourceAllParam['redirectUri']
									)
								"
							>
								{{ item.linkName }}
							</el-link>
							<i-ic-baseline-content-copy
								class="inline cursor-pointer ml-2 w-4 text-lg relative"
								@click="
									copyText(
										item.link +
											'?clientId=' +
											encodeURIComponent(
												storageItem.storageSourceAllParam['clientId']
											) +
											'&clientSecret=' +
											encodeURIComponent(
												storageItem.storageSourceAllParam['clientSecret']
											) +
											'&redirectUri=' +
											encodeURIComponent(
												storageItem.storageSourceAllParam['redirectUri']
											)
									)
								"
							/>
						</span>
						<el-link
							v-else
							target="_blank"
							:icon="LinkIcon"
							:href="item.link"
							>{{ item.linkName }}</el-link
						>
					</div>

					<!-- 通用描述信息 -->
					<SafeHtml v-if="item.description" :html="item.description" />

					<div
						v-if="
							item.key === 'domain' &&
							storageItem.storageSourceAllParam['domain']
						"
						class="font-bold"
					>
						注意:
						<ul>
							<li v-show="showSchemaTips" class="text-red-500">
								如果学生云盘站点使用 https，则该存储源的下载域名也应使用
								https，否则可能会影响打包下载和批量下载!
							</li>
							<li>需域名支持跨域访问，否则可能会导致下载/预览失败.</li>
						</ul>
					</div>
					<!--  onedrive 动态地址提示 -->
					<div
						v-if="
							item.key === 'redirectUri' &&
							item.description &&
							['onedrive', 'sharepoint'].includes(storageItem.type)
						"
					>
						如：{{ concatPath(serverAddress, 'onedrive/callback') }}
						<i-ic-baseline-content-copy
							class="inline cursor-pointer ml-1"
							@click="copyText(concatPath(serverAddress, 'onedrive/callback'))"
						/>
					</div>
					<div
						v-if="
							item.key === 'redirectUri' &&
							item.description &&
							['onedrive-china', 'sharepoint-china'].includes(storageItem.type)
						"
					>
						如：{{ concatPath(serverAddress, 'onedrive/china-callback') }}
						<i-ic-baseline-content-copy
							class="inline cursor-pointer ml-1"
							@click="
								copyText(concatPath(serverAddress, 'onedrive/china-callback'))
							"
						/>
					</div>
					<div
						v-if="
							item.key === 'redirectUri' &&
							item.description &&
							storageItem.type === 'google-drive'
						"
					>
						如：{{ concatPath(serverAddress, 'gd/callback') }}
						<i-ic-baseline-content-copy
							class="inline cursor-pointer ml-1"
							@click="copyText(concatPath(serverAddress, 'gd/callback'))"
						/>
					</div>
					<div
						v-if="item.key === 'accessToken' && storageItem.type === 'open115'"
					>
						<open115-login
							:app-id="storageItem.storageSourceAllParam.clientId"
							@success="onOpen115LoginSuccess"
						/>
					</div>
				</div>
			</el-form-item>
		</div>
		<section
			v-if="constant.storageType.s3Type.includes(storageItem.type)"
			class="storage-s3-discovery"
		>
			<h2>读取远端信息（可选）</h2>
			<p>
				由服务器使用你填写的凭据只读查询，不保存存储源。读取成功不代表具备所有上传、下载权限。
			</p>
			<div class="storage-address-actions">
				<el-button
					:disabled="!canReadBuckets || !!discoveryBusy"
					:loading="discoveryBusy === 'buckets'"
					@click="readBuckets"
					>读取存储桶</el-button
				><el-button
					:disabled="!canReadCors || !!discoveryBusy"
					:loading="discoveryBusy === 'cors'"
					@click="readCors"
					>查看远端跨域配置</el-button
				>
			</div>
			<p v-if="discoveryMessage" role="status">{{ discoveryMessage }}</p>
			<details v-if="remoteCors !== null">
				<summary>只读跨域结果（不会自动填入保存设置）</summary>
				<pre>{{ remoteCors }}</pre>
			</details>
		</section>
		<div v-if="setupIssues.length" class="storage-setup-error" role="alert">
			<strong>需要检查这些填写项：</strong>
			<ul>
				<li v-for="issue in setupIssues" :key="issue.key">
					{{ issue.message }}
				</li>
			</ul>
		</div>
		<p
			v-if="setupChecked && !setupIssues.length"
			class="storage-setup-success"
			role="status"
		>
			填写检查通过；这不是连接测试。保存时后台仍会验证实际连接，结果以后台响应为准。
		</p>
		<p v-if="saveError" class="storage-setup-error" role="alert">
			{{ saveError }}
		</p>
		<p
			v-if="safetyNotice"
			:class="safetyPassed ? 'storage-setup-success' : 'storage-setup-error'"
			role="status"
		>
			{{ safetyNotice }}
		</p>

		<el-form-item
			:label-width="globalConfigStore.adminForm.labelWidth"
			class="admin-from-footer"
		>
			<el-button
				:disabled="
					initialLoading ||
					!!initialError ||
					paramsLoading ||
					!!paramsError ||
					!storageItem.type ||
					!storageSourceParamList.length
				"
				@click="checkSetup"
				>检查填写</el-button
			>
			<el-checkbox v-model="verifyWrite" :disabled="!!safetyBusy"
				>同时验证写入、回读和清理测试文件</el-checkbox
			>
			<el-button
				class="ml-auto"
				:loading="safetyBusy === 'test'"
				:disabled="
					!supportsSafePreflight ||
					!!safetyBusy ||
					loading ||
					initialLoading ||
					!!initialError ||
					paramsLoading ||
					!!paramsError ||
					isApplyingDetection
				"
				@click="testConnection"
				>{{ verifyWrite ? '测试读写并清理' : '只读测试连接' }}</el-button
			>
			<el-button
				type="primary"
				:icon="CheckBadgeIcon"
				:loading="loading"
				:disabled="
					initialLoading ||
					!!initialError ||
					paramsLoading ||
					!!paramsError ||
					!storageItem.type ||
					!!safetyBusy
				"
				@click="submitForm"
				>保存设置</el-button
			>
		</el-form-item>
	</el-form>
</template>

<script setup>
import SafeHtml from '~/components/common/SafeHtml.vue'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { detectStorageSource } from '~/utils/storage-source-recognizer.mjs'
import {
	commonStorageTypes,
	storageSetupGuide,
	isEssentialStorageField,
	storageFieldHint,
	isSecretStorageField,
	storageSetupIssues,
	parseStorageAddress,
} from '~/utils/storage-setup.mjs'
import { CheckBadgeIcon, LinkIcon } from '@heroicons/vue/24/solid'

import useAdminSetting from '~/composables/admin/useAdminSetting'
const { siteSetting } = useAdminSetting()

import useGlobalConfigStore from '~/stores/global-config'
let globalConfigStore = useGlobalConfigStore()

import {
	loadStorageItemReq,
	loadSupportStorageReq,
	storageParamsReq,
	saveStorageSettingReq,
	testStorageConnectionReq,
	applySavedStorageCorsReq,
	existStorageKeyReq,
} from '~/api/admin/admin-storage'
import { ElMessageBox } from 'element-plus'
import {
	loadSharePointSiteListsReq,
	loadSharePointSitesReq,
} from '~/api/tools/tools-sharepoint'
import { loadS3BucketsReq, loadS3CorsConfigReq } from '~/api/tools/tools-s3'
import { loadGDDrivesReq } from '~/api/tools/tools-gd'
import { toClipboard } from '@soerenmartius/vue3-clipboard'
import { concatPath, convertStr, isNotMobile } from '~/utils'
import { getTenantListReq } from '~/api/admin/admin-tenant'
import useStorageConfigStore from '~/stores/storage-config'

let router = useRouter()
let route = useRoute()
const storageConfigStore = useStorageConfigStore()
const isAdmin = computed(() => storageConfigStore.loginInfo.isAdmin === true)
const tenantList = ref([])
const initialLoading = ref(false)
const initialError = ref('')
const paramsLoading = ref(false)
const paramsError = ref('')
const showAdvanced = ref(false)
const setupChecked = ref(false)
const saveError = ref('')
const safetyBusy = ref('')
const safetyNotice = ref('')
const safetyPassed = ref(false)
const verifyWrite = ref(false)
const safetyRequests = createLatestRequest()
const connectionAddress = ref('')
const addressNotice = ref('')
const addressSuggestions = ref({})
const sourceDetectionInput = ref('')
const sourceDetectionCandidates = ref([])
const sourceDetectionNotice = ref('')
const isApplyingDetection = ref(false)
let detectionGeneration = 0
let activeParamsPromise = Promise.resolve()
const initialRequests = createLatestRequest()
const paramRequests = createLatestRequest()
let storageDisposed = false
let saveController
let saveGeneration = 0
let reloadParams = () => {}
let initializeStorage = () => {}

// 当前正在编辑的存储源 key
let currentEditStorageKey = null

let storageEditForm = ref()
const submitForm = async () => {
	if (
		loading.value ||
		safetyBusy.value ||
		isApplyingDetection.value ||
		storageDisposed ||
		initialLoading.value ||
		initialError.value ||
		paramsLoading.value ||
		paramsError.value ||
		!storageItem.value.type
	)
		return
	checkSetup()
	if (setupIssues.value.length) return
	const targetRouteId = String(route.params.storageId || '')
	if (targetRouteId && String(storageItem.value.id) !== targetRouteId) return
	const generation = ++saveGeneration
	const isCurrentTarget = () =>
		!storageDisposed &&
		generation === saveGeneration &&
		String(route.params.storageId || '') === targetRouteId
	loading.value = true
	saveError.value = ''
	try {
		try {
			await storageEditForm.value.validate()
		} catch {
			showAdvanced.value = true
			return
		}
		if (!isCurrentTarget()) return
		const payload = JSON.parse(JSON.stringify(storageItem.value))
		if (!isCurrentTarget()) return
		const controller = new AbortController()
		saveController = controller
		const res = await saveStorageSettingReq(payload, {
			signal: controller.signal,
			showDefaultMsg: false,
		})
		if (!isCurrentTarget() || controller.signal.aborted) return
		storageItem.value.id = res.data
		isEditMode.value = true
		ElMessage.success('存储源已保存。可在空间人员中调整访问权限。')
		router.push('/admin/storage-list')
	} catch (error) {
		if (isCurrentTarget())
			saveError.value = storageErrorDescription(
				error,
				'保存结果未确认。请先返回存储源列表刷新核实，再重试；不要重复提交。'
			)
	} finally {
		if (isCurrentTarget()) {
			loading.value = false
			saveController = undefined
		}
	}
}

/**
 * 初始化支持的存储源列表和存储源数据
 */
let useInitData = () => {
	// 存储源数据
	let storageItem = ref({
		tenantId: null,
		orderNum: null,
		name: '',
		type: '',
		remark: '',
		alias: '',
		defaultSwitchToImgMode: false,
		enable: true,
		enableFileOperator: true,
		enableFileAnnoOperator: false,
		enableCache: false,
		autoRefreshCache: false,
		storageSourceAllParam: {
			endPoint: '',
			pathStyle: '',
			isPrivate: null,
			accessKey: null,
			secretKey: null,
			bucketName: null,
			host: null,
			port: null,
			filePath: null,
			accessToken: null,
			refreshToken: null,
			secretId: null,
			username: null,
			password: null,
			privateKey: null,
			passphrase: null,
			basePath: '',
			domain: '',
			listId: '',
			redirectUri: '',
			siteId: '',
			proxyDomain: '',
			downloadLinkType: '',
			clientId: '',
			clientSecret: '',
			region: '',
			autoConfigCors: false,
			driveId: '',
		},
	})
	const emptyItem = JSON.parse(JSON.stringify(storageItem.value))
	let initializedRouteId

	// 加载指定存储源的数据
	const loadStorageItem = async (storageId, request) => {
		const res = await loadStorageItemReq(storageId, {
			signal: request.signal,
			showDefaultMsg: false,
		})
		if (request.isCurrent() && !storageDisposed) {
			if (String(res.data?.id) !== String(storageId))
				throw new Error('STORAGE_TARGET_CHANGED')
			const params = res.data.storageSourceAllParam
			if (!params || typeof params !== 'object' || Array.isArray(params))
				throw new Error('STORAGE_PARAMS_UNAVAILABLE')
			// Older responses may encode CORS as JSON text. Reject damaged data
			// rather than clearing saved rules or crashing the CORS editor.
			let cors = params.corsConfigList
			if (typeof cors === 'string') cors = JSON.parse(cors || '[]')
			if (cors === null || cors === undefined) cors = []
			if (
				!Array.isArray(cors) ||
				cors.some(
					(rule) =>
						!rule ||
						typeof rule !== 'object' ||
						!Array.isArray(rule.allowedOrigins) ||
						!Array.isArray(rule.allowedMethods) ||
						['allowedHeaders', 'exposeHeaders'].some(
							(key) =>
								rule[key] !== undefined &&
								rule[key] !== null &&
								!Array.isArray(rule[key])
						)
				)
			)
				throw new Error('STORAGE_CORS_UNAVAILABLE')
			params.corsConfigList = cors
			res.data.type = res.data.type.key
			storageItem.value = res.data
			currentEditStorageKey = res.data.key
		}
	}

	let isEditMode = ref(false)

	// 支持的所有存储策略
	let supportStorageType = ref([])
	initializeStorage = async () => {
		const request = initialRequests.begin()
		initialLoading.value = true
		initialError.value = ''
		isEditMode.value = Boolean(route.params.storageId)
		const nextId = String(route.params.storageId || '')
		if (initializedRouteId !== nextId) {
			initializedRouteId = nextId
			paramRequests.cancel()
			storageItem.value = JSON.parse(JSON.stringify(emptyItem))
			currentEditStorageKey = null
		}
		try {
			const jobs = [
				loadSupportStorageReq({
					signal: request.signal,
					showDefaultMsg: false,
				}).then((response) => {
					if (request.isCurrent() && !storageDisposed)
						supportStorageType.value =
							storageConfigStore.loginInfo.role === 'TENANT_ADMIN'
								? response.data.filter((item) => item.key !== 'local')
								: response.data
				}),
			]
			if (isEditMode.value)
				jobs.push(loadStorageItem(route.params.storageId, request))
			else if (isAdmin.value)
				jobs.push(
					getTenantListReq({
						signal: request.signal,
						timeout: 15000,
						showDefaultMsg: false,
					}).then((response) => {
						if (!request.isCurrent() || storageDisposed) return
						tenantList.value = response.data
						if (!storageItem.value.tenantId)
							storageItem.value.tenantId =
								tenantList.value.find((item) => item.enable)?.id || null
					})
				)
			await Promise.all(jobs)
		} catch (error) {
			if (request.isCurrent() && !storageDisposed)
				initialError.value = storageErrorDescription(
					error,
					'读取存储类型或已有配置失败，请重新加载。'
				)
		} finally {
			if (request.isCurrent() && !storageDisposed) initialLoading.value = false
		}
	}
	onMounted(initializeStorage)
	watch(
		() => route.params.storageId,
		() => {
			invalidateSourceDetection()
			sourceDetectionInput.value = ''
			sourceDetectionNotice.value = ''
			saveGeneration++
			saveController?.abort()
			loading.value = false
			initializeStorage()
		}
	)

	// loading
	let loading = ref(false)
	// 表单校验规则
	let rules = ref({
		name: [{ required: true, message: '请输入存储源名称' }],
		key: [
			{
				validator: (rule, value, callback) => {
					if (value === undefined || value === null || value === '') {
						callback()
						return
					}

					if (constant.systemNames.includes(value)) {
						callback(new Error('不可占用系统级名称，请修改。'))
						return
					}

					let reg = /^[\w-]+$/
					if (!reg.test(value)) {
						callback(new Error('只允许使用字母、数字、下划线、横杠。'))
						return
					}

					if (currentEditStorageKey === value) {
						callback()
						return
					}
					existStorageKeyReq({ storageKey: value }, { showDefaultMsg: false })
						.then((res) => {
							if (res.data) {
								callback(new Error('该存储源别名已存在，请修改。'))
							} else {
								callback()
							}
						})
						.catch(() =>
							callback(
								new Error(
									'别名暂时无法核验，请重试；也可在新建时留空由系统分配。'
								)
							)
						)
				},
			},
		],
		type: [{ required: true, message: '存储策略不能为空' }],
		'storageSourceAllParam.url': [
			{
				type: 'url',
				message: '请输入正确的地址，需以 http:// 或 https:// 开头',
			},
		],
		'storageSourceAllParam.domain': [
			{
				type: 'url',
				message: '请输入正确的域名，需以 http:// 或 https:// 开头',
			},
			{
				validator: (rule, value, callback) => {
					if (value === undefined || value === null || value === '') {
						callback()
						return
					}

					if (
						window.location.protocol === 'https:' &&
						value.indexOf('http://') === 0
					) {
						callback(
							new Error(
								'检测到当前 学生云盘 站点是 https 协议, 受浏览器限制, 此处也必须是 https 协议, 否则可能无法正常使用.'
							)
						)
						return
					}

					callback()
				},
			},
		],
		'storageSourceAllParam.endPointScheme': [
			{
				validator: (rule, value, callback) => {
					if (value === undefined || value === null || value === '') {
						callback()
						return
					}

					if (window.location.protocol === 'https:' && value === 'http') {
						callback(
							new Error(
								'检测到当前 学生云盘 站点是 https 协议, 受浏览器限制, 此处也必须是 https 协议, 否则可能无法正常使用.'
							)
						)
						return
					}

					callback()
				},
			},
		],
		'storageSourceAllParam.filePath': [
			{
				validator: (rule, value, callback) => {
					if (value === undefined || value === null || value === '') {
						callback(new Error('本地存储路径不能为空'))
						return
					}

					let isLinuxAbsolutePath = /^\/.*/.test(value)
					let isWindowsAbsolutePath = /^[a-zA-Z]+:.*/.test(value)
					if (!isLinuxAbsolutePath && !isWindowsAbsolutePath) {
						callback(
							new Error(
								'本地存储路径必须是绝对路径，Linux 以 / 开头，Windows 以盘符开头'
							)
						)
						return
					}

					callback()
				},
			},
		],
		'storageSourceAllParam.bucketName': [
			{
				required: true,
				message: '请选择存储器列表',
			},
		],
	})

	return { storageItem, supportStorageType, loading, rules, isEditMode }
}
let { storageItem, supportStorageType, loading, rules, isEditMode } =
	useInitData()

const supportsSafePreflight = computed(() =>
	['local', 's3', 'aliyun', 'minio', 'huawei', 'tencent', 'qiniu'].includes(
		storageItem.value.type
	)
)

function cancelSafety() {
	safetyRequests.cancel()
	safetyBusy.value = ''
	safetyNotice.value = ''
	safetyPassed.value = false
}

function canStartSafety() {
	return (
		!storageDisposed &&
		!safetyBusy.value &&
		!loading.value &&
		!isApplyingDetection.value &&
		!initialLoading.value &&
		!initialError.value &&
		!paramsLoading.value &&
		!paramsError.value &&
		(!route.params.storageId ||
			String(storageItem.value.id) === String(route.params.storageId))
	)
}

async function testConnection() {
	if (!canStartSafety() || !supportsSafePreflight.value) return
	checkSetup()
	if (setupIssues.value.length) return
	const request = safetyRequests.begin()
	const target = String(route.params.storageId || '')
	safetyBusy.value = 'test'
	safetyNotice.value = ''
	safetyPassed.value = false
	const isCurrent = () =>
		request.isCurrent() &&
		!storageDisposed &&
		String(route.params.storageId || '') === target
	try {
		await storageEditForm.value.validate()
		if (!isCurrent()) return
		const response = await testStorageConnectionReq(
			{
				...JSON.parse(JSON.stringify(storageItem.value)),
				verifyWrite: verifyWrite.value,
			},
			{ signal: request.signal, showDefaultMsg: false }
		)
		if (!isCurrent()) return
		safetyPassed.value = response.data?.passed === true
		safetyNotice.value =
			response.data?.message ||
			'只读检查完成；未保存配置，也未验证上传、删除权限。'
	} catch (error) {
		if (isCurrent())
			safetyNotice.value = storageErrorDescription(
				error,
				'只读检查失败，请检查连接参数；未保存配置。'
			)
	} finally {
		if (isCurrent()) safetyBusy.value = ''
	}
}

async function applySavedCors() {
	if (
		!canStartSafety() ||
		!isEditMode.value ||
		!storageItem.value.id ||
		!constant.storageType.s3Type.includes(storageItem.value.type)
	)
		return
	const request = safetyRequests.begin()
	const id = storageItem.value.id
	const target = String(route.params.storageId || '')
	safetyBusy.value = 'cors'
	safetyNotice.value = ''
	safetyPassed.value = false
	const isCurrent = () =>
		request.isCurrent() &&
		!storageDisposed &&
		storageItem.value.id === id &&
		String(route.params.storageId || '') === target
	try {
		try {
			await ElMessageBox.confirm(
				'此操作将覆盖已保存存储桶的远端跨域规则，无法通过数据库事务撤销。仅使用已保存的连接与规则，当前表单未保存的修改不会参与。请先保存所需修改并重新进入此页。是否明确确认应用？',
				'单独确认远端跨域修改',
				{
					type: 'warning',
					confirmButtonText: '确认应用已保存规则',
					cancelButtonText: '取消',
				}
			)
		} catch {
			return
		}
		if (!isCurrent()) return
		await applySavedStorageCorsReq(id, {
			signal: request.signal,
			showDefaultMsg: false,
		})
		if (!isCurrent()) return
		safetyPassed.value = true
		safetyNotice.value =
			'已单独应用已保存的跨域规则。普通保存和服务重启不会再次应用。'
	} catch (error) {
		if (isCurrent())
			safetyNotice.value = storageErrorDescription(
				error,
				'远端应用结果未确认，请先读取远端规则核实，不要重复提交。'
			)
	} finally {
		if (isCurrent()) safetyBusy.value = ''
	}
}

watch(
	() => [
		route.params.storageId,
		storageItem.value.type,
		storageItem.value.storageSourceAllParam,
		storageConfigStore.loginInfo.username,
		storageConfigStore.loginInfo.tenantId,
		storageConfigStore.loginInfo.isAdmin,
	],
	cancelSafety,
	{ deep: true, flush: 'sync' }
)

const commonTypes = computed(() => commonStorageTypes(supportStorageType.value))
const setupGuide = computed(() => storageSetupGuide(storageItem.value.type))
const canRecognizeAddress = computed(() =>
	['webdav', 'ftp', 'sftp', 'aliyun', 's3', 'minio'].includes(
		storageItem.value.type
	)
)
const addressSuggestionEntries = computed(() =>
	Object.entries(addressSuggestions.value)
)
const advancedFieldCount = computed(
	() =>
		storageSourceParamList.value.filter(
			(field) =>
				fieldShowMap.value[field.key] && !isEssentialStorageField(field)
		).length
)
const setupIssues = computed(() => {
	if (!setupChecked.value) return []
	const issues = storageSetupIssues({
		type: storageItem.value.type,
		params: storageItem.value.storageSourceAllParam,
		fields: storageSourceParamList.value,
		fieldShowMap: fieldShowMap.value,
	})
	if (!storageItem.value.name?.trim())
		issues.unshift({ key: 'name', message: '请填写存储源名称。' })
	if (
		!isEditMode.value &&
		isAdmin.value &&
		!tenantList.value.some(
			(tenant) => tenant.id === storageItem.value.tenantId && tenant.enable
		)
	)
		issues.unshift({ key: 'tenantId', message: '请选择可用工作空间。' })
	return issues
})
function checkSetup() {
	if (
		isApplyingDetection.value ||
		initialLoading.value ||
		initialError.value ||
		paramsLoading.value ||
		paramsError.value ||
		!storageSourceParamList.value.length
	)
		return
	setupChecked.value = true
	if (
		setupIssues.value.some((issue) =>
			storageSourceParamList.value.some(
				(field) => field.key === issue.key && !isEssentialStorageField(field)
			)
		)
	)
		showAdvanced.value = true
}
function fieldName(key) {
	return (
		storageSourceParamList.value.find((field) => field.key === key)?.name || key
	)
}
function detectionFieldName(key) {
	return (
		{
			filePath: '服务器目录',
			host: '主机',
			port: '端口',
			basePath: '基路径',
			url: '连接地址',
			endPoint: 'Endpoint',
			endPointScheme: '连接协议',
			bucketName: 'Bucket',
		}[key] || key
	)
}
function invalidateSourceDetection() {
	detectionGeneration++
	isApplyingDetection.value = false
	sourceDetectionCandidates.value = []
}
watch(
	sourceDetectionInput,
	() => {
		invalidateSourceDetection()
		sourceDetectionNotice.value = ''
	},
	{ flush: 'sync' }
)
watch(
	[
		() => storageConfigStore.loginInfo.username,
		() => storageConfigStore.loginInfo.tenantId,
		() => storageConfigStore.loginInfo.isAdmin,
	],
	() => {
		invalidateSourceDetection()
		sourceDetectionNotice.value = ''
	}
)
function recognizeStorageSource() {
	if (
		loading.value ||
		initialLoading.value ||
		initialError.value ||
		isApplyingDetection.value ||
		isEditMode.value ||
		storageDisposed
	)
		return
	invalidateSourceDetection()
	const result = detectStorageSource(
		sourceDetectionInput.value,
		supportStorageType.value
	)
	sourceDetectionCandidates.value = result.candidates
	sourceDetectionNotice.value = result.notice
}
async function applyDetectedSource(candidate) {
	if (
		loading.value ||
		initialLoading.value ||
		initialError.value ||
		isApplyingDetection.value ||
		isEditMode.value ||
		storageDisposed ||
		!sourceDetectionCandidates.value.includes(candidate) ||
		!supportStorageType.value.some((item) => item.key === candidate.type)
	)
		return
	const generation = ++detectionGeneration
	const routeId = String(route.params.storageId || '')
	const isCurrent = () =>
		!storageDisposed &&
		generation === detectionGeneration &&
		!isEditMode.value &&
		String(route.params.storageId || '') === routeId &&
		storageItem.value.type === candidate.type
	isApplyingDetection.value = true
	sourceDetectionNotice.value = '正在加载此存储方式的字段…'
	try {
		storageItem.value.type = candidate.type
		await nextTick()
		if (!isCurrent()) return
		if (
			!paramsLoading.value &&
			(!storageSourceParamList.value.length || paramsError.value)
		)
			reloadParams()
		await activeParamsPromise
		if (!isCurrent()) return
		if (paramsError.value || !storageSourceParamList.value.length) {
			sourceDetectionNotice.value =
				'参数未能加载，未填入识别结果。请重新加载参数后再采用。'
			return
		}
		const allowedFields = new Set(
			storageSourceParamList.value
				.filter((field) => !field.hidden && !isSecretStorageField(field.key))
				.map((field) => field.key)
		)
		for (const [key, value] of Object.entries(candidate.suggestions))
			if (allowedFields.has(key))
				storageItem.value.storageSourceAllParam[key] = value
		setupChecked.value = false
		sourceDetectionCandidates.value = []
		sourceDetectionNotice.value = `已按${candidate.title}填入，请补充认证信息并核对参数；尚未连接或保存。`
	} finally {
		if (generation === detectionGeneration) isApplyingDetection.value = false
	}
}
function recognizeAddress() {
	if (
		loading.value ||
		isApplyingDetection.value ||
		paramsLoading.value ||
		paramsError.value ||
		storageDisposed
	)
		return
	const result = parseStorageAddress(
		connectionAddress.value,
		storageItem.value.type
	)
	addressSuggestions.value = result.suggestions
	addressNotice.value = result.notice
}
function applyAddressSuggestions() {
	if (
		loading.value ||
		isApplyingDetection.value ||
		paramsLoading.value ||
		paramsError.value ||
		storageDisposed
	)
		return
	for (const [key, value] of addressSuggestionEntries.value) {
		if (storageSourceParamList.value.some((field) => field.key === key))
			storageItem.value.storageSourceAllParam[key] = value
	}
	addressSuggestions.value = {}
	addressNotice.value = '已填入识别结果，请检查连接信息；尚未保存。'
}
function storageErrorDescription(error, fallback) {
	const code = error?.data?.code || error?.response?.data?.code
	return code === 401 || code === 403
		? '登录状态或管理权限已失效，请重新登录后再操作。'
		: fallback
}
let suggestedName = ''
watch(
	() => storageItem.value.type,
	(type, previous) => {
		showAdvanced.value = false
		setupChecked.value = false
		connectionAddress.value = ''
		addressSuggestions.value = {}
		addressNotice.value = ''
		if (!isEditMode.value) {
			if (!type) return
			const suggestion = storageSetupGuide(type).nameSuggestion
			if (!storageItem.value.name || storageItem.value.name === suggestedName)
				storageItem.value.name = suggestion
			suggestedName = suggestion
			if (previous && previous !== type)
				storageItem.value.storageSourceAllParam = {
					corsConfigList: [],
					domain: '',
					basePath: '',
					redirectUri: '',
				}
		}
	},
	{ flush: 'sync' }
)
onBeforeUnmount(() => {
	storageDisposed = true
	cancelSafety()
	invalidateSourceDetection()
	initialRequests.cancel()
	paramRequests.cancel()
	saveController?.abort()
})

let useS3Util = () => {
	let bucketList = ref([])
	let corsTips = ref('')
	const remoteCors = ref(null)
	const discoveryBusy = ref('')
	const discoveryMessage = ref('')
	const discoveryRequests = createLatestRequest()

	let s3Credentials = computed(() => {
		return {
			accessKey:
				storageItem.value.storageSourceAllParam.accessKey ||
				storageItem.value.storageSourceAllParam.secretId,
			secretKey: storageItem.value.storageSourceAllParam.secretKey,
			endPoint: storageItem.value.storageSourceAllParam.endPoint,
			endPointScheme: storageItem.value.storageSourceAllParam.endPointScheme,
			region: storageItem.value.storageSourceAllParam.region,
			bucketName: storageItem.value.storageSourceAllParam.bucketName,
		}
	})

	const canReadBuckets = computed(
		() =>
			constant.storageType.s3Type.includes(storageItem.value.type) &&
			Boolean(
				s3Credentials.value.accessKey &&
					s3Credentials.value.secretKey &&
					s3Credentials.value.endPoint
			)
	)
	const canReadCors = computed(
		() => canReadBuckets.value && Boolean(s3Credentials.value.bucketName)
	)
	watch([() => storageItem.value.type, s3Credentials], (current, previous) => {
		discoveryRequests.cancel()
		discoveryBusy.value = ''
		discoveryMessage.value = ''
		if (
			!previous ||
			previous[0] !== current[0] ||
			['accessKey', 'secretKey', 'endPoint', 'endPointScheme', 'region'].some(
				(key) => previous[1]?.[key] !== current[1]?.[key]
			)
		)
			bucketList.value = []
		remoteCors.value = null
	})
	async function discover(kind) {
		if (
			discoveryBusy.value ||
			loading.value ||
			storageDisposed ||
			(kind === 'cors' ? !canReadCors.value : !canReadBuckets.value)
		)
			return
		const request = discoveryRequests.begin()
		discoveryBusy.value = kind
		discoveryMessage.value = ''
		const credentials = { ...s3Credentials.value }
		if (
			typeof credentials.endPoint === 'string' &&
			!/^https?:\/\//i.test(credentials.endPoint) &&
			['http', 'https'].includes(credentials.endPointScheme)
		)
			credentials.endPoint = `${credentials.endPointScheme}://${credentials.endPoint}`
		delete credentials.endPointScheme
		try {
			const response = await (kind === 'cors'
				? loadS3CorsConfigReq
				: loadS3BucketsReq)(credentials, { signal: request.signal })
			if (!request.isCurrent() || storageDisposed) return
			if (kind === 'buckets') {
				bucketList.value = response.data || []
				discoveryMessage.value = `已读取 ${bucketList.value.length} 个存储桶。没有列桶权限时，也可以手动填写桶名。`
			} else {
				remoteCors.value =
					typeof response.data === 'string'
						? response.data
						: JSON.stringify(response.data, null, 2)
				discoveryMessage.value =
					'已读取远端跨域配置，仅展示；不会覆盖表单或自动写回存储桶。'
			}
		} catch {
			if (request.isCurrent() && !storageDisposed)
				discoveryMessage.value =
					'读取失败，请检查凭据、端点和只读权限。辅助读取不支持私网端点；可保留手动桶名，不要反复提交。'
		} finally {
			if (request.isCurrent() && !storageDisposed) discoveryBusy.value = ''
		}
	}
	onBeforeUnmount(() => discoveryRequests.cancel())
	return {
		bucketList,
		corsTips,
		remoteCors,
		discoveryBusy,
		discoveryMessage,
		canReadBuckets,
		canReadCors,
		readBuckets: () => discover('buckets'),
		readCors: () => discover('cors'),
	}
}

let {
	bucketList,
	corsTips,
	remoteCors,
	discoveryBusy,
	discoveryMessage,
	canReadBuckets,
	canReadCors,
	readBuckets,
	readCors,
} = useS3Util()

let useGDUtil = () => {
	let drivesList = ref([])

	let param = computed(() => {
		return {
			accessToken: storageItem.value.storageSourceAllParam.accessToken,
		}
	})

	watch(
		() => param.value,
		(val) => {
			if (storageItem.value.type !== 'google-drive') {
				return
			}
			loadDrives(val)
		}
	)

	const loadDrives = (param) => {
		loadGDDrivesReq(param)
			.then((res) => {
				drivesList.value = res.data
			})
			.catch(() => {
				drivesList.value = []
			})
	}

	return { drivesList }
}

let { drivesList } = useGDUtil()

/**
 * sharepoint 工具
 */
let useSharePointUtil = () => {
	let sharepointSites = ref([])
	let sharepointSiteLists = ref([])

	const isSharePoint = (type) => {
		return type.indexOf('sharepoint') !== -1
	}

	const getSharePointType = (storageType) => {
		return storageType === 'sharepoint' ? 'Standard' : 'China'
	}

	watch(
		() => storageItem.value.storageSourceAllParam.accessToken,
		(newVal) => {
			if (newVal) {
				loadSharePointSites()
			}
		}
	)

	const loadSharePointSites = () => {
		let storageType = storageItem.value.type
		if (isSharePoint(storageType)) {
			let param = {
				type: getSharePointType(storageType),
				accessToken: storageItem.value.storageSourceAllParam.accessToken,
			}

			loadSharePointSitesReq(param).then((res) => {
				sharepointSites.value = res.data
			})
		}
	}

	watch(
		() => storageItem.value.storageSourceAllParam.siteId,
		(newVal) => {
			if (newVal) {
				loadSharePointSiteLists()
			}
		}
	)

	const loadSharePointSiteLists = () => {
		let storageType = storageItem.value.type
		if (isSharePoint(storageType)) {
			let param = {
				type: getSharePointType(storageType),
				accessToken: storageItem.value.storageSourceAllParam.accessToken,
				siteId: storageItem.value.storageSourceAllParam.siteId,
			}

			loadSharePointSiteListsReq(param).then((res) => {
				sharepointSiteLists.value = res.data
			})
		}
	}

	return { sharepointSites, sharepointSiteLists, isSharePoint }
}
let { sharepointSites, sharepointSiteLists, isSharePoint } = useSharePointUtil()

/**
 * 获取存储策略的参数列表, 及监听切换存储策略时, 自动重新加载参数列表.
 */
let useLoadStorageSourceParamList = () => {
	// 监听切换存储策略
	watch(
		() => storageItem.value.type,
		(newVal) => {
			activeParamsPromise = loadStorageSourceParamList(newVal)
		}
	)

	// 获取指定存储策略的所有参数
	let storageSourceParamList = ref([])
	let fieldShowMap = ref({})

	const loadStorageSourceParamList = async (storageType) => {
		const request = paramRequests.begin()
		storageSourceParamList.value = []
		fieldShowMap.value = {}
		paramsError.value = ''
		if (!storageType) {
			paramsLoading.value = false
			return
		}
		paramsLoading.value = true
		try {
			const res = await storageParamsReq(
				{ storageType },
				{ signal: request.signal, showDefaultMsg: false }
			)
			if (
				!request.isCurrent() ||
				storageDisposed ||
				storageItem.value.type !== storageType
			)
				return
			if (
				!Array.isArray(res.data) ||
				!res.data.length ||
				res.data.some(
					(field) =>
						!field ||
						typeof field.key !== 'string' ||
						!field.key ||
						typeof field.type !== 'string'
				)
			)
				throw new Error('STORAGE_SCHEMA_UNAVAILABLE')
			storageSourceParamList.value = res.data
			for (let storageSourceParam of storageSourceParamList.value) {
				// 如果不是编辑状态，且有默认值，则设置默认值
				if (
					!isEditMode.value &&
					(storageItem.value.storageSourceAllParam[storageSourceParam.key] ===
						undefined ||
						storageItem.value.storageSourceAllParam[storageSourceParam.key] ===
							null ||
						storageItem.value.storageSourceAllParam[storageSourceParam.key] ===
							'')
				) {
					const defaultValue = storageSourceParam.defaultValue
					let converted =
						defaultValue === null || defaultValue === undefined
							? ''
							: convertStr(defaultValue)
					if (
						storageSourceParam.type === 'number' &&
						converted !== '' &&
						Number.isFinite(Number(converted))
					)
						converted = Number(converted)
					storageItem.value.storageSourceAllParam[storageSourceParam.key] =
						converted
				} else if (
					storageSourceParam.key === 'redirectUri' &&
					!storageItem.value.storageSourceAllParam[storageSourceParam.key]
				) {
					// 如果是 redirectUri, 且没有值, 则设置默认值
					storageItem.value.storageSourceAllParam[storageSourceParam.key] =
						storageSourceParam.defaultValue
				}

				if (storageSourceParam.hidden === true) {
					fieldShowMap.value[storageSourceParam.key] = false
					continue
				}
				if (!storageSourceParam.condition) {
					fieldShowMap.value[storageSourceParam.key] = true
				} else {
					fieldShowMap.value[storageSourceParam.key] = computed(() => {
						const predicate = (
							conditionField,
							conditionValue,
							conditionSymbol
						) => {
							// 如果依赖的字段不显示, 则不用判断条件，直接返回 false，如果字段以 ? 结尾，则表示不用判断关联字段是否显示
							let ignoreRelatedField = conditionField.endsWith('?')
							if (ignoreRelatedField) {
								conditionField = conditionField.replace('?', '')
							}
							if (
								!ignoreRelatedField &&
								fieldShowMap.value[conditionField] === false
							) {
								return false
							}

							conditionValue = convertStr(conditionValue)

							if (conditionSymbol === '==') {
								if (conditionValue === '') {
									return (
										storageItem.value.storageSourceAllParam[conditionField] ===
											conditionValue ||
										storageItem.value.storageSourceAllParam[conditionField] ===
											null
									)
								}
								return (
									storageItem.value.storageSourceAllParam[conditionField] ===
									conditionValue
								)
							} else if (conditionSymbol === '!=') {
								return (
									storageItem.value.storageSourceAllParam[conditionField] !==
									conditionValue
								)
							} else {
								return true
							}
						}
						const parseConditionItem = (item) => {
							let conditionSymbol
							if (item.indexOf('==') !== -1) {
								conditionSymbol = '=='
							} else if (item.indexOf('!=') !== -1) {
								conditionSymbol = '!='
							} else {
								alert(`不支持的表达式 ${item}`)
							}

							let [conditionField, conditionValue] = item.split(conditionSymbol)
							return predicate(conditionField, conditionValue, conditionSymbol)
						}

						// 如 'enableProxyDownload==true||token!=null'
						let condition = storageSourceParam.condition

						// 如果是 || 条件，只要有一个条件满足即可，但这里要不能短路，要全部判断，因为涉及到依赖字段的显示隐藏(如判断条件依赖的字段是隐藏状态，那么当前字段也应该隐藏)
						if (condition.indexOf('||') !== -1) {
							return condition
								.split('||')
								.some((item) => parseConditionItem(item))
						} else if (condition.indexOf('&&') !== -1) {
							// 如果是 && 条件
							return condition
								.split('&&')
								.every((item) => parseConditionItem(item))
						} else {
							return parseConditionItem(condition)
						}
					})
				}
			}
		} catch (error) {
			if (request.isCurrent() && !storageDisposed)
				paramsError.value = storageErrorDescription(
					error,
					'读取当前方式的参数失败，请重新加载后再保存。'
				)
		} finally {
			if (request.isCurrent() && !storageDisposed) paramsLoading.value = false
		}
	}
	reloadParams = () =>
		(activeParamsPromise = loadStorageSourceParamList(storageItem.value.type))

	return { storageSourceParamList, fieldShowMap }
}
let { storageSourceParamList, fieldShowMap } = useLoadStorageSourceParamList()

watch(
	() => storageSourceParamList.value,
	(newVal) => {
		if (newVal) {
			const nextRules = Object.fromEntries(
				Object.entries(rules.value).filter(
					([key]) => !key.startsWith('storageSourceAllParam.')
				)
			)
			for (const item of newVal)
				if (item.required && !item.hidden)
					nextRules[`storageSourceAllParam.${item.key}`] = [
						{
							validator: (_rule, value, callback) => {
								if (
									fieldShowMap.value[item.key] &&
									(value === undefined ||
										value === null ||
										value === '' ||
										(typeof value === 'string' && !value.trim()))
								)
									callback(new Error(`${item.name}不能为空`))
								else callback()
							},
						},
					]
			rules.value = nextRules
		}
	}
)

/**
 *  复制
 */
let copyText = (text) => {
	toClipboard(text).then(() => {
		ElMessage.success('复制成功')
	})
}

const showSchemaTips = computed(() => {
	return (
		window.location.protocol === 'https:' &&
		String(storageItem.value.storageSourceAllParam['domain'] || '').indexOf(
			'http://'
		) === 0
	)
})

const trimInputFields = [
	'endPoint',
	'accessKey',
	'secretKey',
	'accessToken',
	'refreshToken',
	'secretId',
	'clientId',
	'clientSecret',
]

const serverAddress = computed(() => {
	return (
		siteSetting.value?.forceBackendAddress || globalConfigStore.serverAddress
	)
})

const inputIsReadOnly = (key) => {
	if (
		storageItem.value.type === 'open115' &&
		(key === 'accessToken' || key === 'refreshToken')
	) {
		return true
	}
	return false
}

const onOpen115LoginSuccess = (data) => {
	storageItem.value.storageSourceAllParam['accessToken'] = data.accessToken
	storageItem.value.storageSourceAllParam['refreshToken'] = data.refreshToken
	storageItem.value.storageSourceAllParam['refreshTokenExpiredAt'] =
		data.expiredAt
}
</script>

<style scoped>
.student-cloud-storage-edit :deep(.el-form-item-input > .el-select) {
	width: 100%;
}
.storage-setup-intro,
.storage-setup-guide,
.storage-address-helper,
.storage-s3-discovery {
	margin: 0 0 22px;
	padding: 20px;
	border: 1px solid #dfe6dc;
	border-radius: 12px;
	background: #f8f9f5;
	color: #334c42;
}
.storage-setup-intro h2,
.storage-setup-section-heading h2,
.storage-s3-discovery h2 {
	margin: 8px 0;
	font-size: 18px;
	font-weight: 600;
}
.storage-setup-intro p,
.storage-s3-discovery p,
.storage-setup-guide li {
	font-size: 13px;
	line-height: 1.7;
	color: #67776d;
}
.storage-setup-eyebrow {
	font-size: 12px;
	color: #477664;
}
.storage-source-detection {
	margin-top: 18px;
}
.storage-source-detection > label {
	display: block;
	margin-bottom: 10px;
	font-size: 14px;
	font-weight: 500;
}
.storage-detection-candidates {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
	gap: 12px;
	margin: 16px 0;
}
.storage-detection-candidates article {
	min-width: 0;
	padding: 16px;
	background: #fff;
	border: 1px solid #dce4d7;
	border-radius: 9px;
}
.storage-detection-heading {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
	align-items: center;
}
.storage-detection-heading strong {
	font-size: 14px;
}
.storage-detection-heading span {
	font-size: 11px;
	color: #477664;
}
.storage-detection-candidates dl {
	margin: 12px 0;
	font-size: 12px;
	line-height: 1.7;
	overflow-wrap: anywhere;
}
.storage-detection-candidates dl > div {
	display: flex;
	gap: 8px;
}
.storage-detection-candidates dt {
	flex-shrink: 0;
	color: #718071;
}
.storage-detection-candidates dd {
	margin: 0;
	min-width: 0;
}
.storage-type-choices {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 10px;
	margin-top: 16px;
}
.storage-type-choices button {
	padding: 16px;
	border: 1px solid #dce4d7;
	border-radius: 9px;
	background: #fff;
	text-align: left;
	cursor: pointer;
}
.storage-type-choices button[aria-pressed='true'] {
	border-color: #2a7866;
	background: #e8f1e9;
}
.storage-type-choices strong,
.storage-type-choices small {
	display: block;
}
.storage-type-choices strong {
	color: #2e473b;
	font-size: 14px;
}
.storage-type-choices small {
	margin-top: 7px;
	color: #718071;
	font-size: 12px;
	line-height: 1.5;
}
.storage-type-choices button:focus-visible {
	outline: 2px solid #2a7866;
	outline-offset: 2px;
}
.storage-setup-section-heading {
	display: flex;
	gap: 12px;
	justify-content: space-between;
	align-items: center;
	margin: 18px 0;
}
.storage-address-helper label {
	display: block;
	margin-bottom: 10px;
	font-size: 13px;
}
.storage-address-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 10px;
}
.storage-address-actions > .el-input {
	flex: 1;
	min-width: 180px;
}
.storage-address-result {
	display: grid;
	gap: 8px;
	margin-top: 12px;
	font-size: 13px;
	overflow-wrap: anywhere;
}
.storage-s3-discovery pre {
	padding: 12px;
	max-height: 220px;
	overflow: auto;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
	background: #fff;
}
.storage-setup-error {
	padding: 12px 16px;
	margin-bottom: 16px;
	background: #fff3eb;
	color: #995828;
	border-radius: 8px;
	font-size: 13px;
	line-height: 1.7;
}
.storage-setup-success {
	padding: 12px;
	color: #277566;
	font-size: 13px;
}
.storage-cors-warning {
	color: #995828;
	font-size: 12px;
	line-height: 1.7;
}
@media (max-width: 640px) {
	.storage-detection-candidates {
		grid-template-columns: minmax(0, 1fr);
	}
	.storage-type-choices {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}
	.storage-setup-intro,
	.storage-setup-guide,
	.storage-address-helper,
	.storage-s3-discovery {
		padding: 14px;
	}
}
</style>

<route lang="yaml">
meta:
  layout: admin
  name: 新增存储源
</route>
