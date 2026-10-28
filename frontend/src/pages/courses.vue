<template>
	<main class="shared-courses" aria-labelledby="courses-title">
		<header class="course-heading">
			<div>
				<p class="eyebrow">课程协作</p>
				<h1 id="courses-title">共享课程</h1>
				<p>课程资料单独共享，个人云盘和账号信息保持独立。</p>
			</div>
			<nav aria-label="课程快捷入口">
				<router-link to="/learning">个人资料库</router-link
				><router-link to="/">返回云盘</router-link>
			</nav>
		</header>
		<p v-if="error" class="error" role="alert">{{ error }}</p>
		<section
			v-if="hasRouteInvitation"
			class="course-invitation-preview"
			aria-labelledby="course-invitation-title"
			:aria-busy="previewLoading"
		>
			<div>
				<p class="eyebrow">课程邀请</p>
				<h2 id="course-invitation-title">
					{{ invitationPreview?.name || '查看课程邀请' }}
				</h2>
				<p v-if="previewLoading" class="hint" role="status">
					正在确认邀请信息…
				</p>
				<p v-else-if="previewError" class="error" role="alert">
					{{ previewError }}
				</p>
				<template v-else-if="invitationPreview">
					<p>
						{{ invitationPreview.description || '创建者邀请你加入这门课程。' }}
					</p>
					<p class="hint">
						{{
							invitationPreview.creator
								? '这是你创建的课程。'
								: invitationPreview.joined
								? '你已加入这门课程。'
								: '确认加入后，即可查看和下载课程共享资料。'
						}}
						上传资料仍需课程创建者批准，个人云盘不会对课程成员开放。
					</p>
					<p v-if="invitationPreview.expiresAt" class="hint">
						邀请有效期至 {{ dateTime(invitationPreview.expiresAt) }}
					</p>
				</template>
			</div>
			<div class="actions">
				<el-button
					v-if="invitationPreview"
					type="primary"
					:disabled="busy || previewLoading"
					@click="acceptInvitation"
					>{{
						invitationPreview.joined || invitationPreview.creator
							? '打开课程'
							: '确认加入课程'
					}}</el-button
				>
				<el-button
					v-if="previewError"
					:disabled="busy || previewLoading"
					@click="loadInvitationPreview"
					>重新检查邀请</el-button
				>
				<el-button :disabled="busy" @click="dismissInvitation"
					>取消邀请</el-button
				>
			</div>
		</section>
		<section class="course-toolbar" aria-label="创建或加入课程">
			<form novalidate @submit.prevent="join">
				<label for="course-code">课程邀请码</label
				><input
					id="course-code"
					v-model="joinCode"
					maxlength="32"
					placeholder="输入创建者提供的邀请码"
					autocomplete="off"
					:disabled="busy"
				/><el-button native-type="submit" :disabled="busy || !validCode"
					>加入课程</el-button
				>
			</form>
			<el-button
				v-if="canCreate"
				type="primary"
				:disabled="busy"
				@click="openEditor()"
				>创建课程</el-button
			>
			<el-button :disabled="busy" @click="load">刷新</el-button>
		</section>
		<div class="course-grid" :aria-busy="busy">
			<aside class="course-list" aria-label="我的共享课程">
				<h2>
					我的课程 <small>{{ courses.length }}</small>
				</h2>
				<p v-if="!courses.length" class="hint">
					尚未加入课程。向创建者获取邀请码后即可加入。
				</p>
				<button
					v-for="course in courses"
					:key="course.id"
					:aria-pressed="selectedId === course.id"
					:disabled="busy"
					class="course-item"
					@click="select(course.id)"
				>
					<strong>{{ course.name }}</strong
					><span
						>{{ course.creator ? '我创建的' : '已加入' }} ·
						{{ course.materialCount }} 份资料{{
							course.enabled ? '' : ' · 已暂停'
						}}</span
					>
				</button>
			</aside>
			<section v-if="selected" class="course-content">
				<header class="section-heading">
					<div>
						<h2>{{ selected.name }}</h2>
						<p class="hint">
							{{ selected.description || '创建者尚未填写课程介绍。' }}
						</p>
					</div>
					<div class="actions">
						<template v-if="selected.creator"
							><el-button
								type="primary"
								:disabled="busy || !selected.enabled"
								@click="openInvitations"
								>邀请成员</el-button
							><el-button :disabled="busy" @click="openEditor(selected)"
								>设置</el-button
							></template
						><el-button v-else :disabled="busy" @click="leave"
							>退出课程</el-button
						>
					</div>
				</header>
				<p class="course-capacity">
					课程容量 {{ bytes(selected.usedBytes) }} /
					{{ bytes(selected.quotaBytes) }} ·
					{{ selected.memberCount }} 位加入成员
				</p>
				<progress
					:value="selected.usedBytes"
					:max="selected.quotaBytes"
					aria-label="课程容量使用情况"
				/>
				<div class="materials-toolbar">
					<h3>课程资料</h3>
					<div>
						<el-button
							v-if="mayUpload"
							type="primary"
							:disabled="busy || !selected.enabled"
							@click="fileInput?.click()"
							>上传资料</el-button
						><el-button
							v-else
							:disabled="
								busy || selected.uploadStatus === 'PENDING' || !selected.enabled
							"
							@click="requestUpload"
							>{{
								selected.uploadStatus === 'PENDING'
									? '上传权限待审批'
									: '申请上传权限'
							}}</el-button
						>
					</div>
				</div>
				<input
					ref="fileInput"
					class="hidden-file"
					type="file"
					aria-label="选择课程资料"
					@change="uploadFile"
				/>
				<p class="hint">
					加入后可查看课程资料；上传权限由课程创建者批准一次后持续有效。每份资料最多
					50 MB。
				</p>
				<p v-if="!selected.enabled" class="hint">
					课程已暂停共享，创建者可在设置中重新启用。
				</p>
				<p v-else-if="!materials.length" class="empty">暂无课程资料</p>
				<ul class="materials">
					<li v-for="material in materials" :key="material.id">
						<div>
							<strong>{{ material.filename }}</strong
							><small>{{ bytes(material.sizeBytes) }}</small>
						</div>
						<div class="actions">
							<el-button :disabled="busy" @click="download(material)"
								>下载</el-button
							><el-button
								v-if="selected.creator"
								:disabled="busy"
								@click="removeMaterial(material)"
								>移除</el-button
							>
						</div>
					</li>
				</ul>
				<details v-if="selected.creator" @toggle="membersToggle">
					<summary>成员与上传审批 <span>只有课程创建者可处理</span></summary>
					<el-button :disabled="busy" @click="loadMembers">刷新申请</el-button>
					<p v-if="!members.length" class="hint">暂无加入成员</p>
					<ul class="members">
						<li v-for="member in members" :key="member.userId">
							<span
								>{{ member.nickname || '课程成员'
								}}<small>{{ uploadLabel(member.uploadStatus) }}</small></span
							>
							<div class="actions">
								<el-button
									v-if="member.uploadStatus === 'PENDING'"
									:disabled="busy"
									@click="review(member, true)"
									>同意上传</el-button
								><el-button
									v-if="['PENDING', 'APPROVED'].includes(member.uploadStatus)"
									:disabled="busy"
									@click="review(member, false)"
									>{{
										member.uploadStatus === 'APPROVED' ? '撤销上传' : '拒绝'
									}}</el-button
								><el-button :disabled="busy" @click="removeMember(member)"
									>移出</el-button
								>
							</div>
						</li>
					</ul>
				</details>
				<details v-if="selected.creator" @toggle="incompleteToggle">
					<summary>异常上传清理</summary>
					<p class="hint">
						中断或清理失败的上传仍占用容量。确认上传已结束后，可移除对应记录和文件。
					</p>
					<ul class="materials">
						<li v-for="material in incomplete" :key="material.id">
							<span
								>{{ material.filename }} · {{ bytes(material.sizeBytes) }}</span
							><el-button :disabled="busy" @click="removeMaterial(material)"
								>清理</el-button
							>
						</li>
					</ul>
					<p v-if="!incomplete.length" class="hint">没有未完成上传</p>
				</details>
			</section>
			<section v-else class="course-content empty">
				选择一门课程，或输入邀请码加入。
			</section>
		</div>
		<el-dialog
			v-model="invitationVisible"
			title="邀请成员加入课程"
			width="min(620px, 94vw)"
			:close-on-click-modal="!busy"
			:close-on-press-escape="!busy"
			:show-close="!busy"
			destroy-on-close
		>
			<div class="course-invitation-manager">
				<p>分享邀请链接或邀请码，成员和其他普通管理员都可以加入。</p>
				<p class="hint">
					邀请码可供多位成员重复使用。每次可创建独立的新邀请，也可随时撤销。加入后可下载资料，上传仍需你批准。
				</p>
				<p v-if="invitationError" class="error" role="alert">
					{{ invitationError }}
				</p>
				<div class="invitation-expiry">
					<label
						>邀请有效期<select
							v-model="invitationExpiry"
							aria-label="邀请有效期"
							:disabled="busy"
						>
							<option value="permanent">长期有效</option>
							<option value="1">1 天</option>
							<option value="7">7 天</option>
							<option value="30">30 天</option>
							<option value="custom">自定义时间</option>
						</select></label
					>
					<label v-if="invitationExpiry === 'custom'"
						>失效时间<input
							v-model="invitationCustomExpiry"
							type="datetime-local"
							aria-label="邀请失效时间"
							:disabled="busy"
					/></label>
					<label
						>邀请人数<select
							v-model="invitationMemberMode"
							aria-label="邀请人数限制"
							:disabled="busy"
						>
							<option value="unlimited">不限人数</option>
							<option value="limited">限制人数</option>
						</select></label
					>
					<label v-if="invitationMemberMode === 'limited'"
						>人数上限<input
							v-model.number="invitationMaxMembers"
							type="number"
							min="1"
							max="500"
							step="1"
							aria-label="邀请码人数上限"
							:disabled="busy"
					/></label>
					<el-button
						type="primary"
						:disabled="
							busy || !validInvitationExpiry || !validInvitationMembers
						"
						@click="generateInvitation"
						>生成新邀请链接</el-button
					>
				</div>
				<p
					v-if="invitationExpiry === 'custom' && !validInvitationExpiry"
					class="hint"
				>
					请选择未来的失效时间。
				</p>
				<p class="hint">
					人数限制单独计算通过这个邀请码加入的成员；同一成员重复加入不占新名额。课程当前人数另见课程详情。
				</p>
				<p v-if="!validInvitationMembers" class="hint">
					人数上限须为 1 至 500 的整数。
				</p>
				<section
					v-if="generatedInvitation"
					class="generated-invitation"
					aria-label="本次生成的课程邀请"
				>
					<label
						>邀请链接<input
							:value="generatedInvitation.link"
							aria-label="邀请链接"
							readonly
							@focus="$event.target.select()"
					/></label>
					<div class="actions">
						<el-button :disabled="busy" @click="copyInvitation('link')"
							>复制邀请链接</el-button
						><el-button :disabled="busy" @click="copyInvitation('code')"
							>复制邀请码</el-button
						>
					</div>
					<p class="invitation-code">
						邀请码：<code>{{ generatedInvitation.code }}</code>
					</p>
					<p class="hint">
						有效期：{{
							expiryLabel(generatedInvitation.expiresAt)
						}}。请在关闭窗口前复制保存，之后仍可生成新的独立邀请。
					</p>
					<p class="hint">{{ invitationMembersLabel(generatedInvitation) }}</p>
				</section>
				<h3>邀请记录</h3>
				<p v-if="!invitations.length" class="hint">
					暂无邀请链接，点击上方按钮生成。
				</p>
				<ul class="course-invitations">
					<li v-for="invitation in invitations" :key="invitation.id">
						<div>
							<strong>{{
								courseInvitationStatus(invitation, invitationNow)
							}}</strong
							><small
								>{{ dateTime(invitation.createdAt) }} 创建 ·
								{{ invitationMembersLabel(invitation) }}</small
							><small>有效期：{{ expiryLabel(invitation.expiresAt) }}</small>
						</div>
						<el-button
							v-if="
								['有效', '人数已满'].includes(
									courseInvitationStatus(invitation, invitationNow)
								)
							"
							:disabled="busy"
							@click="revokeInvitation(invitation)"
							>撤销邀请</el-button
						>
					</li>
				</ul>
				<el-button
					v-if="invitationsHasMore"
					:disabled="busy"
					@click="loadMoreInvitations"
					>加载更多邀请</el-button
				>
			</div>
			<template #footer
				><el-button :disabled="busy" @click="invitationVisible = false"
					>关闭邀请窗口</el-button
				></template
			>
		</el-dialog>
		<el-dialog
			v-model="editorVisible"
			:title="editingId ? '课程设置' : '创建共享课程'"
			width="min(480px, 94vw)"
			:close-on-click-modal="!busy"
			:close-on-press-escape="!busy"
			:show-close="!busy"
			destroy-on-close
		>
			<form novalidate class="course-form" @submit.prevent="save">
				<label
					>课程名称<input
						v-model="draft.name"
						maxlength="120"
						required
						:disabled="busy" /></label
				><label
					>课程介绍<textarea
						v-model="draft.description"
						style="resize: none"
						maxlength="1000"
						:disabled="busy"
					/></label
				><label
					>课程容量（MB）<input
						v-model.number="draft.quotaMb"
						type="number"
						min="1"
						max="1024"
						required
						:disabled="busy" /></label
				><label v-if="editingId" class="toggle"
					><input
						v-model="draft.enabled"
						type="checkbox"
						:disabled="busy"
					/>启用课程共享</label
				>
				<p class="hint">
					课程资料保存在独立课程区，不会开放创建者的私人目录。课程容量与个人云盘容量分别计算；服务器可设置更低的容量上限。
				</p>
			</form>
			<template #footer
				><el-button :disabled="busy" @click="editorVisible = false"
					>取消</el-button
				><el-button type="primary" :disabled="busy || !validDraft" @click="save"
					>保存</el-button
				></template
			>
		</el-dialog>
	</main>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import useStorageConfigStore from '~/stores/storage-config'
