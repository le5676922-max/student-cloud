<!-- Login layout adapted from arsh342/careercompass (MIT); see public/licenses/careercompass.txt. -->
<template>
	<main class="career-login-page">
		<div class="career-login-card">
			<section class="career-login-art" aria-label="学生云盘">
				<router-link
					to="/"
					class="career-login-brand"
					aria-label="学生云盘首页"
				>
					<CloudArrowUpIcon aria-hidden="true" />
					<span>学生云盘</span>
				</router-link>
				<div class="career-login-stage">
					<LoginCharacters
						:is-typing="isTyping"
						:show-password="passwordVisible"
						:password-length="formData.password.length"
						:password-focused="passwordFocused"
						:status="
							loginSucceeded
								? 'success'
								: loginError
								? 'error'
								: loading
								? 'busy'
								: 'idle'
						"
						:paused="motionPaused"
					/>
				</div>
				<div class="career-login-art-footer">
					<router-link to="/">返回云盘</router-link>
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
				aria-labelledby="career-login-title"
			>
				<div class="career-login-form-wrap">
					<router-link
						to="/"
						class="career-login-brand career-login-mobile-brand"
						aria-label="学生云盘首页"
					>
						<CloudArrowUpIcon aria-hidden="true" /><span>学生云盘</span>
					</router-link>
					<header class="career-login-intro">
						<h1 id="career-login-title">欢迎回来!</h1>
						<p>登录学生云盘，继续整理你的资料</p>
					</header>
					<el-form
						ref="loginFormRef"
						:rules="formRules"
						:model="formData"
						:disabled="loading"
						:validate-on-rule-change="false"
						hide-required-asterisk
						class="career-login-form"
						label-position="top"
						@submit.prevent="submitForm"
					>
						<el-form-item
							prop="username"
							label="邮箱 / 账号ID"
							for="career-login-username"
						>
							<el-input
								id="career-login-username"
								ref="usernameInput"
								v-model.trim="formData.username"
								placeholder="邮箱、账号ID或原有账号"
								maxlength="254"
								autocomplete="username"
								:spellcheck="false"
								@focus="isTyping = true"
								@blur="isTyping = false"
							/>
						</el-form-item>
						<el-form-item
							prop="password"
							label="密码"
							for="career-login-password"
						>
							<el-input
								id="career-login-password"
								ref="passwordInput"
								v-model="formData.password"
								:type="passwordVisible ? 'text' : 'password'"
								placeholder="••••••••"
								autocomplete="current-password"
								maxlength="128"
								@keyup="updateCapsLock"
								@focus="passwordFocused = true"
								@blur="handlePasswordBlur"
							>
								<template #suffix>
									<button
										type="button"
										class="career-login-password-toggle"
										:aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
										:aria-pressed="passwordVisible"
										:disabled="loading"
										@mousedown.prevent
										@click="togglePassword"
									>
										<EyeSlashIcon v-if="passwordVisible" /><EyeIcon v-else />
									</button>
								</template>
							</el-input>
						</el-form-item>
						<p v-if="capsLock" class="career-login-note" role="status">
							大写锁定已开启
						</p>

						<el-form-item
							v-if="loginVerifyMode === '2fa'"
							prop="verifyCode"
							label="认证验证码"
							for="career-login-verify"
						>
							<el-input
								id="career-login-verify"
								v-model.trim="formData.verifyCode"
								placeholder="请输入双因素认证验证码"
								autocomplete="one-time-code"
								inputmode="numeric"
								maxlength="6"
							/>
						</el-form-item>
						<el-form-item
							v-else-if="loginVerifyMode === 'image'"
							prop="verifyCode"
							label="图片验证码"
							for="career-login-verify"
						>
							<div class="career-login-captcha-row">
								<el-input
									id="career-login-verify"
									v-model.trim="formData.verifyCode"
									placeholder="请输入验证码"
									autocomplete="off"
									maxlength="10"
								/>
								<button
									type="button"
									class="career-login-captcha"
									aria-label="刷新图片验证码"
									:disabled="loading || captchaLoading"
									@click="loadLoginVerifyCodeImgData"
								>
									<img
										v-if="loginVerifyCodeImgData.imgBase64"
										:src="loginVerifyCodeImgData.imgBase64"
										alt="图片验证码"
									/>
									<span
										v-if="captchaLoading"
										class="career-login-captcha-status"
										>加载中…</span
									>
									<span v-else-if="!loginVerifyCodeImgData.imgBase64"
										>重新获取</span
									>
								</button>
							</div>
						</el-form-item>

						<div class="career-login-options">
							<el-checkbox v-model="remember">记住账号</el-checkbox>
							<button
								type="button"
								class="career-login-link"
								:disabled="loading"
								@click="entryDialog = 'forgot'"
							>
								忘记密码？
							</button>
						</div>
						<p
							v-if="bootstrapChecking"
							class="career-login-connection"
							role="status"
						>
							正在连接登录服务…
						</p>
						<div
							v-else-if="bootstrapError || verificationError || captchaError"
							class="career-login-feedback"
							role="alert"
						>
							<p>{{ bootstrapError || verificationError || captchaError }}</p>
							<button
								v-if="bootstrapError && !entryAddressInvalid"
								type="button"
								@click="initializeLogin"
							>
								重新连接
							</button>
							<button
								v-else-if="verificationError"
								type="button"
								@click="loadLoginVerifyMode"
							>
								重试
							</button>
							<button
								v-else-if="captchaError"
								type="button"
								@click="loadLoginVerifyCodeImgData"
							>
								重新获取验证码
							</button>
						</div>
						<p v-if="loginError" class="career-login-feedback" role="alert">
							{{ loginError }}
						</p>
						<div
							v-if="navigationError"
							class="career-login-feedback"
							role="alert"
						>
							<p>{{ navigationError }}</p>
							<button
								type="button"
								class="career-login-link"
								:disabled="loading"
								@click="retryLoginNavigation"
							>
								重新进入
							</button>
						</div>
						<button
							type="submit"
							class="career-login-submit"
							:disabled="!canSubmit"
							:aria-busy="loading"
						>
							<span class="career-login-submit-label">{{ submitLabel }}</span>
							<span class="career-login-submit-hover" aria-hidden="true">
								{{ submitLabel }}<ArrowRightIcon v-if="canSubmit" /><span
									v-else
									v-show="loading"
									class="career-login-spinner"
								/>
							</span>
						</button>
					</el-form>
					<div class="career-login-signup">
						还没有账号？<button
							type="button"
							:disabled="loading"
							@click="registrationVisible = true"
						>
							注册账号
						</button>
					</div>
					<p class="career-login-note career-login-remember-note">
						记住账号只保存登录标识，不会保存密码。
					</p>
				</div>
			</section>
		</div>
		<PasswordResetDialog
			v-if="entryDialogVisible"
			v-model="entryDialogVisible"
			:initial-identifier="formData.username"
			@return-to-login="prefillRegisteredLogin"
		/>
		<RegistrationDialog
			v-if="registrationVisible"
			v-model="registrationVisible"
			:initial-identifier="formData.username"
			@return-to-login="prefillRegisteredLogin"
		/>
	</main>
