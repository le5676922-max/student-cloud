<template>
	<details class="local-storage-requests" @toggle="onToggle">
		<summary>
			{{ isAdmin ? '服务器本地云盘申请审批' : '申请服务器本地云盘' }}
		</summary>
		<p>
			第三方云盘可以直接添加；服务器本地云盘由全局管理员批准，目录由系统分配。
		</p>
		<form v-if="!isAdmin" novalidate @submit.prevent="submit">
			<label
				>申请用途<input
					v-model="reason"
					maxlength="500"
					placeholder="例如课程共享资料"
					:disabled="busy"
			/></label>
			<button type="submit" :disabled="busy || !reason.trim()">提交申请</button>
		</form>
		<p v-if="error" role="alert">{{ error }}</p>
		<p v-if="busy" role="status">正在处理…</p>
		<button type="button" :disabled="busy" @click="load">刷新申请</button>
		<p v-if="loaded && !rows.length">暂无申请。</p>
		<ul>
			<li v-for="row in rows" :key="row.id">
				<strong>{{ row.tenantName }}</strong
				><span>{{ statusLabel(row.status) }}</span>
				<p>{{ row.reason }}</p>
				<p v-if="row.reviewNote">审核说明：{{ row.reviewNote }}</p>
				<template v-if="isAdmin && row.status === 'PENDING'">
					<label
						>审核说明<input
							v-model="notes[row.id]"
							maxlength="500"
							:disabled="busy"
					/></label>
					<button type="button" :disabled="busy" @click="review(row, true)">
						批准并开通
					</button>
					<button type="button" :disabled="busy" @click="review(row, false)">
						拒绝
					</button>
				</template>
			</li>
		</ul>
		<small v-if="rows.length === 200">仅显示最近 200 条申请。</small>
	</details>
</template>
<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import useStorageConfigStore from '~/stores/storage-config'
import {
	loadLocalStorageRequestsReq,
	submitLocalStorageRequestReq,
	reviewLocalStorageRequestReq,
} from '~/api/admin/local-storage-approval'
const emit = defineEmits(['changed'])
const store = useStorageConfigStore()
const isAdmin = computed(() => store.loginInfo.role === 'ADMIN')
const rows = ref([]),
	reason = ref(''),
	notes = ref({}),
	busy = ref(false),
	error = ref(''),
	loaded = ref(false)
let generation = 0,
	controller,
	disposed = false,
	opened = false
function reset() {
	generation++
	controller?.abort()
	rows.value = []
	notes.value = {}
	reason.value = ''
	busy.value = false
	loaded.value = false
	error.value = ''
}
function statusLabel(value) {
	return (
		{ PENDING: '待审核', APPROVED: '已批准', REJECTED: '已拒绝' }[value] ||
		value
	)
}
async function run(action, changed = false) {
	if (busy.value || disposed) return
	const current = ++generation
	controller = new AbortController()
	busy.value = true
	error.value = ''
	try {
		await action(controller.signal)
		if (disposed || generation !== current) return
		if (changed) {
			reason.value = ''
			emit('changed')
		}
		const result = await loadLocalStorageRequestsReq(isAdmin.value, {
			signal: controller.signal,
		})
		if (disposed || generation !== current) return
		rows.value = result.data || []
		loaded.value = true
	} catch (failure) {
		if (!disposed && current === generation && !controller.signal.aborted)
			error.value =
				failure?.response?.data?.msg ||
				'处理结果尚未确认，请刷新申请状态后再操作。'
	} finally {
		if (!disposed && generation === current) busy.value = false
	}
}
const load = () => run(async () => {})
const submit = () =>
	reason.value.trim() &&
	run(
		(signal) => submitLocalStorageRequestReq(reason.value.trim(), { signal }),
		true
	)
const review = (row, approve) =>
	run(
		(signal) =>
			reviewLocalStorageRequestReq(row.id, approve, notes.value[row.id] || '', {
				signal,
			}),
		true
	)
function onToggle(event) {
	opened = event.target.open
	if (opened && !loaded.value) load()
}
watch(
	() => [
		store.loginInfo.username,
		store.loginInfo.role,
		store.loginInfo.tenantId,
	],
	() => {
		reset()
		if (opened) load()
	}
)
onBeforeUnmount(() => {
	disposed = true
	reset()
})
</script>
<style scoped>
.local-storage-requests {
	padding: 16px;
	border: 1px solid #dedee2;
	border-radius: 14px;
	margin: 16px 0;
	background: #fff;
}
summary {
	cursor: pointer;
	font-weight: 600;
}
p {
	margin: 10px 0;
	color: #59606d;
}
form,
li {
	padding: 12px 0;
}
li + li {
	border-top: 1px solid #ececf0;
}
input {
	padding: 8px;
	margin: 6px;
	border: 1px solid #d4d4da;
	border-radius: 8px;
	max-width: 100%;
}
button {
	padding: 7px 12px;
	margin: 4px;
	border: 1px solid #d4d4da;
	border-radius: 8px;
}
span {
	margin-left: 12px;
}
[role='alert'] {
	color: #b42318;
}
button:disabled {
	opacity: 0.5;
}
</style>
