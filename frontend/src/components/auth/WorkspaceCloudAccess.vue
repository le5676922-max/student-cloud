<template>
	<section class="cloud-access" aria-label="云盘使用申请">
		<header>
			<div>
				<h2>{{ manager ? '云盘使用申请' : '申请使用云盘' }}</h2>
				<p>
					{{
						manager
							? '审批成员的个人目录与容量。目录不会向其他成员开放。'
							: '向本空间管理员申请个人云盘，课程共享资料在课程页面查看。'
					}}
				</p>
			</div>
			<button :disabled="busy || loading" @click="load">刷新</button>
		</header>
		<p v-if="error" role="alert">{{ error }}</p>
		<p v-if="notice" role="status">{{ notice }}</p>
		<p v-if="loading">正在读取…</p>
		<p v-else-if="!rows.length">
			{{ manager ? '暂无待审批申请' : '本空间暂无可申请的云盘' }}
		</p>
		<article v-for="row in rows" :key="row.id">
			<div>
				<strong>{{ manager ? row.storageName : row.name }}</strong>
				<p v-if="manager">
					申请人：{{ row.displayName || '空间成员' }} ·
					{{ row.reason || '未填写说明' }}
				</p>
				<p v-else>
					{{ statusText(row)
					}}<span v-if="row.granted && row.quotaBytes != null">
						· 容量 {{ (row.quotaBytes / 1073741824).toFixed(2) }} GB</span
					>
				</p>
			</div>
			<template v-if="manager">
				<label
					>容量上限（GB）<input
						v-model.number="row.quotaGB"
						type="number"
						min="0"
						max="102400"
						step="0.01"
						:disabled="busy"
				/></label>
				<label
					><input
						v-model="row.allowUpload"
						type="checkbox"
						:disabled="busy"
					/>允许上传</label
				>
				<label
					>审核说明<input v-model="row.note" maxlength="500" :disabled="busy"
				/></label>
				<div class="actions">
					<button :disabled="busy" @click="review(row, false)">拒绝</button
					><button
						class="primary"
						:disabled="busy || !validQuota(row)"
						@click="review(row, true)"
					>
						同意并开通
					</button>
				</div>
			</template>
			<template v-else-if="!row.granted && row.status !== 'PENDING'">
				<label
					>申请说明<input v-model="row.reason" maxlength="500" :disabled="busy"
				/></label>
				<button class="primary" :disabled="busy" @click="apply(row)">
					{{ row.status === 'REJECTED' ? '重新申请' : '申请使用' }}
				</button>
			</template>
		</article>
		<router-link v-if="!manager" to="/">返回我的文件</router-link>
	</section>
</template>
<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import useStorageConfigStore from '~/stores/storage-config'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import {
	getMemberCloudsReq,
	applyMemberCloudReq,
	getCloudApplicationsReq,
	reviewCloudApplicationReq,
} from '~/api/home/workspace-cloud'
const store = useStorageConfigStore()
const manager = computed(() => store.loginInfo?.role === 'TENANT_ADMIN')
const identity = computed(
	() =>
		`${store.loginInfo?.isLogin}:${store.loginInfo?.role}:${store.loginInfo?.tenantId}:${store.loginInfo?.username}`
)
const rows = ref([]),
	loading = ref(false),
	busy = ref(false),
	error = ref(''),
	notice = ref('')
const requests = createLatestRequest()
let disposed = false,
	mutation
const validQuota = (row) =>
	Number.isFinite(row.quotaGB) && row.quotaGB >= 0 && row.quotaGB <= 102400
const statusText = (row) =>
	row.granted
		? '已开通个人目录'
		: {
				PENDING: '等待空间管理员审核',
				REJECTED: '申请未通过',
				APPROVED: '授权已关闭，可重新申请',
				NONE: '尚未申请',
		  }[row.status] || '状态待核对'