</template>

<script setup>
import {
	checkLoginReq,
	loginReq,
	loginVerifyImgReq,
	loginVerifyModeReq,
	validateLoginEntryReq,
} from '~/api/home/user'
import { installStatusReq } from '~/api/home/install'
import { isNavigationFailure, NavigationFailureType } from 'vue-router'
import {
	ArrowRightIcon,
	CloudArrowUpIcon,
	EyeIcon,
	EyeSlashIcon,
} from '@heroicons/vue/24/outline'
import LoginCharacters from '~/components/auth/LoginCharacters.vue'
import RegistrationDialog from '~/components/auth/RegistrationDialog.vue'
import PasswordResetDialog from '~/components/auth/PasswordResetDialog.vue'
import {
	loadLoginPreference,
	saveLoginPreference,
} from '~/utils/login-preference.mjs'
import {
	isAuthenticatedUser,
	isPublicRoute,
	loginDestination,
} from '~/utils/auth-navigation.mjs'
import '~/styles/login.css'

const route = useRoute()
const router = useRouter()
useTitle('学生云盘 | 登录')
const loading = ref(false)
const bootstrapChecking = ref(true)
const bootstrapError = ref('')
const entryAddressInvalid = ref(false)
const verificationChecking = ref(false)
const verificationError = ref('')
const captchaLoading = ref(false)
const captchaError = ref('')
const loginSucceeded = ref(false)
const loginError = ref('')
const navigationError = ref('')
const authenticatedDestination = ref('')
const passwordVisible = ref(false)
const passwordFocused = ref(false)
const usernameInput = ref()
const passwordInput = ref()
const handlePasswordBlur = () => {
	passwordFocused.value = false
	capsLock.value = false
}
const isTyping = ref(false)
const capsLock = ref(false)
let disposed = false
let pageEpoch = 0
let verifyRequest = 0
let captchaRequest = 0
const lifetime = new AbortController()
const requestOptions = {
	signal: lifetime.signal,
	timeout: 15000,
	showDefaultMsg: false,
}
const failureMessage = (error, fallback) => {
	const status = error?.response?.status ?? error?.status
	if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT')
		return '连接超时，请稍后重试。'
	if (!error?.response && !error?.data && error?.code)
		return '暂时无法连接服务，请检查网络后重试。'
	if (status >= 500) return '登录服务暂时不可用，请稍后重试。'
	return error?.response?.data?.msg || error?.data?.msg || fallback
}
const focusInvalidField = async () => {
	await nextTick()
	if (disposed) return
	const input = document.querySelector('.career-login-form .is-error input')
	input?.focus()
	input?.scrollIntoView({ block: 'nearest', behavior: 'auto' })
}
const togglePassword = () => {
	passwordVisible.value = !passwordVisible.value
	passwordInput.value?.focus()
}
const motionPaused = ref(
	window.matchMedia('(prefers-reduced-motion: reduce)').matches
)
const entryDialog = ref('')
const registrationVisible = ref(false)
const entryDialogVisible = computed({
	get: () => Boolean(entryDialog.value),
	set: (visible) => {
		if (!visible) entryDialog.value = ''
	},
})
const submitLabel = computed(() =>
	loginSucceeded.value
		? navigationError.value
			? '已登录'
			: '登录成功，正在进入'
		: loading.value
		? '正在登录…'
		: bootstrapChecking.value
		? '正在连接…'
		: verificationChecking.value || captchaLoading.value
		? '请稍候…'
		: '登录'
)
const updateCapsLock = (event) => {
	capsLock.value = Boolean(event.getModifierState?.('CapsLock'))
}

