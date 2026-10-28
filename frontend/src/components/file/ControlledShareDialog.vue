<template>
	<ZDialog
		v-model="visible"
		title="受控文件分享"
		:close-on-click-modal="false"
		:show-footer="false"
		hidden-full-btn
		@close="close"
	>
		<div class="controlled-share-dialog" :aria-busy="busy">
			<p v-if="error" role="alert" class="share-error">{{ error }}</p>
			<p v-if="fileName" class="share-file">{{ fileName }}</p>
			<template v-if="!created">
				<el-form label-position="top">
					<el-form-item label="访问范围">
						<el-select
							v-model="audience"
							:disabled="busy"
							@change="consent = false"
						>
							<el-option label="本工作空间已登录成员" value="OWN_WORKSPACE" />
							<el-option label="指定课程当前成员" value="COURSE" />
						</el-select>
					</el-form-item>
					<el-form-item v-if="audience === 'COURSE'" label="课程">
						<el-select
							v-model="courseId"
							placeholder="请选择已加入的课程"
							:disabled="busy || coursesLoading"
							@change="consent = false"
						>
							<el-option
								v-for="course in courses"
								:key="course.id"
								:label="course.name"
								:value="course.id"
							/>
						</el-select>
						<p v-if="!coursesLoading && !courses.length" class="share-hint">
							尚无可用课程，请先加入课程。
						</p>
					</el-form-item>
					<el-form-item label="有效期">
						<el-select v-model="days" :disabled="busy"
							><el-option label="1天" :value="1" /><el-option
								label="7天（默认）"
								:value="7" /><el-option label="30天" :value="30"
						/></el-select>
					</el-form-item>
					<el-checkbox v-model="consent" :disabled="busy">{{
						audience === 'OWN_WORKSPACE'
							? '仅本空间已登录成员可访问'
							: '仅所选课程当前已登录成员可访问'
					}}</el-checkbox>
				</el-form>
				<p class="share-hint">
					接收者必须登录。源文件变化、移走、权限收回或分享到期后，链接失效。当前支持单个文件，最多50MiB。
				</p>
				<el-button
					type="primary"
					:disabled="!consent || busy || !file"
					:loading="busy"
					@click="create"
					>创建分享</el-button
				>
			</template>
			<template v-else>
				<p>分享已创建，有效期至 {{ dateTime(created.expiresAt) }}。</p>
				<label for="controlled-share-url">受控分享链接</label>
				<el-input
					id="controlled-share-url"
					:model-value="shareUrl(created.id)"
					readonly
				/>
				<div class="share-actions">
					<el-button @click="copy(created.id)">复制链接</el-button
					><router-link :to="controlledSharePath(created.id)"
						>打开分享</router-link
					><el-button
						:disabled="busy || !!created.revokedAt"
						@click="revoke(created)"
						>{{ created.revokedAt ? '已撤销' : '撤销分享' }}</el-button
					>
				</div>
			</template>
			<details @toggle="loadMine">
				<summary>我的分享</summary>
				<p v-if="mineLoading" role="status">正在读取分享…</p>
				<p v-else-if="!mine.length" class="share-hint">暂无分享。</p>
				<ul v-else class="share-list">
					<li v-for="share in mine" :key="share.id">
						<span
							>{{ share.name
							}}<small>{{
								share.revokedAt
									? '已撤销'
									: share.expiresAt <= Date.now()
									? '已过期'
									: dateTime(share.expiresAt) + ' 到期'
							}}</small></span
						><el-button
							:disabled="
								busy || !!share.revokedAt || share.expiresAt <= Date.now()
							"
							@click="revoke(share)"
							>撤销</el-button
						>
					</li>
				</ul>
			</details>
		</div>
	</ZDialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useStorage } from '@vueuse/core'
import { ElMessage } from 'element-plus'
import ZDialog from '~/components/common/dialog/ZDialog.vue'
import useStorageConfigStore from '~/stores/storage-config'
import { courseRequest } from '~/api/home/courses'
import { controlledSharesRequest } from '~/api/home/controlled-shares'
import {
	controlledShareInput,
	controlledSharePath,
} from '~/utils/controlled-sharing.mjs'

const store = useStorageConfigStore(),
	route = useRoute()
const credential = useStorage('tongligong-student-cloud-token', '', undefined, {
	writeDefaults: false,
})
const visible = ref(false),
	busy = ref(false),
	error = ref(''),
	file = ref(null),
	fileName = ref('')