import {
	courseRequest,
	downloadCourseMaterial,
	uploadCourseMaterial,
} from '~/api/home/courses'
import {
	courseInvitationStatus,
	courseInvitationUrl,
	normalizeCourseInvitation,
} from '~/utils/course-invitations.mjs'

const store = useStorageConfigStore()
const route = useRoute(),
	router = useRouter()
const courses = ref([]),
	selectedId = ref(''),
	materials = ref([]),
	members = ref([]),
	incomplete = ref([])
const busy = ref(false),
	error = ref(''),
	joinCode = ref(''),
	fileInput = ref(null)
const editorVisible = ref(false),
	editingId = ref('')
const draft = ref({ name: '', description: '', quotaMb: 100, enabled: true })
const invitationVisible = ref(false),
	invitationCourseId = ref(''),
	invitations = ref([]),
	generatedInvitation = ref(null),
	invitationError = ref(''),
	invitationNow = ref(Date.now()),
	invitationExpiry = ref('permanent'),
	invitationCustomExpiry = ref(''),
	invitationMemberMode = ref('unlimited'),
	invitationMaxMembers = ref(50),
	invitationsHasMore = ref(false)
let invitationOffset = 0
const invitationPreview = ref(null),
	previewLoading = ref(false),
	previewError = ref('')