let loginStorage
try {
	loginStorage = window.localStorage
} catch {
	// The form still renders when browser storage access is unavailable.
}
const loginPreference = loadLoginPreference(loginStorage)
const remember = ref(loginPreference.remember)
const loginFormRef = ref()
const loginEntry = ref('')
const loginEntryValid = ref(false)
const canSubmit = computed(
	() =>
		!loading.value &&
		!loginSucceeded.value &&
		!bootstrapChecking.value &&
		!bootstrapError.value &&
		loginEntryValid.value &&
		!verificationChecking.value &&
		!verificationError.value &&
		!captchaLoading.value &&
		!captchaError.value &&
		(loginVerifyMode.value !== 'image' ||
			Boolean(formData.value.verifyCodeUUID))
)
const formData = ref({
	username: loginPreference.username,
	password: '',
	verifyCode: '',
	verifyCodeUUID: '',
})
const prefillRegisteredLogin = (identifier) => {
	if (identifier) formData.value.username = identifier
	formData.value.password = ''
	formData.value.verifyCode = ''
	passwordVisible.value = false
	loginError.value = ''
	loginFormRef.value?.clearValidate()
	nextTick(() => {
		if (!disposed) passwordInput.value?.focus()
	})
}
const formRules = computed(() => ({
	username: [
		{
			required: true,
			message: '请输入邮箱或账号 ID',
			trigger: ['change', 'blur'],
		},
	],
	password: [
		{ required: true, message: '密码不能为空', trigger: ['change', 'blur'] },
	],
	verifyCode: [
		{
			required: ['image', '2fa'].includes(loginVerifyMode.value),
			message: '请输入验证码',
			trigger: 'blur',
		},
	],
}))

