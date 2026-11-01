<template>
	<section
		class="ownership-transfer"
		aria-label="管理交接"
		:aria-busy="loading || busy"
	>
		<header>
			<div>
				<h2>
					{{ resourceType === 'course' ? '课程创建者交接' : '空间管理员交接' }}
				</h2>
				<p>只交接公共管理职责，不转移前任私人文件、问答或 Memory。</p>
			</div>
			<ElButton v-if="enabled" :disabled="loading || busy" @click="load"
				>刷新交接</ElButton
			>
		</header>
		<p v-if="!enabled">管理交接未启用。</p>
		<p v-if="error" role="alert">{{ error }}</p>
		<p v-if="notice" role="status">{{ notice }}</p>
		<p v-if="loading" role="status">正在读取交接信息…</p>
		<template v-if="snapshot && enabled">
			<p>
				<strong>{{ snapshot.resourceName }}</strong> · 管理版本
				{{ snapshot.ownershipRevision }}
			</p>
			<form v-if="snapshot.canCreate" novalidate @submit.prevent="create">
				<label for="ownership-recipient"
					>接任者（仅本空间符合资格的账号）</label
				>
				<ElSelect
					id="ownership-recipient"
					v-model="draft.toAccountId"
					aria-label="接任者"
					:disabled="busy || loading"
					placeholder="核对账号后选择接任者"
				>
					<ElOption
						v-for="person in snapshot.candidates"
						:key="person.id"
						:value="person.id"
						:label="`${person.username} · ${person.accountId}`"
					/>
				</ElSelect>
				<p v-if="!snapshot.candidates.length">
					暂无符合资格的接任者。课程接任者须已加入课程且为本空间管理员；空间接任者须为本空间已启用成员。
				</p>
				<label for="ownership-expiry">等待确认有效期</label>
				<ElSelect
					id="ownership-expiry"
					v-model="draft.hours"
					aria-label="交接有效期"
					:disabled="busy || loading"
				>
					<ElOption
						v-for="hours in [1, 24, 72, 168]"
						:key="hours"
						:value="hours"
						:label="`${hours} 小时`"
					/>
				</ElSelect>
				<ElCheckbox v-model="draft.confirmed" :disabled="busy || loading"
					>我已核对接任账号；完成后前任管理能力将被撤销，私人资料不会转移。</ElCheckbox
				>
				<ElButton
					native-type="submit"
					type="primary"
					:loading="busy"
					:disabled="loading || busy || !draft.toAccountId || !draft.confirmed"
					>发起交接</ElButton
				>
			</form>
			<p v-if="!snapshot.transfers.length">暂无与本人有关的交接。</p>
			<article v-for="row in snapshot.transfers" :key="row.id">
				<p>
					<strong>{{ stateText(row.state) }}</strong> · 账号
					{{ row.fromAccountId }} → {{ row.toAccountId }}
				</p>
				<p>有效至 {{ dateText(row.expiresAt) }}（北京时间）</p>
				<div v-if="row.state === 'PENDING'" class="actions">
					<template v-if="row.toAccountId === snapshot.actorId">
						<ElButton :disabled="busy || loading" @click="decide(row, 'reject')"
							>拒绝交接</ElButton
						>
						<ElButton
							type="primary"
							:disabled="busy || loading"
							@click="decide(row, 'accept')"
							>确认接任</ElButton
						>
					</template>
					<ElButton
						v-if="row.fromAccountId === snapshot.actorId"
						:disabled="busy || loading"
						@click="decide(row, 'cancel')"
						>撤回交接</ElButton
					>
				</div>
			</article>
			<p class="scope-note">
				最多显示最近 50 条本人交接。候选账号最多 100
				个；未列出不代表有接任资格。跨空间交接不开放。
			</p>
		</template>
	</section>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
	ElSelect,
	ElOption,
	ElButton,
	ElCheckbox,
	ElMessageBox,
} from 'element-plus'
import useStorageConfigStore from '~/stores/storage-config'
import {
	getOwnershipTransfersReq,
	createOwnershipTransferReq,
	decideOwnershipTransferReq,
} from '~/api/home/ownership-transfer'

