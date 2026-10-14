<template>
	<main v-loading="busy" class="learning-page">
		<header class="learning-heading">
			<div>
				<p class="eyebrow">学习资料</p>
				<h1>我的课程资料库</h1>
				<p>把讲义、笔记和参考资料，整理成容易找到的课程档案。</p>
			</div>
			<nav class="heading-links" aria-label="学习资料快捷入口">
				<router-link to="/agent-tasks">批量整理</router-link>
				<router-link to="/">返回云盘</router-link>
			</nav>
		</header>
		<details class="learning-notice">
			<summary>
				<span class="notice-mark" aria-hidden="true">i</span>资料仅自己可见
				<span class="notice-more">查看支持格式与隐私说明</span>
			</summary>
			<p>
				资料库仅自己可见。支持本地存储的 TXT / MD / PDF / DOCX，每份 ≤ 5
				MB，最多 30 份。每份最多索引 240,000 字符，PDF 最多前 300
				页；超限会标记覆盖范围。纯扫描 PDF 可在管理员启用本地 OCR
				后识别，默认最多 10 页； 混合 PDF 的图片文字不会自动补识别，OCR
				结果需人工校对。启用语义索引后，每份资料最多前 64
				段会发送给独立嵌入服务；
				小芽回答时相关片段会发送给聊天模型。请仅索引允许外传的资料；未启用语义服务时仍使用本地关键词检索。
			</p>
		</details>
		<section class="learning-toolbar">
			<el-select
				v-model="semester"
				clearable
				placeholder="全部学期"
				aria-label="筛选学期"
			>
				<el-option
					v-for="item in semesters"
					:key="item"
					:value="item"
					:label="item"
				/>
			</el-select>
			<el-button type="primary" @click="editCourse()">新建课程</el-button>
		</section>
		<div class="learning-grid">
			<aside class="learning-card course-sidebar">
				<h2 class="course-list-heading">
					我的课程 <small>{{ courses.length }} / 50</small>
				</h2>
				<el-empty
					v-if="!visibleCourses.length"
					description="还没有课程，先创建一门吧"
					:image-size="70"
				/>
				<button
					v-for="course in visibleCourses"
					:key="course.id"
					class="course-button"
					:class="{ active: selected === course.id }"
					:aria-pressed="selected === course.id"
					@click="selectCourse(course.id)"
				>
					<strong>{{ course.name }}</strong
					><span>{{ course.semester }} · #{{ course.id }}</span>
				</button>
			</aside>
			<section class="learning-card materials-panel">
				<template v-if="currentCourse">
					<div class="section-heading">
						<div>
							<p class="course-semester">{{ currentCourse.semester }}</p>
							<h2>{{ currentCourse.name }}</h2>
							<p class="course-material-count">
								{{ materials.length }} 份已归档资料
							</p>
						</div>
						<div class="course-actions">
							<el-button @click="editCourse(currentCourse)">编辑课程</el-button>
							<el-button type="danger" plain @click="removeCourse"
								>删除课程</el-button
							>
						</div>
					</div>
					<div class="learning-toolbar">
						<el-input
							v-model="tagFilter"
							clearable
							placeholder="筛选文件名或标签"
							aria-label="筛选文件名或标签"
							maxlength="200"
						/>
						<el-button type="primary" @click="openPicker"
							>从云盘添加资料</el-button
						>
					</div>
					<el-empty
						v-if="!visibleMaterials.length"
						description="暂无资料，请先上传文件到云盘，再添加索引"
						:image-size="80"
					/>
					<article
						v-for="item in visibleMaterials"
						:key="item.id"
						class="material-row"
					>
						<div>
							<strong>{{ item.name }}</strong>
							<p>{{ item.tags || '未设置标签' }}</p>
							<small
								>{{ item.storageKey }} · {{ item.path }} · 索引于
								{{ item.indexedAt }}</small
							>
							<p
								class="index-coverage"
								:class="{ partial: learningCoverage(item).partial }"
							>
								{{ learningCoverage(item).text }}
							</p>
							<p v-if="item.semanticNotice" class="index-coverage">
								{{ item.semanticNotice }}
							</p>
						</div>
						<div class="material-actions">
							<router-link :to="fileFolderRoute(item)">所在目录</router-link>
							<el-button size="small" @click="refreshMaterial(item)"
								>重新索引</el-button
							>
							<el-button size="small" @click="repairMaterial(item)"
								>修复路径</el-button
							>
							<el-button
								size="small"
								type="danger"
								plain
								@click="removeMaterial(item)"
								>移除索引</el-button
							>
						</div>
					</article>
					<form class="learning-search" @submit.prevent="search">
						<h2>检索课程资料</h2>
						<p>
							检索当前课程；文件变化或权限失效后会跳过旧索引。修改文件后，请点击“重新索引”。
						</p>
						<div class="learning-toolbar">
							<el-input
								v-model="query"
								maxlength="200"
								placeholder="例如：数据库事务、二叉树、期末复习"
								aria-label="资料检索词"
							/>
							<el-button
								native-type="submit"
								type="primary"
								:disabled="!query.trim()"
								>检索正文</el-button
							>
						</div>
					</form>
					<template v-if="searchResult">
						<p role="status">
							返回 {{ searchResult.citations.length }} 个片段；跳过
							{{ searchResult.skipped }}
							份变化或不可访问的候选资料（非全库失效数）。
						</p>
						<el-empty
							v-if="!searchResult.citations.length"
							description="当前可用资料中未检索到足够依据，请更换关键词"
							:image-size="60"
						/>
						<article
							v-for="citation in searchResult.citations"
							:key="citation.reference"
							class="citation-card"
						>
							<strong
								>[{{ citation.reference }}] {{ citation.name }} · 文本片段
								{{ citation.segment }}</strong
							>
							<p>{{ citation.excerpt }}</p>
						</article>
					</template>
				</template>
				<el-empty v-else description="选择一门课程，开始整理学习资料" />
			</section>
		</div>
		<el-dialog
			v-model="courseDialog"
			:title="courseForm.id ? '编辑课程' : '新建课程'"
			width="min(480px, 94vw)"
		>
			<el-form label-position="top" @submit.prevent="saveCourse">
				<el-form-item label="课程名称"
					><el-input
						v-model="courseForm.name"
						maxlength="100"
						placeholder="例如：数据结构"
				/></el-form-item>
				<el-form-item label="学期"
					><el-input
						v-model="courseForm.semester"
						maxlength="40"
						placeholder="例如：2026–2027 学年第一学期"
				/></el-form-item>
				<el-button
					native-type="submit"
					type="primary"
					:loading="busy"
					:disabled="!courseForm.name.trim() || !courseForm.semester.trim()"
					>保存课程</el-button
				>
			</el-form>
		</el-dialog>
		<el-dialog
			v-model="pickerDialog"
			title="从云盘添加资料索引"
			width="min(700px, 94vw)"
		>
			<p>
				文件仍保存在云盘中。这里只保存归档信息和检索文字，不复制原文件，也不保存目录密码。
			</p>
			<el-select
				v-model="materialForm.storageKey"
				placeholder="选择可访问的本地存储"
				aria-label="选择存储源"
				@change="changeStorage"
			>
				<el-option
					v-for="source in sources"
					:key="source.key"
					:value="source.key"
					:label="source.name"
				/>
			</el-select>
			<div class="learning-toolbar">
				<el-button
					:disabled="directory === '/' || pickerBusy"
					@click="parentDirectory"
					>上一级</el-button
				><span>{{ directory }}</span>
			</div>
			<div v-loading="pickerBusy" class="file-picker">
				<p v-if="!files.length">目录为空，或没有可选文件。</p>
				<button
					v-for="file in files"
					:key="file.name"
					:disabled="
						pickerBusy ||
						(file.type !== 'FOLDER' && !supportedLearningFile(file.name))
					"
					:class="{ active: materialForm.path === directory + file.name }"
					@click="chooseFile(file)"
				>
					{{ file.type === 'FOLDER' ? '📁' : '📄' }} {{ file.name }}
				</button>
			</div>
			<p>已选：{{ materialForm.path || '尚未选择文件' }}</p>
			<el-input
				v-model="materialForm.tags"
				maxlength="200"
				placeholder="标签，以逗号分隔，例如：期末,重点,实验"
				aria-label="资料标签"
			/>
			<template #footer
				><el-button @click="pickerDialog = false">取消</el-button>
				<el-button
					type="primary"
					:disabled="!materialForm.path || pickerBusy"
					:loading="busy"
					@click="addMaterial"
					>确认建立索引</el-button
				></template
			>
		</el-dialog>
	</main>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import {
	learningRequest,
	learningStorageList,
	learningFiles,
} from '~/api/home/learning'
import {
	supportedLearningFile,
	filterLearningMaterials,
	learningFileRoute,
	learningCoverage,
} from '~/utils/learning.mjs'

