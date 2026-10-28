<template>
	<main class="recycle-page">
		<header>
			<div>
				<p>文件管理</p>
				<h1>回收站</h1>
				<p>删除内容保留7天，仍占用容量。恢复不会覆盖同名文件。</p>
			</div>
			<router-link to="/">返回云盘</router-link>
		</header>
		<el-select
			v-model="selected"
			placeholder="选择云盘"
			:disabled="busy"
			@change="load"
			><el-option
				v-for="drive in drives"
				:key="drive.key"
				:label="drive.name"
				:value="drive.key"
		/></el-select>
		<el-button :loading="loading" :disabled="busy" @click="refresh"
			>刷新</el-button
		>
		<el-alert v-if="error" :title="error" type="error" :closable="false" />
		<el-table
			v-loading="loading"
			:data="rows"
			:empty-text="error ? '回收站状态尚未确认' : '回收站为空'"
		>
			<el-table-column prop="name" label="文件名" /><el-table-column
				prop="originalPath"
				label="原位置"
			/>
			<el-table-column label="大小"
				><template #default="{ row }"
					>{{ row.size }} B</template
				></el-table-column
			>
			<el-table-column label="到期时间"
				><template #default="{ row }">{{
					new Date(row.expiresAt).toLocaleString()
				}}</template></el-table-column
			>
			<el-table-column label="操作" width="210"
				><template #default="{ row }"
					><template v-if="row.status === 'TRASHED'"
						><el-button :disabled="busy" @click="act(row, false)"
							>恢复</el-button
						><el-button
							type="danger"
							text
							:disabled="busy"
							@click="act(row, true)"
							>彻底删除</el-button
						></template
					><span v-else>结果需要管理员核查</span></template
				></el-table-column
			>
		</el-table>
	</main>
</template>
<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useStorage } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '~/http/request'
import useStorageConfigStore from '~/stores/storage-config'
const store = useStorageConfigStore(),
	drives = ref([]),
	selected = ref(''),
	rows = ref([]),
	loading = ref(false),
	busy = ref(false),
	error = ref('')
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
let generation = 0,
	controller = null,
	storageController = null,
	activeOperation = null,
	disposed = false
const url = () =>
	'/api/drives/' + encodeURIComponent(selected.value) + '/recycle-bin'
const authenticated = () =>
	store.loginInfo?.isLogin === true &&
	['ADMIN', 'TENANT_ADMIN', 'USER'].includes(store.loginInfo?.role)
const failureMessage = (failure, fallback) =>
	failure?.response?.data?.msg ||
	failure?.data?.msg ||
	failure?.message ||
	fallback
const current = (ticket) => !disposed && ticket === generation
function cancel() {
	generation++
	controller?.abort()
	storageController?.abort()
	rows.value = []
	loading.value = false
}
function invalidate() {
	cancel()
	activeOperation?.controller?.abort()
	activeOperation = null
	busy.value = false
	selected.value = ''
	drives.value = []
	error.value = ''
}
async function load() {
	cancel()
	if (!selected.value || disposed || !authenticated()) return
	const ticket = generation,
		request = new AbortController()
	controller = request
	loading.value = true
	error.value = ''
	try {
		const response = await http({
			url: url(),
			method: 'get',
			config: { signal: request.signal, timeout: 15000, showDefaultMsg: false },
		})
		if (current(ticket) && !request.signal.aborted) {
			if (!Array.isArray(response.data))
				throw new Error('回收站响应无效，请刷新重试')
			rows.value = response.data
		}
	} catch (failure) {
		if (current(ticket) && !request.signal.aborted)
			error.value = failureMessage(failure, '回收站读取失败，请刷新重试')
	} finally {
		if (current(ticket)) loading.value = false
	}
}
async function loadDrives() {
	cancel()
	drives.value = []
	selected.value = ''
	error.value = ''
	if (disposed || !authenticated()) return
	const ticket = generation,
		request = new AbortController()
	storageController = request
	loading.value = true
	try {
		const response = await http({
			url: '/api/storage/list',
			method: 'get',
			config: { signal: request.signal, timeout: 15000, showDefaultMsg: false },
		})
		if (current(ticket) && !request.signal.aborted) {
			if (!Array.isArray(response.data))
				throw new Error('云盘响应无效，请刷新重试')
			drives.value = response.data
			selected.value = drives.value[0]?.key || ''
			if (selected.value) await load()
		}
	} catch (failure) {
		if (current(ticket) && !request.signal.aborted)
			error.value = failureMessage(failure, '云盘读取失败，请刷新重试')
	} finally {
		if (current(ticket)) loading.value = false
	}
}
async function refresh() {
	if (busy.value) return
	return selected.value ? load() : loadDrives()
}
async function act(row, purge) {
	if (
		busy.value ||
		disposed ||
		!authenticated() ||
		!selected.value ||
		row?.status !== 'TRASHED' ||
		!rows.value.some((item) => item.id === row.id) ||
		!/^[a-f0-9]{32}$/.test(row.id)
	)
		return
	const ticket = generation,
		base = url(),
		operation = { controller: null }
	activeOperation = operation
	busy.value = true
	error.value = ''
	try {
		if (purge) {
			try {
				await ElMessageBox.confirm(
					'彻底删除后无法恢复，是否继续？',
					'彻底删除',
					{ type: 'warning' }
				)
			} catch {
				return
			}
		}
		if (!current(ticket) || activeOperation !== operation) return
		operation.controller = new AbortController()
		await http({
			url: base + '/' + row.id + (purge ? '?confirmed=true' : '/restore'),
			method: purge ? 'delete' : 'post',
			config: {
				signal: operation.controller.signal,
				timeout: 30000,
				showDefaultMsg: false,
			},
		})
		if (current(ticket) && activeOperation === operation) {
			ElMessage.success(purge ? '已彻底删除' : '已恢复')
			await load()
		}
	} catch (failure) {
		if (
			current(ticket) &&
			activeOperation === operation &&
			!operation.controller?.signal.aborted
		)
			error.value = failureMessage(
				failure,
				'操作结果未确认，请刷新回收站核对，勿重复提交'
			)
	} finally {
		if (activeOperation === operation) {
			activeOperation = null
			busy.value = false
		}
	}
}
watch(
	() => [
		store.loginInfo?.id,
		store.loginInfo?.accountId,
		store.loginInfo?.username,
		store.loginInfo?.isLogin,
		store.loginInfo?.tenantId,
		store.loginInfo?.role,
		credential.value,
	],
	invalidate,
	{ flush: 'sync' }
)
onMounted(loadDrives)
onBeforeUnmount(() => {
	disposed = true
	invalidate()
})
</script>
<style scoped>
.recycle-page {
	max-width: 1200px;
	margin: 32px auto;
	padding: 28px;
	background: var(--el-bg-color, #fff);
	border: 1px solid var(--el-border-color, #ddd);
	border-radius: 18px;
	color: var(--el-text-color-primary, #222);
}
header {
	display: flex;
	justify-content: space-between;
	align-items: center;
	margin-bottom: 24px;
}
h1 {
	font-size: 28px;
	margin: 6px 0;
}
p {
	color: var(--el-text-color-secondary, #666);
}
.el-select {
	width: 240px;
	margin: 0 12px 20px 0;
}
.el-alert {
	margin-bottom: 16px;
}
@media (max-width: 650px) {
	.recycle-page {
		margin: 12px;
		padding: 16px;
	}
	header {
		align-items: flex-start;
		gap: 12px;
	}
}
</style>
