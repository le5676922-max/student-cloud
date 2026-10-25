<template>
	<section class="member-permissions" aria-label="成员存储权限">
		<header class="permission-person">
			<div class="permission-person-copy">
				<span class="member-mark" aria-hidden="true">{{
					(member.nickname || member.username || '成').slice(0, 1)
				}}</span>
				<div>
					<h2>{{ member.nickname || member.username || '成员' }}的权限</h2>
					<p>{{ member.accountId || member.username || '普通成员' }}</p>
				</div>
			</div>
			<button
				type="button"
				:disabled="saving || loading || !allowed"
				@click="load"
			>
				重新读取
			</button>
		</header>
		<div class="permission-body" :aria-busy="loading">
			<p v-if="error" class="permission-error" role="alert">{{ error }}</p>
			<p v-if="loading" class="permission-loading" role="status">
				正在读取可授予的权限…
			</p>
			<template v-else-if="draft">
				<div class="permission-section-heading">
					<h3>当前存储源</h3>
					<span
						>{{ draft.storages.filter((row) => row.enable).length }} /
						{{ draft.storages.length }} 已开启</span
					>
				</div>
				<p class="permission-intro">
					设置成员可以访问的目录与文件操作，授权范围不能超过你当前已有的权限。
				</p>
				<div v-if="hasScopeConflict" class="permission-warning" role="alert">
					<strong>部分已有授权超出你的可授范围</strong>
					<p>先调整下方选项，或使用此按钮收窄。未列出的存储源授权不会变更。</p>
					<button type="button" :disabled="saving" @click="narrowToAllowed">
						按可授范围收窄
					</button>
				</div>
				<details
					v-for="(row, index) in draft.storages"
					:key="row.storageSourceId"
					class="storage-permission"
					:open="index === 0 || draft.storages.length < 3"
				>
					<summary>
						<span>{{ row.storageSourceName }}</span
						><small :class="{ 'is-enabled': row.enable }">{{
							row.enable ? '允许访问' : '已关闭'
						}}</small>
					</summary>
					<div class="storage-permission-body">
						<label class="permission-enabled"
							><input
								v-model="row.enable"
								type="checkbox"
								:disabled="saving"
							/>允许此成员使用存储源</label
						>
						<template v-if="row.enable">
							<label class="permission-root"
								><span>授权根目录</span
								><input
									v-model="row.rootPath"
									maxlength="1024"
									:disabled="saving"
									spellcheck="false"
							/></label>
							<p class="permission-range">
								可授予范围：<code>{{ row.allowedRootPath }}</code> 及其子目录
							</p>
							<label class="permission-root"
								><span>容量上限（GB）</span>
								<input
									v-model.number="row.quotaGB"
									type="number"
									min="0"
									max="102400"
									step="0.01"
									:disabled="saving"
								/>
							</label>
							<p class="permission-range">
								个人目录与其他成员隔离。容量按此存储源分别计算，0
								表示暂不允许新增文件。
							</p>
							<fieldset>
								<legend>可执行操作</legend>
								<div class="permission-options">
									<label
										v-for="option in optionsFor(row.allowedPermissions)"
										:key="option.value"
										><input
											v-model="row.permissions"
											type="checkbox"
											:value="option.value"
											:disabled="saving"
										/>{{ option.name }}</label
									>
								</div>
							</fieldset>
						</template>
					</div>
				</details>
				<div v-if="!draft.storages.length" class="permission-empty">
					<strong>暂无可委派的存储源</strong>
					<p>
						你目前没有可委派的存储源授权，请先确认空间的存储源和当前账号授权。
					</p>
					<p v-if="defaultOptions.length">
						下方可设置审批云盘申请时的权限预设，不会自动开通云盘。
					</p>
				</div>
				<details class="permission-advanced">
					<summary>
						<span>云盘申请权限预设</span><small>高级设置</small>
					</summary>
					<div class="advanced-content">
						<p>
							审批申请时未另选操作权限才采用此预设，不自动开通云盘，也不修改已有授权。
						</p>
						<div v-if="defaultOptions.length" class="permission-options">
							<label v-for="option in defaultOptions" :key="option.value"
								><input
									v-model="draft.defaultPermissions"
									type="checkbox"
									:value="option.value"
									:disabled="saving"
								/>{{ option.name }}</label
							>
						</div>
						<p v-else>当前没有可委派的默认操作权限。</p>
					</div>
				</details>
			</template>
		</div>
		<footer class="permission-footer">
			<small>{{
				draft
					? '保存只影响当前成员，未列出的历史授权保持不变。'
					: '加载成功后可编辑权限。'
			}}</small>
			<div>
				<button type="button" :disabled="saving" @click="requestClose">
					取消</button
				><button
					class="primary"
					type="button"
					:disabled="!canSave"
					@click="save"
				>
					{{ saving ? '正在保存…' : '保存权限' }}
				</button>
			</div>
		</footer>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import useStorageConfigStore from '~/stores/storage-config'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import {
	getWorkspaceMemberPermissionsReq,
	saveWorkspaceMemberPermissionsReq,
} from '~/api/home/workspace-admin'