const props = defineProps({
	resourceType: { type: String, default: 'workspace' },
	resourceId: { type: [Number, String], default: null },
	enabled: { type: Boolean, default: false },
})
const emit = defineEmits(['completed'])
const store = useStorageConfigStore()
const identity = computed(
	() =>
		`${store.loginInfo?.isLogin}:${store.loginInfo?.username}:${store.loginInfo?.tenantId}:${store.loginInfo?.role}:${props.resourceType}:${props.resourceId}:${props.enabled}`
)
const snapshot = ref(null),
	loading = ref(false),
	busy = ref(false),
	error = ref(''),
	notice = ref('')
const draft = reactive({ toAccountId: null, hours: 24, confirmed: false })
let epoch = 0,
	readController,
	writeController,
	frozenRequest,
	disposed = false
const stateText = (state) =>
	({
		PENDING: '等待接任者确认',
		ACCEPTED: '已确认，正在完成',
		COMPLETED: '交接已完成',
		REJECTED: '已拒绝',
		CANCELLED: '已撤回',
		EXPIRED: '已过期',
	}[state] || '状态待核对')
const dateText = (value) =>
	new Intl.DateTimeFormat('zh-CN', {
		timeZone: 'Asia/Shanghai',
		year: 'numeric',
		month: '2-digit',
		day: '2-digit',
		hour: '2-digit',
		minute: '2-digit',
	}).format(value)