const route = useRoute()
const courses = ref([])
const materials = ref([])
const selected = ref(null)
const semester = ref('')
const tagFilter = ref('')
const busy = ref(false)
const courseDialog = ref(false)
const courseForm = ref({ id: null, name: '', semester: '' })
const pickerDialog = ref(false)
const sources = ref([])
const files = ref([])
const directory = ref('/')
const pickerBusy = ref(false)
const materialForm = ref({ storageKey: '', path: '', tags: '' })
const query = ref('')
const searchResult = ref(null)
const semesters = computed(() => [
	...new Set(courses.value.map((c) => c.semester)),
])
const visibleCourses = computed(() =>
	courses.value.filter((c) => !semester.value || c.semester === semester.value)
)
const currentCourse = computed(() =>
	courses.value.find((c) => c.id === selected.value)
)
const visibleMaterials = computed(() =>
	filterLearningMaterials(materials.value, tagFilter.value)
)
const fileFolderRoute = (item) =>
	learningFileRoute(
		item.storageKey,
		item.path.slice(0, item.path.lastIndexOf('/') + 1)
	)

async function run(action) {
	if (busy.value) return
	busy.value = true
	try {
		await action()
	} catch (error) {
		if (error !== 'cancel' && error !== 'close')
			ElMessage.error(
				error?.data?.msg || error?.response?.data?.msg || '操作失败，请稍后重试'
			)
	} finally {
		busy.value = false
	}
}
async function loadCourses() {
	courses.value = (await learningRequest('get', '/courses')).data
}
async function loadMaterials() {
	materials.value = []
	searchResult.value = null
	materials.value = selected.value
		? (await learningRequest('get', '/materials', { courseId: selected.value }))
				.data
		: []
	searchResult.value = null
}
function selectCourse(id) {
	run(async () => {
		selected.value = id
		tagFilter.value = ''
		await loadMaterials()
	})
}
function editCourse(course) {
	courseForm.value = course
		? { ...course }
		: { id: null, name: '', semester: semester.value }
	courseDialog.value = true
}
function saveCourse() {
	run(async () => {
		const form = courseForm.value
		const result = await learningRequest(
			form.id ? 'put' : 'post',
			form.id ? `/courses/${form.id}` : '/courses',
			{ name: form.name, semester: form.semester }
		)
		courseDialog.value = false
		semester.value = ''
		selected.value = result.data.id
		await loadCourses()
		await loadMaterials()
	})
}
function removeCourse() {
	run(async () => {
		await ElMessageBox.confirm(
			'删除此课程及其资料索引？云盘中的原文件不会删除。',
			'确认删除课程',
			{ type: 'warning' }
		)
		await learningRequest('delete', `/courses/${selected.value}`)
		selected.value = null
		await loadCourses()
		await loadMaterials()
	})
}
function openPicker() {
	run(async () => {
		sources.value = (await learningStorageList()).data.filter(
			(s) => (s.type?.key || s.type) === 'local'
		)
		materialForm.value = { storageKey: '', path: '', tags: '' }
		directory.value = '/'
		files.value = []
		pickerDialog.value = true
	})
}
let pickerRequest = 0
async function browse(path) {
	const request = ++pickerRequest
	pickerBusy.value = true
	files.value = []
	materialForm.value.path = ''
	directory.value = path
	try {
		const response = await learningFiles(materialForm.value.storageKey, path)
		if (request === pickerRequest) files.value = response.data.files
	} catch {
		/* Existing file API displays permission/password errors. */
	} finally {
		if (request === pickerRequest) pickerBusy.value = false
	}
}
function changeStorage() {
	browse('/')
}
function parentDirectory() {
	browse(directory.value.replace(/[^/]+\/$/, '') || '/')
}
function chooseFile(file) {
	if (file.type === 'FOLDER') browse(directory.value + file.name + '/')
	else materialForm.value.path = directory.value + file.name
}
function addMaterial() {
	run(async () => {
		await learningRequest('post', '/materials', {
			...materialForm.value,
			courseId: selected.value,
		})
		pickerDialog.value = false
		await loadMaterials()
		ElMessage.success('资料索引已建立，可在当前课程检索或询问小芽')
	})
}
function refreshMaterial(item) {
	run(async () => {
		await learningRequest('post', `/materials/${item.id}/refresh`)
		await loadMaterials()
		ElMessage.success('索引已更新')
	})
}
function repairMaterial(item) {
	run(async () => {
		const { value } = await ElMessageBox.prompt(
			'填写此存储源内的新文件路径。将重新检查权限并建立索引，不移动或删除文件。',
			'修复资料路径',
			{
				inputValue: item.path,
				inputPlaceholder: '/文档/课程笔记.pdf',
				inputValidator: (value) =>
					(value?.trim() && value.length <= 500) ||
					'请填写不超过 500 字符的文件路径',
				confirmButtonText: '验证并重新索引',
				cancelButtonText: '取消',
			}
		)
		await learningRequest('post', `/materials/${item.id}/relink`, {
			storageKey: item.storageKey,
			path: value.trim(),
		})
		await loadMaterials()
		ElMessage.success('资料路径和索引已更新，原文件未修改')
	})
}
function removeMaterial(item) {
	run(async () => {
		await ElMessageBox.confirm(
			'仅移除资料索引，云盘原文件不受影响。是否继续？',
			'移除索引'
		)
		await learningRequest('delete', `/materials/${item.id}`)
		await loadMaterials()
	})
}
function search() {
	searchResult.value = null
	run(async () => {
		searchResult.value = (
			await learningRequest('get', '/search', {
				query: query.value,
				courseId: selected.value,
			})
		).data
	})
}
onMounted(() =>
	run(async () => {
		await loadCourses()
		const requested = Number(route.query.courseId)
		selected.value =
			courses.value.find((c) => c.id === requested)?.id ||
			courses.value[0]?.id ||
			null
		await loadMaterials()
	})
)
watch(
	() => route.query.courseId,
	(id) => {
		const course = courses.value.find((c) => c.id === Number(id))
		if (course) selectCourse(course.id)
	}
)
</script>