const props = defineProps({ member: { type: Object, required: true } })
const emit = defineEmits(['close', 'saved', 'busy'])
const store = useStorageConfigStore()
const loading = ref(false)
const saving = ref(false)
const draft = ref(null)
const error = ref('')
const originalDefaults = ref([])
const requests = createLatestRequest()
let disposed = false
const identity = computed(
	() =>
		`${store.loginInfo?.isLogin}:${store.loginInfo?.role}:${store.loginInfo?.tenantId}:${store.loginInfo?.username}`
)
const allowed = computed(
	() =>
		store.loginInfo?.isLogin === true &&
		store.loginInfo?.role === 'TENANT_ADMIN' &&
		Number.isInteger(props.member?.id) &&
		props.member.id > 2 &&
		!['PENDING', 'REJECTED'].includes(props.member.membershipStatus)
)
const optionsFor = (permissions) =>
	(draft.value?.permissionOptions || []).filter((option) =>
		permissions.includes(option.value)
	)
const defaultOptions = computed(() =>
	optionsFor(draft.value?.allowedDefaultPermissions || [])
)
const hasScopeConflict = computed(
	() =>
		!!draft.value &&
		(draft.value.defaultPermissions.some(
			(value) => !draft.value.allowedDefaultPermissions.includes(value)
		) ||
			draft.value.storages.some(
				(row) =>
					!withinRoot(row.rootPath, row.allowedRootPath) ||
					row.permissions.some(
						(value) => !row.allowedPermissions.includes(value)
					)
			))
)
const canSave = computed(
	() =>
		allowed.value &&
		!!draft.value &&
		!loading.value &&
		!saving.value &&
		!hasScopeConflict.value &&
		draft.value.storages.every(
			(row) =>
				Number.isFinite(row.quotaGB) &&
				row.quotaGB >= 0 &&
				row.quotaGB <= 102400
		) &&
		(draft.value.storages.length > 0 ||
			defaultOptions.value.length > 0 ||
			originalDefaults.value.length > 0)
)

function normalizedRoot(value) {
	if (
		typeof value !== 'string' ||
		value.length > 1024 ||
		!value.startsWith('/') ||
		value.includes('\\') ||
		value.includes('%') ||
		[...value].some((character) => {
			const code = character.charCodeAt(0)
			return code < 32 || (code >= 127 && code <= 159)
		})
	)
		return null
	const parts = value.split('/').filter(Boolean)
	if (parts.some((part) => part === '.' || part === '..')) return null
	return parts.length ? `/${parts.join('/')}` : '/'
}
function withinRoot(value, parent) {
	const root = normalizedRoot(value)
	const allowedRoot = normalizedRoot(parent)
	return (
		!!root &&
		!!allowedRoot &&
		(allowedRoot === '/' ||
			root === allowedRoot ||
			root.startsWith(`${allowedRoot}/`))
	)
}

function requestClose() {
	if (!saving.value) emit('close')
}

async function load() {
	const request = requests.begin()
	draft.value = null
	originalDefaults.value = []
	error.value = ''
	if (!allowed.value) {
		loading.value = false
		error.value = '仅工作空间管理员可以设置本空间已入会成员的权限。'
		return
	}
	loading.value = true
	const target = props.member.id
	try {
		const result = await getWorkspaceMemberPermissionsReq(target, {
			signal: request.signal,
			timeout: 15000,
			showDefaultMsg: false,
		})
		if (disposed || !request.isCurrent()) return
		if (
			result.data?.userId !== target ||
			!Array.isArray(result.data.defaultPermissions) ||
			!Array.isArray(result.data.allowedDefaultPermissions) ||
			!Array.isArray(result.data.storages) ||
			!Array.isArray(result.data.permissionOptions) ||
			!result.data.storages.every(
				(row) =>
					Number.isInteger(row?.storageSourceId) &&
					normalizedRoot(row.allowedRootPath) &&
					Array.isArray(row.permissions) &&
					Array.isArray(row.allowedPermissions)
			)
		)
			throw new Error('Invalid member permissions')
		draft.value = {
			...result.data,
			defaultPermissions: [...result.data.defaultPermissions],
			storages: result.data.storages.map((row) => ({
				...row,
				quotaGB: (row.quotaBytes ?? 5368709120) / 1073741824,
				permissions: [...row.permissions],
			})),
		}
		originalDefaults.value = [...result.data.defaultPermissions]
	} catch (failure) {
		if (!disposed && request.isCurrent())
			error.value = failure?.response?.data?.msg || '权限读取失败，请重试。'
	} finally {
		if (!disposed && request.isCurrent()) loading.value = false
	}
}

