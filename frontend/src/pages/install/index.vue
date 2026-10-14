<!-- Login layout adapted from arsh342/careercompass (MIT); see public/licenses/careercompass.txt. -->
<template>
	<main class="career-login-page career-install-page">
		<div class="career-login-card">
			<section class="career-login-art" aria-label="学生云盘">
				<div class="career-login-brand">
					<CloudArrowUpIcon aria-hidden="true" />
					<span>学生云盘</span>
				</div>
				<div class="career-login-stage">
					<LoginCharacters
						:is-typing="isTyping"
						:show-password="passwordVisible"
						:password-length="formData.password.length"
						:paused="motionPaused"
					/>
				</div>
				<div class="career-login-art-footer">
					<span>首次设置</span>
					<button
						type="button"
						:aria-pressed="motionPaused"
						@click="motionPaused = !motionPaused"
					>
						{{ motionPaused ? '播放动画' : '暂停动画' }}
					</button>
				</div>
			</section>

			<section
				class="career-login-content"
				aria-labelledby="career-install-title"
			>
				<div class="career-login-form-wrap">
					<div class="career-login-brand career-login-mobile-brand">
						<CloudArrowUpIcon aria-hidden="true" />
						<span>学生云盘</span>
					</div>
					<header class="career-login-intro">
						<h1 id="career-install-title">初始化云盘</h1>
						<p>设置站点名称，创建全局管理员账号</p>
					</header>

					<p v-if="checkingInstall" class="career-install-status" role="status">
						正在检查初始化状态…
					</p>
					<div v-if="feedback" class="career-login-feedback" role="alert">
						{{ feedback }}
						<button
							v-if="statusError"
							type="button"
							class="career-install-retry"
							:disabled="checkingInstall"
							@click="checkInstallStatus"
						>
							重新检查
						</button>
					</div>

					<el-form
						ref="installFormRef"
						:rules="formRules"
						:model="formData"
						:disabled="formDisabled"
						:validate-on-rule-change="false"
						hide-required-asterisk
						class="career-login-form"
						label-position="top"
						@submit.prevent="submitForm"
					>
						<el-form-item
							prop="siteName"
							label="站点名称"
							for="career-install-site"
						>
							<el-input
								id="career-install-site"
								v-model.trim="formData.siteName"
								placeholder="例如：学生云盘"
								autocomplete="organization"
								@focus="isTyping = true"
								@blur="isTyping = false"
							/>
						</el-form-item>
						<el-form-item
							prop="username"
							label="全局管理员账号"
							for="career-install-account"
						>
							<el-input
								id="career-install-account"
								v-model.trim="formData.username"
								placeholder="设置管理员登录账号"
								maxlength="100"
								autocomplete="username"
								:spellcheck="false"
								@focus="isTyping = true"
								@blur="isTyping = false"
							/>
						</el-form-item>
						<el-form-item
							prop="password"
							label="管理员密码"
							for="career-install-password"
						>
							<el-input
								id="career-install-password"
								v-model="formData.password"
								:type="passwordVisible ? 'text' : 'password'"
								placeholder="设置 6–128 位密码"
								maxlength="128"
								autocomplete="new-password"
								@keyup="updateCapsLock"
								@blur="capsLock = false"
							>
								<template #suffix>
									<button
										type="button"
										class="career-login-password-toggle"
										:aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
										:aria-pressed="passwordVisible"
										:disabled="formDisabled"
										@click="passwordVisible = !passwordVisible"
									>
										<EyeSlashIcon v-if="passwordVisible" aria-hidden="true" />
										<EyeIcon v-else aria-hidden="true" />
									</button>
								</template>
							</el-input>
						</el-form-item>
						<p v-if="capsLock" class="career-login-note" role="status">
							大写锁定已开启
						</p>
						<button
							type="submit"
							class="career-login-submit"
							:disabled="formDisabled"
							:aria-busy="loading || checkingInstall"
						>
							<span class="career-login-submit-label">{{ submitLabel }}</span>
							<span class="career-login-submit-hover" aria-hidden="true">
								{{ submitLabel }}
								<span
									v-if="loading || checkingInstall"
									class="career-login-spinner"
								/>
								<ArrowRightIcon v-else />
							</span>
						</button>
					</el-form>

					<p class="career-login-remember-note">
						此账号用于管理整个站点，普通用户请在初始化后注册。
					</p>
					<p v-if="installed || installSucceeded" class="career-install-status">
						站点已初始化，<router-link to="/login">前往登录</router-link>。
					</p>
				</div>
			</section>
		</div>
	</main>
</template>

<script setup>
import {
	ArrowRightIcon,
	CloudArrowUpIcon,
	EyeIcon,
	EyeSlashIcon,
} from '@heroicons/vue/24/outline'
import { installReq, installStatusReq } from '~/api/home/install'
import LoginCharacters from '~/components/auth/LoginCharacters.vue'
import MessageBox from '~/components/messageBox/messageBox'
import '~/styles/login.css'