<style scoped>
.learning-page {
	max-width: 1240px;
	margin: 0 auto;
	padding: 36px 24px 90px;
	color: var(--cloud-ink, #273936);
}
.index-coverage {
	font-size: 12px;
	line-height: 1.6;
	color: var(--cloud-muted, #6d7974);
}
.index-coverage.partial {
	color: #946b38;
}
.learning-heading,
.section-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 16px;
	margin-bottom: 24px;
}
.learning-heading h1 {
	font-size: 27px;
	margin: 4px 0 8px;
	font-weight: 650;
}
.learning-heading p,
.learning-search p {
	color: var(--cloud-muted, #6d7974);
	font-size: 13px;
	line-height: 1.7;
}
.eyebrow {
	letter-spacing: 0.12em;
	font-size: 12px;
	color: var(--cloud-primary, #277566) !important;
}
a {
	color: var(--cloud-primary, #277566);
}
.heading-links {
	display: flex;
	gap: 9px;
	flex-wrap: wrap;
}
.heading-links a {
	padding: 10px 13px;
	border: 1px solid var(--cloud-line, #e1e6df);
	background: var(--cloud-surface, #fff);
	border-radius: 9px;
	font-size: 13px;
}
.heading-links a:hover {
	background: var(--cloud-soft, #e8f1ec);
}
.learning-notice {
	border: 1px solid #d9e5dd;
	border-radius: 10px;
	background: #eef4ef;
	padding: 14px 16px;
	font-size: 12px;
}
.learning-notice summary {
	cursor: pointer;
	color: #41604f;
}
.learning-notice summary::marker {
	color: var(--cloud-primary, #277566);
}
.notice-mark {
	display: inline-flex;
	justify-content: center;
	align-items: center;
	width: 15px;
	height: 15px;
	margin: 0 7px 0 3px;
	border: 1px solid currentColor;
	border-radius: 50%;
	font-size: 10px;
	font-weight: 600;
}
.notice-more {
	margin-left: 12px;
	color: var(--cloud-muted, #6d7974);
}
.learning-notice p {
	margin: 12px 0 0;
	color: #5d7064;
	line-height: 1.8;
}
.learning-page a:focus-visible,
.learning-page button:focus-visible,
.learning-notice summary:focus-visible {
	outline: 2px solid var(--cloud-primary, #277566);
	outline-offset: 3px;
}
.learning-toolbar {
	display: flex;
	align-items: center;
	gap: 12px;
	margin: 20px 0;
}
.learning-toolbar .el-input {
	max-width: 450px;
	min-width: 0;
}
.learning-grid {
	display: grid;
	grid-template-columns: 245px minmax(0, 1fr);
	gap: 18px;
}
.learning-card {
	background: var(--cloud-surface, #fff);
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 12px;
	padding: 22px;
	min-width: 0;
}
h2 {
	font-size: 18px;
	font-weight: 600;
	margin: 0;
}
.course-list-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	padding-bottom: 14px;
	margin-bottom: 6px;
	border-bottom: 1px solid var(--cloud-line, #e1e6df);
	font-size: 15px;
}
.course-sidebar {
	align-self: start;
	padding: 20px 16px;
}
.course-semester {
	font-size: 11px;
	color: var(--cloud-accent, #ba7657);
	margin-bottom: 6px;
}
.course-material-count {
	font-size: 12px;
	color: var(--cloud-muted, #6d7974);
	margin-top: 8px;
}
.course-actions {
	display: flex;
	gap: 8px;
	flex-wrap: wrap;
}
.course-actions :deep(.el-button + .el-button) {
	margin-left: 0;
}
small {
	color: var(--cloud-muted, #6d7974);
	font-size: 12px;
	overflow-wrap: anywhere;
}
.course-button,
.file-picker button {
	display: block;
	width: 100%;
	text-align: left;
	padding: 12px;
	border-radius: 8px;
	margin-top: 8px;
	overflow-wrap: anywhere;
	border: 1px solid transparent;
	transition: background 140ms ease, border-color 140ms ease;
}
.course-button strong {
	font-size: 13px;
	font-weight: 600;
}
.course-button span {
	display: block;
	color: var(--cloud-muted, #6d7974);
	font-size: 12px;
	margin-top: 5px;
}
.active {
	background: var(--cloud-soft, #e8f1ec);
	color: var(--cloud-primary-dark, #1e5e52);
	border-color: #d5e4da;
}
.course-button:hover,
.file-picker button:hover {
	background: #f1f5ef;
}
.course-button.active:hover {
	background: #dcebe1;
}
.file-picker button:disabled {
	opacity: 0.45;
	cursor: not-allowed;
}
.material-row {
	padding: 18px 0;
	border-bottom: 1px solid var(--cloud-line, #e1e6df);
	overflow-wrap: anywhere;
}
.material-row p {
	color: var(--cloud-muted, #6d7974);
	margin: 6px 0;
	font-size: 12px;
}
.material-row strong {
	font-size: 14px;
	font-weight: 600;
}
.material-actions {
	display: flex;
	align-items: center;
	gap: 12px;
	margin-top: 12px;
	flex-wrap: wrap;
	font-size: 12px;
}
.material-actions :deep(.el-button + .el-button) {
	margin-left: 0;
}
.learning-search {
	margin-top: 28px;
	padding: 20px;
	border-radius: 10px;
	background: #f6f7f2;
	border: 1px solid #e6e9df;
}
.learning-search h2 {
	font-size: 16px;
}
.learning-search p {
	font-size: 12px;
	margin-top: 8px;
}
.learning-search .learning-toolbar {
	margin-bottom: 0;
}
.citation-card {
	margin-top: 16px;
	padding: 16px;
	background: #faf8f3;
	border-left: 3px solid var(--cloud-accent, #ba7657);
	border-radius: 0 8px 8px 0;
	font-size: 13px;
}
.citation-card p {
	white-space: pre-wrap;
	overflow-wrap: anywhere;
	margin-top: 8px;
	line-height: 1.7;
}
.file-picker {
	max-height: 260px;
	overflow: auto;
	min-height: 80px;
	border: 1px solid var(--cloud-line, #e1e6df);
	border-radius: 8px;
}
@media (max-width: 760px) {
	.learning-grid {
		grid-template-columns: 1fr;
	}
	.learning-heading,
	.section-heading,
	.learning-toolbar {
		flex-wrap: wrap;
	}
	.learning-page {
		padding: 20px 12px 80px;
	}
	.learning-card {
		padding: 18px 16px;
	}
	.learning-heading {
		gap: 14px;
	}
	.learning-heading h1 {
		font-size: 25px;
	}
	.learning-heading > div {
		width: 100%;
	}
	.notice-more {
		display: block;
		margin: 6px 0 0 20px;
	}
	.learning-toolbar .el-input {
		flex: 1 1 180px;
	}
	.learning-search {
		padding: 16px 12px;
	}
	.material-actions {
		gap: 10px;
	}
}
@media (prefers-reduced-motion: reduce) {
	.course-button,
	.file-picker button {
		transition: none;
	}
}
</style>