function narrowToAllowed() {
	if (!draft.value || saving.value) return
	draft.value.defaultPermissions = draft.value.defaultPermissions.filter(
		(permission) => draft.value.allowedDefaultPermissions.includes(permission)
	)
	for (const row of draft.value.storages) {
		row.permissions = row.permissions.filter((permission) =>
			row.allowedPermissions.includes(permission)
		)
		if (!withinRoot(row.rootPath, row.allowedRootPath))
			row.rootPath = row.allowedRootPath
	}
}

async function save() {
	if (!canSave.value) return
	const target = props.member.id
	const request = requests.begin()
	saving.value = true
	error.value = ''
	const payload = {
		defaultPermissions: [...draft.value.defaultPermissions],
		storages: draft.value.storages.map((row) => ({
			storageSourceId: row.storageSourceId,
			rootPath: row.rootPath,
			enable: row.enable,
			permissions: [...row.permissions],
			quotaBytes: Math.floor(row.quotaGB * 1073741824),
		})),
	}
	try {
		await saveWorkspaceMemberPermissionsReq(target, payload, {
			signal: request.signal,
			timeout: 15000,
			showDefaultMsg: false,
		})
		if (!disposed && request.isCurrent()) emit('saved', target)
	} catch (failure) {
		if (!disposed && request.isCurrent())
			error.value = failure?.response?.data?.msg
				? `权限未保存：${failure.response.data.msg}。请重新读取并核对可授范围。`
				: '保存结果未确认，请重新读取后核对，勿重复提交。'
	} finally {
		if (!disposed && request.isCurrent()) saving.value = false
	}
}

watch(
	[() => props.member.id, identity],
	() => {
		saving.value = false
		load()
	},
	{ immediate: true, flush: 'sync' }
)
watch(saving, (value) => emit('busy', value), {
	immediate: true,
	flush: 'sync',
})
onBeforeUnmount(() => {
	disposed = true
	requests.cancel()
	emit('busy', false)
})
</script>