async function load() {
	if (disposed) return
	readController?.abort()
	const token = ++epoch,
		actor = identity.value,
		controller = new AbortController()
	readController = controller
	if (!props.enabled || !store.loginInfo?.isLogin) {
		snapshot.value = null
		loading.value = false
		return
	}
	loading.value = true
	error.value = ''
	try {
		const response = await getOwnershipTransfersReq(
			props.resourceType,
			props.resourceId,
			{ signal: controller.signal }
		)
		if (!disposed && token === epoch && actor === identity.value)
			snapshot.value = response.data
	} catch (failure) {
		if (!disposed && token === epoch && actor === identity.value)
			error.value =
				failure?.response?.data?.msg ||
				'交接信息读取失败，请刷新；不会自动发起或确认交接。'
	} finally {
		if (!disposed && token === epoch && actor === identity.value)
			loading.value = false
	}
}
async function mutate(work) {
	if (busy.value || loading.value || !props.enabled) return
	const actor = identity.value,
		controller = new AbortController()
	writeController = controller
	busy.value = true
	error.value = ''
	notice.value = ''
	try {
		const response = await work(controller.signal)
		if (disposed || actor !== identity.value) return
		notice.value = stateText(response.data.state)
		frozenRequest = undefined
		if (response.data.state === 'COMPLETED') emit('completed', response.data)
		await load()
	} catch (failure) {
		if (!disposed && actor === identity.value)
			error.value =
				failure?.response?.data?.msg ||
				'交接操作结果未确认，请先刷新核对；不会自动重试。'
	} finally {
		if (writeController === controller) busy.value = false
	}
}
async function create() {
	if (
		busy.value ||
		loading.value ||
		!snapshot.value?.canCreate ||
		!draft.confirmed ||
		!snapshot.value.candidates.some((person) => person.id === draft.toAccountId)
	)
		return
	const actor = identity.value,
		recipient = snapshot.value.candidates.find(
			(person) => person.id === draft.toAccountId
		),
		hours = draft.hours,
		revision = snapshot.value.ownershipRevision
	try {
		await ElMessageBox.confirm(
			`将「${snapshot.value.resourceName}」交给「${recipient.username} · ${recipient.accountId}」，有效期 ${hours} 小时。接任者须亲自确认；完成后前任管理能力撤销，私人资料不转移。`,
			'核对交接范围',
			{
				confirmButtonText: '发起交接',
				cancelButtonText: '返回核对',
				type: 'warning',
				autofocus: false,
			}
		)
	} catch {
		return
	}
	if (actor !== identity.value || disposed) return
	if (
		draft.toAccountId !== recipient.id ||
		draft.hours !== hours ||
		snapshot.value?.ownershipRevision !== revision
	) {
		error.value = '交接信息已改变，请重新核对接任者、有效期和管理版本。'
		return
	}
	const signature = `${draft.toAccountId}:${draft.hours}:${snapshot.value.ownershipRevision}`
	if (frozenRequest?.signature !== signature)
		frozenRequest = {
			signature,
			body: {
				toAccountId: draft.toAccountId,
				expectedRevision: snapshot.value.ownershipRevision,
				expiresAt: Date.now() + draft.hours * 3600000,
				idempotencyKey: crypto.randomUUID(),
			},
		}
	const body = { ...frozenRequest.body }
	await mutate((signal) =>
		createOwnershipTransferReq(props.resourceType, props.resourceId, body, {
			signal,
		})
	)
}
async function decide(row, action) {
	if (!snapshot.value || row.state !== 'PENDING' || busy.value || loading.value)
		return
	if (
		snapshot.value.actorId !==
		(action === 'cancel' ? row.fromAccountId : row.toAccountId)
	)
		return
	const actor = identity.value,
		verb = { accept: '确认接任', reject: '拒绝交接', cancel: '撤回交接' }[
			action
		]
	if (!verb) return
	try {
		await ElMessageBox.confirm(
			`对「${snapshot.value.resourceName}」${verb}？确认接任后前任不再具有管理能力，私人资料不转移。`,
			'核对交接',
			{
				confirmButtonText: verb,
				cancelButtonText: '返回核对',
				type: 'warning',
				autofocus: false,
			}
		)
	} catch {
		return
	}
	if (actor !== identity.value || disposed) return
	await mutate((signal) =>
		decideOwnershipTransferReq(
			props.resourceType,
			props.resourceId,
			row.id,
			action,
			{ signal }
		)
	)
}
watch(
	identity,
	() => {
		readController?.abort()
		writeController?.abort()
		epoch++
		busy.value = false
		snapshot.value = null
		frozenRequest = undefined
		Object.assign(draft, { toAccountId: null, hours: 24, confirmed: false })
		error.value = ''
		notice.value = ''
		load()
	},
	{ immediate: true }
)
onBeforeUnmount(() => {
	disposed = true
	epoch++
	readController?.abort()
	writeController?.abort()
})
defineExpose({ load, create, decide, draft, snapshot, busy })
</script>

<style scoped>
.ownership-transfer {
	color: var(--cloud-ink);
	background: var(--cloud-surface);
	border: 1px solid var(--cloud-line);
	border-radius: var(--cloud-radius);
	padding: 20px;
}
header {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 12px;
}
h2 {
	font-size: 18px;
	margin: 0;
}
p {
	font-size: 14px;
	line-height: 1.6;
	overflow-wrap: anywhere;
}
form {
	display: grid;
	gap: 12px;
	max-width: 768px;
	margin: 20px 0;
}
form > .el-button {
	justify-self: start;
	min-width: 112px;
}
article {
	border-top: 1px solid var(--cloud-line);
	padding: 12px 0;
}
.actions {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
}
.scope-note {
	color: var(--cloud-muted);
}
:deep(.el-checkbox) {
	white-space: normal;
	height: auto;
	align-items: flex-start;
}
:deep(.el-checkbox__label) {
	white-space: normal;
	line-height: 1.6;
}
:deep(button) {
	cursor: pointer;
}
:deep(button:disabled) {
	cursor: not-allowed;
}
:deep(button:focus-visible) {
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
@media (max-width: 600px) {
	header {
		flex-direction: column;
	}
	.ownership-transfer {
		padding: 16px;
	}
}
</style>