const hasRouteInvitation = computed(() => route.query.invite != null)
const routeInvitationCode = computed(() =>
	normalizeCourseInvitation(route.query.invite)
)
const validInvitationExpiry = computed(
	() =>
		invitationExpiry.value !== 'custom' ||
		(Number.isFinite(new Date(invitationCustomExpiry.value).getTime()) &&
			new Date(invitationCustomExpiry.value).getTime() > invitationNow.value)
)
const validInvitationMembers = computed(
	() =>
		invitationMemberMode.value !== 'limited' ||
		(Number.isInteger(invitationMaxMembers.value) &&
			invitationMaxMembers.value >= 1 &&
			invitationMaxMembers.value <= 500)
)
const canCreate = computed(() => store.loginInfo.role === 'TENANT_ADMIN')
const selected = computed(() =>
	courses.value.find((c) => c.id === selectedId.value)
)
const mayUpload = computed(
	() => selected.value?.creator || selected.value?.uploadStatus === 'APPROVED'
)
const validCode = computed(() => /^[a-f0-9]{32}$/i.test(joinCode.value.trim()))
const validDraft = computed(
	() =>
		draft.value.name.trim().length > 0 &&
		Number.isInteger(draft.value.quotaMb) &&
		draft.value.quotaMb >= 1 &&
		draft.value.quotaMb <= 1024
)
let controller = new AbortController(),
	epoch = 0