<style scoped>
.member-permissions {
	display: flex;
	flex: 1;
	flex-direction: column;
	height: 100%;
	min-height: 0;
	min-width: 0;
	color: var(--cloud-ink, #242428);
	background: var(--cloud-surface, white);
}
.permission-person,
.permission-person-copy,
.permission-section-heading,
.permission-footer,
.permission-footer > div {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
}
.permission-person {
	flex-shrink: 0;
	padding: 18px 24px;
	border-bottom: 1px solid var(--cloud-line, #e3e3e7);
}
.permission-person-copy {
	min-width: 0;
	justify-content: flex-start;
}
.member-mark {
	display: grid;
	flex-shrink: 0;
	place-items: center;
	width: 38px;
	height: 38px;
	border-radius: 12px;
	color: var(--cloud-accent-ink, #5b3ec4);
	background: var(--cloud-lilac, #f0ebff);
	font-size: 16px;
	font-weight: 600;
}
h2 {
	margin: 0;
	font-size: 15px;
	font-weight: 650;
	overflow-wrap: anywhere;
}
.permission-person p {
	margin: 4px 0 0;
	overflow-wrap: anywhere;
}
.permission-person > button {
	flex-shrink: 0;
}
.permission-body {
	flex: 1;
	min-height: 0;
	overflow-y: auto;
	padding: 22px 24px;
	overscroll-behavior: contain;
}
p {
	margin: 8px 0 12px;
	font-size: 12px;
	line-height: 1.7;
	color: var(--cloud-muted, #696970);
}
.permission-section-heading h3 {
	margin: 0;
	font-size: 14px;
	font-weight: 600;
}
.permission-section-heading > span {
	color: var(--cloud-muted, #696970);
	font-size: 12px;
}
.permission-intro {
	margin-bottom: 18px;
}
.storage-permission {
	margin: 0 0 12px;
	border: 1px solid var(--cloud-line, #e3e3e7);
	border-radius: 12px;
	overflow: hidden;
}
summary {
	cursor: pointer;
	font-size: 13px;
	font-weight: 600;
	line-height: 1.5;
}
.storage-permission > summary {
	padding: 14px 16px;
	background: var(--cloud-soft, #f7f7f8);
	overflow-wrap: anywhere;
}
summary small {
	margin-left: 10px;
	font-size: 11px;
	font-weight: 400;
	color: var(--cloud-muted, #73737b);
}
summary .is-enabled {
	color: var(--cloud-accent-ink, #5b3ec4);
}
.storage-permission-body {
	padding: 16px;
}
label {
	display: flex;
	align-items: center;
	gap: 7px;
	font-size: 12px;
	line-height: 1.5;
	cursor: pointer;
}
.permission-options {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 8px;
	margin: 10px 0 0;
}
.permission-options > label {
	align-items: flex-start;
	padding: 9px 10px;
	border: 1px solid var(--cloud-line, #e3e3e7);
	border-radius: 8px;
}
.permission-options > label:has(input:checked) {
	border-color: var(--cloud-accent, #7855df);
	background: var(--cloud-lilac, #f0ebff);
}
input[type='checkbox'] {
	flex-shrink: 0;
	width: 14px;
	height: 14px;
	margin: 2px 0 0;
	accent-color: var(--cloud-accent, #7855df);
	cursor: pointer;
}
.permission-enabled {
	margin: 0 0 18px;
	font-size: 13px;
	font-weight: 500;
}
.permission-root {
	display: grid;
	gap: 8px;
	cursor: default;
}
.permission-root > span {
	color: var(--cloud-muted, #696970);
}
.permission-root input {
	width: 100%;
	min-width: 0;
	padding: 9px 10px;
	border: 1px solid var(--cloud-line, #dedede);
	border-radius: 8px;
	background: var(--cloud-surface, #fff);
	color: inherit;
	font: inherit;
}
.permission-range {
	overflow-wrap: anywhere;
	margin-bottom: 18px;
}
fieldset {
	margin: 0;
	padding: 0;
	border: 0;
	min-width: 0;
}
legend {
	font-size: 12px;
	color: var(--cloud-muted, #696970);
}
.permission-advanced {
	margin-top: 22px;
	padding-top: 18px;
	border-top: 1px solid var(--cloud-line, #e3e3e7);
}
.advanced-content {
	padding-top: 8px;
}
.permission-empty {
	padding: 22px 18px;
	border: 1px dashed var(--cloud-line, #dedede);
	border-radius: 12px;
	background: var(--cloud-soft, #f7f7f8);
}
.permission-empty strong {
	font-size: 13px;
}
.permission-empty p:last-child {
	margin-bottom: 0;
}
.permission-error,
.permission-warning {
	margin: 0 0 18px;
	padding: 12px 14px;
	border-radius: 10px;
	font-size: 12px;
	line-height: 1.7;
}
.permission-error {
	color: #a43232;
	background: #fff1f0;
	border: 1px solid #f1d0ce;
}
.permission-warning {
	background: #fff9ee;
	border: 1px solid #ebdcc2;
}
.permission-warning strong {
	color: #8b6021;
	font-weight: 600;
}
.permission-warning p {
	margin: 4px 0 10px;
}
.permission-loading {
	padding: 24px 0;
}
.permission-footer {
	flex-shrink: 0;
	padding: 16px 24px;
	border-top: 1px solid var(--cloud-line, #e3e3e7);
	background: var(--cloud-surface, #fff);
}
.permission-footer > small {
	max-width: 44%;
	color: var(--cloud-muted, #696970);
	font-size: 11px;
	line-height: 1.6;
}
.permission-footer > div {
	gap: 8px;
	flex-shrink: 0;
}
button:focus-visible,
summary:focus-visible,
input:focus-visible {
	outline: 2px solid var(--cloud-accent, #7855df);
	outline-offset: 3px;
}
button {
	padding: 8px 12px;
	font: inherit;
	font-size: 12px;
	line-height: 1.5;
	color: var(--cloud-ink, #242428);
	background: var(--cloud-surface, #fff);
	border: 1px solid var(--cloud-line, #dedede);
	border-radius: 8px;
	cursor: pointer;
}
button:not(:disabled):hover {
	border-color: var(--cloud-muted, #73737b);
}
button:disabled {
	opacity: 0.5;
	cursor: not-allowed;
}
.primary {
	color: white;
	background: var(--cloud-primary, #262629);
	border-color: var(--cloud-primary, #262629);
}
@media (max-width: 480px) {
	.permission-person,
	.permission-body {
		padding: 16px;
	}
	.permission-footer {
		align-items: stretch;
		flex-direction: column;
		padding: 14px 16px;
		gap: 10px;
	}
	.permission-footer > small {
		max-width: none;
	}
	.permission-footer > div {
		justify-content: flex-end;
	}
	.permission-options {
		grid-template-columns: 1fr;
	}
}
</style>