const navigationFailureMessage =
	'已登录，但页面未能打开。请重新进入，无需再次输入密码。'
const completeLoginNavigation = async (epoch) => {
	navigationError.value = ''
	try {
		const failure = await router.replace(authenticatedDestination.value)
		if (disposed || epoch !== pageEpoch) return
		if (
			isNavigationFailure(failure) &&
			!isNavigationFailure(failure, NavigationFailureType.duplicated)
		)
			throw new Error('Navigation did not complete')
		// A guard redirect can resolve successfully while returning to this page.
		// Do not leave the form locked in its authenticated transition state.
		if (isPublicRoute(router.currentRoute?.value.path || '')) {
			loginSucceeded.value = false
			authenticatedDestination.value = ''
			loginError.value = '未能确认登录状态，请重新登录或稍后重试。'
			await loadLoginVerifyMode()
			return
		}
	} catch {
		if (!disposed && epoch === pageEpoch)
			navigationError.value = navigationFailureMessage
	}
}
const retryLoginNavigation = async () => {
	if (
		disposed ||
		loading.value ||
		!loginSucceeded.value ||
		!authenticatedDestination.value
	)
		return
	const epoch = pageEpoch
	loading.value = true
	try {
		await completeLoginNavigation(epoch)
	} finally {
		if (!disposed && epoch === pageEpoch) loading.value = false
	}
}

const submitForm = async () => {
	if (!canSubmit.value || !loginFormRef.value || disposed) return
	const epoch = pageEpoch
	loading.value = true
	loginError.value = ''
	navigationError.value = ''
	try {
		try {
			await loginFormRef.value.validate()
		} catch {
			if (!disposed && epoch === pageEpoch) {
				loginError.value = '请检查标出的登录信息。'
				loading.value = false
				await focusInvalidField()
			}
			return
		}
		if (disposed || epoch !== pageEpoch) return
		const payload = { ...formData.value }
		const response = await loginReq(payload, loginEntry.value, requestOptions)
		if (disposed || epoch !== pageEpoch) return
		if (!response.data?.token) throw new Error('登录响应缺少凭据')
		try {
			window.localStorage.setItem(
				'tongligong-student-cloud-token',
				response.data.token
			)
		} catch {
			loginError.value =
				'浏览器无法保存登录状态，请允许此站点使用本地存储后重试。'
			return
		}
		saveLoginPreference(loginStorage, remember.value, payload.username)
		loginSucceeded.value = true
		authenticatedDestination.value = loginDestination(
			response.data,
			route.query.redirect
		)
		formData.value.password = ''
		loginFormRef.value?.clearValidate()
		ElMessage.success('登录成功')
		await completeLoginNavigation(epoch)
	} catch (error) {
		if (disposed || epoch !== pageEpoch || error?.code === 'ERR_CANCELED')
			return
		if (loginSucceeded.value) {
			navigationError.value = navigationFailureMessage
			return
		}
		loginSucceeded.value = false
		loginError.value = failureMessage(error, '登录失败，请检查账号密码后重试。')
		if (loginVerifyMode.value === 'image') await loadLoginVerifyCodeImgData()
	} finally {
		if (!disposed && epoch === pageEpoch) loading.value = false
	}
}