let previewController,
	previewRevision = 0,
	invitationTimer
const config = () => ({ signal: controller.signal, timeout: 15000 })
const data = async (path = '', method = 'get', body) =>
	(await courseRequest(path, method, body, config())).data
function clear() {
	controller.abort()
	controller = new AbortController()
	epoch++
	courses.value = []
	selectedId.value = ''
	materials.value = []
	members.value = []
	incomplete.value = []
	joinCode.value = ''
	editorVisible.value = false
	draft.value = { name: '', description: '', quotaMb: 100, enabled: true }
	error.value = ''
	busy.value = false
	invitationVisible.value = false
	clearInvitations()
	clearInvitationPreview()
}
function clearInvitations() {
	invitationCourseId.value = ''
	invitations.value = []
	generatedInvitation.value = null
	invitationError.value = ''
	invitationExpiry.value = 'permanent'
	invitationCustomExpiry.value = ''
	invitationMemberMode.value = 'unlimited'
	invitationMaxMembers.value = 50
	invitationsHasMore.value = false
	invitationOffset = 0
}
function clearInvitationPreview() {
	previewController?.abort()
	previewRevision++
	invitationPreview.value = null
	previewError.value = ''
	previewLoading.value = false
}
function dateTime(value) {
	return new Date(Number(value)).toLocaleString('zh-CN', { hour12: false })
}
function expiryLabel(value) {
	return value === null ? '长期有效' : dateTime(value)
}
function invitationMembersLabel(invitation) {
	return `已邀请 ${invitation.usedCount} 人 / ${
		invitation.maxMembers == null
			? '不限人数'
			: `上限 ${invitation.maxMembers} 人`
	}`
}
function failureMessage(err) {
	return (
		err?.response?.data?.msg ||
		err?.data?.msg ||
		err?.message ||
		'操作失败，请刷新确认结果后重试'
	)
}
function bytes(n) {
	return n >= 1048576 ? `${(n / 1048576).toFixed(1)} MB` : `${n} B`
}
function uploadLabel(value) {
	return (
		{
			NONE: '尚未申请上传',
			PENDING: '待审批',
			APPROVED: '允许上传',
			REJECTED: '未获上传权限',
		}[value] || value
	)
}
async function action(fn, errorTarget = error) {
	if (busy.value) return
	const started = epoch
	busy.value = true
	errorTarget.value = ''
	try {
		await fn(() => started === epoch && !controller.signal.aborted)
	} catch (err) {
		if (
			started === epoch &&
			!controller.signal.aborted &&
			err !== 'cancel' &&
			err !== 'close'
		)
			errorTarget.value = failureMessage(err)
	} finally {
		if (started === epoch) busy.value = false
	}
}
async function loadInvitationPreview() {
	clearInvitationPreview()
	if (!hasRouteInvitation.value || !store.loginInfo.isLogin) return
	const code = routeInvitationCode.value
	if (!code) {
		previewError.value = '邀请链接无效，请向课程创建者索取新的链接或邀请码。'
		return
	}
	const started = epoch,
		revision = previewRevision,
		requestController = new AbortController()
	previewController = requestController
	const alive = () =>
		started === epoch &&
		revision === previewRevision &&
		!requestController.signal.aborted &&
		routeInvitationCode.value === code
	previewLoading.value = true
	try {
		const response = await courseRequest(
			'/invitation-preview',
			'post',
			{ code },
			{ signal: requestController.signal, timeout: 15000 }
		)
		if (alive()) invitationPreview.value = response.data
	} catch (err) {
		if (alive())
			previewError.value =
				err?.response?.data?.msg ||
				err?.data?.msg ||
				err?.message ||
				'无法查看邀请，请联系课程创建者获取新的邀请。'
	} finally {
		if (alive()) previewLoading.value = false
	}
}
async function removeRouteInvitation(courseId) {
	clearInvitationPreview()
	await router.replace({
		query: {
			...route.query,
			invite: undefined,
			...(courseId ? { course: courseId } : {}),
		},
	})
}
function dismissInvitation() {
	if (!busy.value) return removeRouteInvitation()
}
function acceptInvitation() {
	if (
		!invitationPreview.value ||
		previewLoading.value ||
		!routeInvitationCode.value
	)
		return
	const code = routeInvitationCode.value,
		preview = invitationPreview.value
	return action(async (alive) => {
		const result =
			preview.joined || preview.creator
				? preview
				: await data('/join', 'post', { code })
		if (!alive()) return
		selectedId.value = result.id
		await refresh(alive)
		if (alive()) await removeRouteInvitation(result.id)
	}, previewError)
}
function openInvitations() {
	if (busy.value || !selected.value?.creator || !selected.value.enabled) return
	clearInvitations()
	const id = selectedId.value
	invitationCourseId.value = id
	invitationVisible.value = true
	return action((alive) => readInvitations(id, alive), invitationError)
}
async function readInvitations(id, alive, append = false) {
	const offset = append ? invitationOffset : 0
	const result = await data(`/${id}/invitations?offset=${offset}&limit=20`)
	if (!alive() || invitationCourseId.value !== id) return
	if (!Array.isArray(result))
		throw new Error('邀请记录读取失败，请重新打开邀请窗口。')
	invitations.value = append
		? [
				...new Map(
					[...invitations.value, ...result].map((entry) => [entry.id, entry])
				).values(),
		  ]
		: result
	invitationOffset = offset + result.length
	invitationsHasMore.value = result.length === 20
	invitationNow.value = Date.now()
}
function loadMoreInvitations() {
	const id = invitationCourseId.value
	if (id && invitationsHasMore.value)
		return action((alive) => readInvitations(id, alive, true), invitationError)
}
function generateInvitation() {
	const id = invitationCourseId.value
	if (
		!id ||
		id !== selectedId.value ||
		!selected.value?.creator ||
		!validInvitationExpiry.value ||
		!validInvitationMembers.value
	)
		return
	return action(async (alive) => {
		let expiresAt = null
		if (invitationExpiry.value === 'custom')
			expiresAt = new Date(invitationCustomExpiry.value).getTime()
		else if (invitationExpiry.value !== 'permanent')
			expiresAt = Date.now() + Number(invitationExpiry.value) * 86400000
		if (
			expiresAt !== null &&
			(!Number.isFinite(expiresAt) || expiresAt <= Date.now())
		)
			throw new Error('请选择未来的失效时间。')
		const result = await data(`/${id}/invitations`, 'post', {
			expiresAt,
			maxMembers:
				invitationMemberMode.value === 'limited'
					? invitationMaxMembers.value
					: null,
		})
		if (!alive() || invitationCourseId.value !== id) return
		generatedInvitation.value = {
			...result,
			link: courseInvitationUrl(window.location.origin, result.code),
		}
		await readInvitations(id, alive)
	}, invitationError)
}
function copyInvitation(kind) {
	const generated = generatedInvitation.value
	if (!generated) return
	return action(async (alive) => {
		if (!window.navigator.clipboard?.writeText)
			throw new Error('浏览器未开放剪贴板，请选中上方邀请链接手动复制。')
		try {
			await window.navigator.clipboard.writeText(
				kind === 'code' ? generated.code : generated.link
			)
		} catch {
			if (alive())
				throw new Error('自动复制未成功，请选中上方邀请链接手动复制。')
			return
		}
		if (alive() && generatedInvitation.value === generated)
			ElMessage.success(kind === 'code' ? '邀请码已复制' : '邀请链接已复制')
	}, invitationError)
}
function revokeInvitation(invitation) {
	const id = invitationCourseId.value
	if (!id) return
	return action(async (alive) => {
		await ElMessageBox.confirm(
			'撤销后，这个邀请链接和邀请码将不能再加入课程。已加入成员不受影响。',
			'撤销课程邀请'
		)
		if (!alive() || invitationCourseId.value !== id) return
		await data(`/${id}/invitations/${invitation.id}`, 'delete')
		if (!alive() || invitationCourseId.value !== id) return
		if (generatedInvitation.value?.id === invitation.id)
			generatedInvitation.value = null
		await readInvitations(id, alive)
	}, invitationError)
}
async function refresh(alive = () => true) {
	const list = await data()
	if (!alive()) return
	courses.value = list
	// JSON numeric IDs and URL query strings describe the same course. Keep
	// the backend's original ID in selection/API calls; normalize only at the URL boundary.
	const requested = list.find((c) => String(c.id) === route.query.course)
	if (!selectedId.value && requested) selectedId.value = requested.id
	if (!list.some((c) => c.id === selectedId.value))
		selectedId.value = list[0]?.id || ''
	materials.value = []
	members.value = []
	incomplete.value = []
	if (selected.value?.enabled) {
		const id = selectedId.value,
			result = await data(`/${id}/materials`)
		if (alive() && id === selectedId.value) materials.value = result
	}
}
function load() {
	return action(refresh)
}
function select(id) {
	return action(async (alive) => {
		selectedId.value = id
		await refresh(alive)
	})
}
function join() {
	if (!validCode.value) return
	return action(async (alive) => {
		const course = await data('/join', 'post', {
			code: joinCode.value.trim().toLowerCase(),
		})
		if (!alive()) return
		selectedId.value = course.id
		joinCode.value = ''
		await refresh(alive)
	})
}
function openEditor(course) {
	editingId.value = course?.id || ''
	draft.value = {
		name: course?.name || '',
		description: course?.description || '',
		quotaMb: course ? course.quotaBytes / 1048576 : 100,
		enabled: course?.enabled ?? true,
	}
	editorVisible.value = true
}
function save() {
	if (!validDraft.value) return
	return action(async (alive) => {
		const creating = !editingId.value
		const result = await data(
			editingId.value ? `/${editingId.value}` : '',
			editingId.value ? 'put' : 'post',
			{
				name: draft.value.name.trim(),
				description: draft.value.description,
				quotaBytes: Math.floor(draft.value.quotaMb * 1048576),
				enabled: draft.value.enabled,
			}
		)
		if (!alive()) return
		editorVisible.value = false
		selectedId.value = result.id
		await refresh(alive)
		if (alive() && creating) {
			clearInvitations()
			invitationCourseId.value = result.id
			invitationVisible.value = true
			try {
				await readInvitations(result.id, alive)
			} catch (err) {
				if (alive()) invitationError.value = failureMessage(err)
			}
			if (alive())
				ElMessage.success('课程已创建，选择邀请有效期和人数后即可邀请成员')
		}
	})
}
function leave() {
	return action(async (alive) => {
		await ElMessageBox.confirm(
			'退出后将不能查看课程资料，上传权限也会清除。',
			'退出课程'
		)
		if (!alive()) return
		await data(`/${selectedId.value}/leave`, 'post')
		await refresh(alive)
	})
}
function requestUpload() {
	return action(async (alive) => {
		await data(`/${selectedId.value}/upload-request`, 'post')
		await refresh(alive)
		if (alive()) ElMessage.success('已提交给课程创建者审批')
	})
}
async function uploadFile(event) {
	const file = event.target.files?.[0]
	event.target.value = ''
	if (!file) return
	if (!file.size || file.size > 50 * 1048576) {
		error.value = '请选择 1 字节至 50 MB 的文件'
		return
	}
	return action(async (alive) => {
		await uploadCourseMaterial(selectedId.value, file, {
			signal: controller.signal,
		})
		await refresh(alive)
	})
}
function download(material) {
	return action(async (alive) => {
		const blob = await downloadCourseMaterial(selectedId.value, material, {
			signal: controller.signal,
		})
		if (!alive()) return
		const url = URL.createObjectURL(blob),
			link = document.createElement('a')
		link.href = url
		link.download = material.filename
		link.click()
		setTimeout(() => URL.revokeObjectURL(url), 1000)
	})
}
function removeMaterial(material) {
	return action(async (alive) => {
		await ElMessageBox.confirm(
			`将删除课程中的“${material.filename}”，请确认上传已结束。`,
			'移除课程资料'
		)
		if (!alive()) return
		await data(`/${selectedId.value}/materials/${material.id}`, 'delete')
		await refresh(alive)
	})
}
function loadMembers() {
	return action(async (alive) => {
		const result = await data(`/${selectedId.value}/members`)
		if (alive()) members.value = result
	})
}
function membersToggle(event) {
	if (event.target.open) loadMembers()
}
function incompleteToggle(event) {
	if (event.target.open)
		return action(async (alive) => {
			const result = await data(`/${selectedId.value}/incomplete-uploads`)
			if (alive()) incomplete.value = result
		})
}
function review(member, approve) {
	return action(async (alive) => {
		await data(`/${selectedId.value}/members/${member.userId}/upload`, 'post', {
			approve,
		})
		const result = await data(`/${selectedId.value}/members`)
		if (alive()) members.value = result
	})
}
function removeMember(member) {
	return action(async (alive) => {
		await ElMessageBox.confirm(
			'移出后该成员立即失去课程访问权限，原邀请码不能重新加入。',
			'移出课程成员'
		)
		if (!alive()) return
		await data(`/${selectedId.value}/members/${member.userId}`, 'delete')
		const result = await data(`/${selectedId.value}/members`)
		if (alive()) members.value = result
	})
}
watch(
	() => [
		store.loginInfo.username,
		store.loginInfo.tenantId,
		store.loginInfo.role,
		store.loginInfo.isLogin,
	],
	() => {
		clear()
		if (store.loginInfo.isLogin) {
			load()
			loadInvitationPreview()
		}
	}
)
watch(selectedId, (id) => {
	if (invitationCourseId.value && invitationCourseId.value !== id)
		invitationVisible.value = false
	const queryId = id ? String(id) : undefined
	if (route.query.course !== queryId)
		router
			.replace({ query: { ...route.query, course: queryId } })
			.catch(() => {})
})
watch(invitationVisible, (visible) => {
	if (!visible) clearInvitations()
})
watch(() => route.query.invite, loadInvitationPreview, { immediate: true })
watch(
	() => route.query.course,
	(id) => {
		const requested = courses.value.find((course) => String(course.id) === id)
		if (requested && requested.id !== selectedId.value && !busy.value)
			select(requested.id)
	}
)
onMounted(() => {
	load()
	invitationTimer = setInterval(() => {
		invitationNow.value = Date.now()
	}, 30000)
})
onUnmounted(() => {
	clearInterval(invitationTimer)
	clear()
	controller.abort()
})
</script>