async function load() {
	const request = requests.begin()
	loading.value = true
	rows.value = []
	error.value = ''
	if (
		!store.loginInfo?.isLogin ||
		!['USER', 'TENANT_ADMIN'].includes(store.loginInfo?.role)
	) {
		loading.value = false
		error.value = '请使用空间成员或空间管理员账号'
		return
	}
	try {
		const result = await (manager.value
			? getCloudApplicationsReq
			: getMemberCloudsReq)({ signal: request.signal })
		if (!disposed && request.isCurrent())
			rows.value = result.data.map((row) => ({
				...row,
				quotaGB: 5,
				reason: '',
				note: '',
				allowUpload: false,
				...row,
			}))
	} catch (failure) {
		if (!disposed && request.isCurrent())
			error.value = failure?.response?.data?.msg || '申请信息加载失败，请重试'
	} finally {
		if (!disposed && request.isCurrent()) loading.value = false
	}
}
async function mutate(work, message) {
	if (busy.value) return
	const actor = identity.value,
		controller = new AbortController()
	mutation = controller
	busy.value = true
	error.value = ''
	notice.value = ''
	try {
		await work({ signal: controller.signal })
		if (disposed || actor !== identity.value) return
		notice.value = message
		await load()
	} catch (failure) {
		if (!disposed && actor === identity.value)
			error.value =
				failure?.response?.data?.msg || '操作结果未确认，请刷新核对后再操作'
	} finally {
		if (mutation === controller) busy.value = false
	}
}
const apply = (row) =>
	mutate(
		(config) => applyMemberCloudReq(row.id, row.reason || '', config),
		'已提交给空间管理员'
	)
const review = (row, approve) => {
	if (approve && !validQuota(row)) return
	return mutate(
		(config) =>
			reviewCloudApplicationReq(
				row.id,
				{
					approve,
					quotaBytes: Math.floor(row.quotaGB * 1073741824),
					permissions: row.allowUpload
						? ['preview', 'download', 'upload', 'newFolder']
						: ['preview', 'download'],
					note: row.note || '',
				},
				config
			),
		approve ? '已开通成员个人目录' : '已拒绝申请'
	)
}
watch(
	identity,
	() => {
		mutation?.abort()
		mutation = null
		busy.value = false
		notice.value = ''
		load()
	},
	{ immediate: true, flush: 'sync' }
)
onBeforeUnmount(() => {
	disposed = true
	requests.cancel()
	mutation?.abort()
	rows.value = []
})
</script>
<style scoped>
.cloud-access {
	color: var(--cloud-ink, #242428);
}
header {
	display: flex;
	justify-content: space-between;
	gap: 16px;
	align-items: center;
}
h2 {
	font-size: 18px;
	margin: 0 0 8px;
}
p {
	color: #6b6b76;
	font-size: 13px;
	line-height: 1.6;
}
article {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 12px;
	padding: 16px 0;
	border-top: 1px solid #e6e6eb;
}
article > div:first-child {
	flex: 1;
	min-width: 200px;
}
label {
	display: flex;
	gap: 6px;
	align-items: center;
	font-size: 12px;
}
input:not([type='checkbox']) {
	width: 150px;
	border: 1px solid #dcdce3;
	border-radius: 8px;
	padding: 8px;
}
input[type='number'] {
	width: 90px;
}
button {
	border: 1px solid #dcdce3;
	border-radius: 10px;
	padding: 9px 13px;
	background: white;
	cursor: pointer;
}
button.primary {
	background: #242428;
	color: white;
	border-color: #242428;
}
button:disabled {
	opacity: 0.5;
	cursor: default;
}
.actions {
	display: flex;
	gap: 8px;
}
[role='alert'] {
	color: #a13232;
}
[role='status'] {
	color: #34714c;
}
a {
	display: inline-block;
	margin-top: 15px;
}
@media (max-width: 640px) {
	article {
		align-items: flex-start;
		flex-direction: column;
	}
}
</style>