const loginVerifyMode = ref('')
const loginVerifyCodeImgData = ref({})
const loadLoginVerifyCodeImgData = async () => {
	if (disposed) return
	const request = ++captchaRequest
	formData.value.verifyCode = ''
	formData.value.verifyCodeUUID = ''
	loginVerifyCodeImgData.value = {}
	captchaLoading.value = true
	captchaError.value = ''
	try {
		const response = await loginVerifyImgReq(requestOptions)
		if (disposed || request !== captchaRequest) return
		if (!response.data?.uuid || !response.data?.imgBase64)
			throw new Error('验证码暂时不可用')
		loginVerifyCodeImgData.value = response.data
		formData.value.verifyCodeUUID = response.data.uuid
	} catch (error) {
		if (
			!disposed &&
			request === captchaRequest &&
			error?.code !== 'ERR_CANCELED'
		)
			captchaError.value = failureMessage(error, '验证码加载失败，请重新获取。')
	} finally {
		if (!disposed && request === captchaRequest) captchaLoading.value = false
	}
}
const loadLoginVerifyMode = async () => {
	if (disposed || !loginEntryValid.value) return
	const request = ++verifyRequest
	verificationChecking.value = true
	verificationError.value = ''
	try {
		const response = await loginVerifyModeReq(
			formData.value.username,
			requestOptions
		)
		if (disposed || request !== verifyRequest) return
		if (!['', 'off', 'image', '2fa'].includes(response.data))
			throw new Error('未知的登录验证方式')
		const oldMode = loginVerifyMode.value
		loginVerifyMode.value = response.data
		if (oldMode !== response.data) {
			captchaRequest++
			formData.value.verifyCode = ''
			formData.value.verifyCodeUUID = ''
			captchaLoading.value = false
			captchaError.value = ''
		}
		if (response.data === 'image') await loadLoginVerifyCodeImgData()
	} catch (error) {
		if (
			!disposed &&
			request === verifyRequest &&
			error?.code !== 'ERR_CANCELED'
		)
			verificationError.value = failureMessage(
				error,
				'无法确认登录验证方式，请重试。'
			)
	} finally {
		if (!disposed && request === verifyRequest)
			verificationChecking.value = false
	}
}
const debouncedVerifyMode = useDebounceFn(loadLoginVerifyMode, 300)
watch(
	() => formData.value.password,
	() => {
		loginError.value = ''
	}
)
watch(
	() => formData.value.username,
	() => {
		verifyRequest++
		loginError.value = ''
		if (loginEntryValid.value) {
			verificationChecking.value = true
			debouncedVerifyMode()
		}
	}
)
const initializeLogin = async () => {
	if (disposed) return
	const epoch = ++pageEpoch
	verifyRequest++
	captchaRequest++
	verificationChecking.value = false
	verificationError.value = ''
	captchaLoading.value = false
	captchaError.value = ''
	formData.value.verifyCode = ''
	formData.value.verifyCodeUUID = ''
	loginVerifyCodeImgData.value = {}
	loginError.value = ''
	navigationError.value = ''
	authenticatedDestination.value = ''
	bootstrapChecking.value = true
	bootstrapError.value = ''
	entryAddressInvalid.value = false
	loginEntryValid.value = false
	loading.value = false
	loginSucceeded.value = false
	const entry = route.params.entry
	loginEntry.value = typeof entry === 'string' ? entry.trim() : ''
	let bootstrapStage = 'install'
	try {
		const installation = await installStatusReq(requestOptions)
		if (disposed || epoch !== pageEpoch) return
		if (!installation.data) {
			await router.replace('/install')
			return
		}
		bootstrapStage = 'entry'
		await validateLoginEntryReq(loginEntry.value, requestOptions)
		if (disposed || epoch !== pageEpoch) return
		loginEntryValid.value = true
		bootstrapStage = 'identity'
		const identity = await checkLoginReq(requestOptions)
		if (disposed || epoch !== pageEpoch) return
		if (isAuthenticatedUser(identity.data)) {
			loginSucceeded.value = true
			authenticatedDestination.value = loginDestination(
				identity.data,
				route.query.redirect
			)
			await completeLoginNavigation(epoch)
			return
		}
		await loadLoginVerifyMode()
	} catch (error) {
		if (disposed || epoch !== pageEpoch || error?.code === 'ERR_CANCELED')
			return
		const status = error?.response?.status ?? error?.status
		entryAddressInvalid.value =
			bootstrapStage === 'entry' &&
			(status === 403 ||
				status === 404 ||
				(status === 200 && error?.data?.code !== undefined))
		bootstrapError.value = entryAddressInvalid.value
			? '登录地址不正确，请使用管理员提供的完整地址。'
			: failureMessage(error, '暂时无法连接登录服务，请重试。')
	} finally {
		if (!disposed && epoch === pageEpoch) bootstrapChecking.value = false
	}
}
watch(() => route.params.entry, initializeLogin)
onBeforeUnmount(() => {
	disposed = true
	pageEpoch++
	verifyRequest++
	captchaRequest++
	lifetime.abort()
})
onMounted(initializeLogin)
</script>