useTitle('学生云盘 | 初始化')

const router = useRouter()
const formData = reactive({ siteName: '学生云盘', username: '', password: '' })
const formRules = {
	siteName: [
		{
			required: true,
			whitespace: true,
			message: '请输入站点名称',
			trigger: ['change', 'blur'],
		},
	],
	username: [
		{
			required: true,
			whitespace: true,
			message: '请输入管理员账号',
			trigger: ['change', 'blur'],
		},
		{
			max: 100,
			message: '管理员账号不能超过 100 个字符',
			trigger: ['change', 'blur'],
		},
	],
	password: [
		{
			required: true,
			whitespace: true,
			message: '请输入管理员密码',
			trigger: ['change', 'blur'],
		},
		{
			min: 6,
			max: 128,
			message: '密码长度为 6–128 位',
			trigger: ['change', 'blur'],
		},
	],
}
const installFormRef = ref()
const loading = ref(false)
const checkingInstall = ref(true)
const installStatusChecked = ref(false)
const installed = ref(false)
const installSucceeded = ref(false)
const statusError = ref('')
const formError = ref('')
const passwordVisible = ref(false)
const isTyping = ref(false)
const capsLock = ref(false)
const motionPaused = ref(
	window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
)
const formDisabled = computed(
	() =>
		checkingInstall.value ||
		loading.value ||
		installSucceeded.value ||
		!installStatusChecked.value ||
		installed.value
)
const feedback = computed(() => statusError.value || formError.value)
const submitLabel = computed(() => {
	if (checkingInstall.value) return '正在检查…'
	if (installSucceeded.value || installed.value) return '初始化完成'
	return loading.value ? '正在初始化…' : '初始化云盘'
})
let disposed = false
let statusRequest = 0

const updateCapsLock = (event) => {
	capsLock.value = Boolean(event.getModifierState?.('CapsLock'))
}

const checkInstallStatus = async () => {
	if (disposed || loading.value || installSucceeded.value) return
	const request = ++statusRequest
	checkingInstall.value = true
	installStatusChecked.value = false
	statusError.value = ''
	try {
		const response = await installStatusReq()
		if (disposed || request !== statusRequest) return
		if (typeof response?.data !== 'boolean')
			throw new Error('Invalid initialization status')
		installed.value = response.data
		installStatusChecked.value = true
		if (installed.value) await router.replace('/login')
	} catch {
		if (!disposed && request === statusRequest) {
			statusError.value = installed.value
				? '站点已初始化，请点击下方链接前往登录。'
				: '无法检查初始化状态，请检查后端连接后重试。'
		}
	} finally {
		if (!disposed && request === statusRequest) checkingInstall.value = false
	}
}

const submitForm = async () => {
	if (disposed || formDisabled.value || !installFormRef.value) return
	// 在异步校验前锁定表单，避免点击或回车触发重复初始化。
	loading.value = true
	formError.value = ''
	try {
		const valid = await installFormRef.value.validate().catch(() => false)
		if (disposed) return
		if (!valid) {
			formError.value = '请检查并填写初始化信息。'
			return
		}
		await installReq({
			siteName: formData.siteName.trim(),
			username: formData.username.trim(),
			// 密码保留用户实际输入，不静默删除前后空格。
			password: formData.password,
		})
		if (disposed) return
		installSucceeded.value = true
		installed.value = true
		formData.password = ''
		passwordVisible.value = false
		capsLock.value = false
		await MessageBox.confirm('系统初始化成功，点击确定前往登录。', '提示', {
			confirmButtonText: '确定',
			showCancelButton: false,
			showCloseButton: false,
			closeOnClickModal: false,
			closeOnPressEscape: false,
			type: 'success',
		})
		if (!disposed) await router.replace('/login')
	} catch (error) {
		if (!disposed) {
			formError.value = installSucceeded.value
				? '初始化已完成，请点击下方链接前往登录。'
				: error?.response?.data?.msg ||
				  error?.data?.msg ||
				  '初始化失败，请检查后端连接后重试。'
		}
	} finally {
		if (!disposed) loading.value = false
	}
}

onBeforeMount(checkInstallStatus)
onBeforeUnmount(() => {
	disposed = true
	statusRequest++
})
</script>

<style scoped>
.career-install-page .career-login-submit:disabled .career-login-submit-label {
	opacity: 1;
}

.career-install-status {
	margin: 16px 0;
	color: #686b73;
	font-size: 12px;
	line-height: 1.7;
	text-align: center;
}

.career-install-status a,
.career-install-retry {
	color: inherit;
	text-decoration: underline;
	text-underline-offset: 3px;
}

.career-install-retry {
	display: block;
	margin-top: 8px;
	border: 0;
	padding: 0;
	background: transparent;
	cursor: pointer;
}
</style>