const audience = ref('OWN_WORKSPACE'),
	courseId = ref(null),
	days = ref(7),
	consent = ref(false)
const created = ref(null),
	courses = ref([]),
	coursesLoading = ref(false),
	mine = ref([]),
	mineLoading = ref(false)
const identity = computed(() =>
	JSON.stringify([
		store.loginInfo?.id,
		store.loginInfo?.accountId,
		store.loginInfo?.username,
		store.loginInfo?.tenantId,
		store.loginInfo?.role,
		store.loginInfo?.isLogin,
		credential.value,
	])
)
let generation = 0
const message = (failure) =>
	failure?.response?.data?.msg ||
	failure?.data?.msg ||
	failure?.message ||
	'分享操作失败，请稍后重试'
const dateTime = (value) => new Date(value).toLocaleString('zh-CN')
const shareUrl = (id) => window.location.origin + controlledSharePath(id)
function close() {
	generation++
	visible.value = false
	busy.value = false
	created.value = null
	file.value = null
	mine.value = []
	error.value = ''
	courses.value = []
	coursesLoading.value = false
	mineLoading.value = false
}
watch([identity, () => route.fullPath], close)
async function openDialog(snapshot, name) {
	close()
	file.value = snapshot
	fileName.value = name
	audience.value = 'OWN_WORKSPACE'
	courseId.value = null
	days.value = 7
	consent.value = false
	visible.value = true
	const current = generation
	coursesLoading.value = true
	try {
		const response = await courseRequest()
		if (current === generation)
			courses.value = (response.data || []).filter((course) => course.enabled)
	} catch {
		if (current === generation) courses.value = []
	} finally {
		if (current === generation) coursesLoading.value = false
	}
}
async function create() {
	if (busy.value) return
	let input
	try {
		input = controlledShareInput(file.value, {
			audience: audience.value,
			courseId: courseId.value,
			days: days.value,
			consent: consent.value,
		})
	} catch (failure) {
		error.value = message(failure)
		return
	}
	const current = generation
	busy.value = true
	error.value = ''
	try {
		const response = await controlledSharesRequest('', 'post', input)
		if (current === generation) created.value = response.data
	} catch (failure) {
		if (current === generation) error.value = message(failure)
	} finally {
		if (current === generation) busy.value = false
	}
}
async function revoke(share) {
	if (busy.value) return
	const current = generation
	busy.value = true
	error.value = ''
	try {
		await controlledSharesRequest(`/${share.id}`, 'delete', {
			version: share.version,
		})
		if (current === generation) {
			share.revokedAt = Date.now()
			share.version++
			ElMessage.success('分享已撤销，源文件保留')
		}
	} catch (failure) {
		if (current === generation) error.value = message(failure)
	} finally {
		if (current === generation) busy.value = false
	}
}
async function copy(id) {
	try {
		await navigator.clipboard.writeText(shareUrl(id))
		ElMessage.success('链接已复制')
	} catch {
		error.value = '复制失败，请手动复制链接'
	}
}
async function loadMine(event) {
	if (!event.target.open || mineLoading.value) return
	const current = generation
	mineLoading.value = true
	try {
		const response = await controlledSharesRequest('', 'get', { limit: 100 })
		if (current === generation) mine.value = response.data || []
	} catch (failure) {
		if (current === generation) error.value = message(failure)
	} finally {
		if (current === generation) mineLoading.value = false
	}
}
defineExpose({ openDialog })
</script>

<style scoped>
.controlled-share-dialog {
	display: grid;
	gap: 16px;
	color: var(--el-text-color-primary);
}
.share-file {
	overflow-wrap: anywhere;
	font-weight: 600;
}
.share-hint,
.share-list small {
	color: var(--el-text-color-secondary);
	font-size: 13px;
	line-height: 1.7;
}
.share-error {
	color: var(--el-color-danger);
}
.share-actions {
	display: flex;
	align-items: center;
	gap: 12px;
	flex-wrap: wrap;
}
.share-actions a {
	color: var(--el-color-primary);
}
.share-list {
	padding: 0;
	list-style: none;
	max-height: 280px;
	overflow: auto;
}
.share-list li {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	border-bottom: 1px solid var(--el-border-color-lighter);
	padding: 12px 0;
}
.share-list span {
	overflow-wrap: anywhere;
	min-width: 0;
}
.share-list small {
	display: block;
}
summary {
	cursor: pointer;
	color: var(--el-color-primary);
}
</style>