<style scoped>
.shared-courses {
	max-width: 1450px;
	margin: 0 auto;
	padding: 32px;
	color: #262626;
}
.course-heading,
.section-heading,
.course-toolbar,
.materials-toolbar {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 20px;
}
.course-heading {
	margin-bottom: 26px;
}
.course-heading h1 {
	font-size: 28px;
	margin: 6px 0 12px;
}
.course-heading p {
	color: #737177;
}
.eyebrow {
	font-size: 12px;
	color: #7859c8 !important;
}
.course-heading nav,
.actions {
	display: flex;
	align-items: center;
	gap: 10px;
	flex-wrap: wrap;
}
.course-heading a {
	color: #4b4852;
	font-size: 13px;
	padding: 9px 14px;
	border: 1px solid #dedce3;
	border-radius: 30px;
	background: #fff;
}
.course-toolbar {
	padding: 18px 22px;
	background: #fff;
	border: 1px solid #dedce3;
	border-radius: 16px;
	margin-bottom: 20px;
}
.course-invitation-preview {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 20px;
	padding: 22px;
	margin-bottom: 20px;
	border: 1px solid #d9cdec;
	border-radius: 16px;
	background: #f7f4fc;
}
.course-invitation-preview h2 {
	font-size: 20px;
	margin: 6px 0 12px;
}
.course-invitation-manager {
	color: #343139;
	line-height: 1.7;
}
.course-invitation-manager h3 {
	margin-top: 24px;
	font-size: 15px;
}
.generated-invitation {
	padding: 16px;
	margin-top: 18px;
	border-radius: 12px;
	background: #f5f2fa;
}
.generated-invitation label {
	display: grid;
	gap: 8px;
	font-size: 13px;
}
.generated-invitation input {
	width: 100%;
}
.generated-invitation .actions {
	margin-top: 12px;
}
.invitation-expiry {
	display: flex;
	flex-wrap: wrap;
	align-items: flex-end;
	gap: 12px;
	margin-top: 18px;
}
.invitation-expiry label {
	display: grid;
	gap: 6px;
	font-size: 13px;
}
.invitation-expiry select {
	border: 1px solid #d9d6dd;
	border-radius: 8px;
	padding: 9px 12px;
	background: #fff;
	color: #262626;
}
.invitation-code {
	font-size: 12px;
	overflow-wrap: anywhere;
}
.course-invitations {
	list-style: none;
	padding: 0;
}
.course-invitations li {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 12px;
	padding: 14px 0;
	border-bottom: 1px solid #efedf2;
}
.course-invitations strong {
	font-size: 13px;
}
.course-invitations small {
	display: block;
	color: #8a8690;
	font-size: 12px;
	margin-top: 4px;
}
.legacy-invitation {
	padding-top: 16px;
	border-top: 1px solid #e7e3ec;
	margin-top: 20px;
}
.legacy-invitation summary {
	cursor: pointer;
	font-size: 13px;
}
.course-toolbar form {
	display: flex;
	align-items: center;
	gap: 12px;
	flex: 1;
}
.course-toolbar label {
	font-size: 13px;
	white-space: nowrap;
}
input:not([type='checkbox']),
textarea {
	background: #fff;
	border: 1px solid #d9d6dd;
	border-radius: 8px;
	padding: 10px 12px;
	color: #262626;
	min-width: 0;
	box-sizing: border-box;
}
input:focus,
textarea:focus {
	outline: 2px solid #9276dc;
	outline-offset: 2px;
}
.course-toolbar input {
	width: min(330px, 100%);
}
.course-grid {
	display: grid;
	grid-template-columns: 260px minmax(0, 1fr);
	gap: 20px;
}
.course-list,
.course-content {
	background: #fff;
	border: 1px solid #dedce3;
	border-radius: 18px;
	padding: 24px;
	min-width: 0;
}
.course-list h2,
.course-content h2 {
	font-size: 19px;
	margin: 0 0 18px;
}
.course-list h2 small {
	color: #999;
	font-weight: 400;
}
.course-item {
	width: 100%;
	text-align: left;
	border: 1px solid transparent;
	background: transparent;
	border-radius: 10px;
	padding: 14px;
	margin-bottom: 8px;
}
.course-item[aria-pressed='true'] {
	background: #f1eef8;
	border-color: #d9cdec;
}
.course-item span {
	display: block;
	color: #777;
	font-size: 12px;
	margin-top: 7px;
}
.course-item strong {
	overflow-wrap: anywhere;
}
.hint,
.course-capacity {
	font-size: 12px;
	line-height: 1.8;
	color: #7e7a85;
}
.course-capacity {
	margin: 10px 0;
}
.course-content progress {
	width: 100%;
	height: 6px;
	accent-color: #8661db;
}
.materials-toolbar {
	margin-top: 22px;
}
.materials-toolbar h3 {
	font-size: 16px;
}
.materials,
.members {
	padding: 0;
	list-style: none;
}
.materials li,
.members li {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 14px;
	padding: 15px 0;
	border-bottom: 1px solid #efedf2;
}
.materials strong {
	font-size: 14px;
	overflow-wrap: anywhere;
}
.materials small,
.members small {
	display: block;
	color: #8a8690;
	font-size: 12px;
	margin-top: 6px;
}
.course-content details {
	border-top: 1px solid #e7e3ec;
	padding-top: 18px;
	margin-top: 24px;
}
.course-content summary {
	cursor: pointer;
	font-size: 14px;
	margin-bottom: 15px;
}
.course-content summary span {
	font-size: 12px;
	color: #9b96a1;
	margin-left: 12px;
}
.empty {
	padding: 50px 24px;
	text-align: center;
	color: #938d99;
	font-size: 14px;
}
.error {
	color: #ad3030;
	background: #fff2f1;
	border: 1px solid #f3cfca;
	padding: 12px;
	border-radius: 10px;
	font-size: 13px;
}
.course-form {
	display: grid;
	gap: 18px;
}
.course-form label:not(.toggle) {
	display: grid;
	gap: 9px;
	font-size: 13px;
}
.course-form textarea {
	min-height: 88px;
}
.toggle {
	font-size: 13px;
	display: flex;
	gap: 9px;
	align-items: center;
}
.hidden-file {
	display: none;
}
:deep(.el-button--primary) {
	background: #242325;
	border-color: #242325;
}
:deep(.el-button) {
	border-radius: 22px;
}
:deep(.el-button + .el-button) {
	margin-left: 0;
}
@media (max-width: 880px) {
	.shared-courses {
		padding: 18px;
	}
	.course-grid {
		grid-template-columns: 1fr;
	}
	.course-list {
		padding: 18px;
		display: flex;
		overflow: auto;
		gap: 10px;
		align-items: center;
	}
	.course-list h2 {
		white-space: nowrap;
		font-size: 15px;
		margin: 0;
	}
	.course-item {
		min-width: 160px;
		width: auto;
		margin: 0;
	}
	.course-heading,
	.course-toolbar,
	.section-heading,
	.course-invitation-preview {
		align-items: flex-start;
		flex-wrap: wrap;
	}
	.course-toolbar form {
		flex-wrap: wrap;
		width: 100%;
	}
	.course-toolbar input {
		flex: 1;
		min-width: 180px;
	}
	.course-content {
		padding: 20px;
	}
	.members li,
	.materials li {
		align-items: flex-start;
		flex-wrap: wrap;
	}
}
@media (prefers-reduced-motion: no-preference) {
	.course-item,
	.course-heading a {
		transition: background 0.15s, border-color 0.15s;
	}
}
</style>
