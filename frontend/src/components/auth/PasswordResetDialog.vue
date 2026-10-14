<template>
	<el-dialog
		v-model="visible"
		title="找回密码"
		class="career-login-entry-dialog career-registration-dialog"
		width="500px"
		:close-on-click-modal="!busy"
		:close-on-press-escape="!busy"
		:show-close="!busy"
		append-to-body
		@opened="focusField('email')"
	>
		<section
			v-if="completed"
			class="career-registration-result"
			aria-live="polite"
		>
			<h2>邮箱验证已完成</h2>
			<p>{{ completed }}</p>
			<p class="career-registration-hint">
				若仍无法登录，请确认使用注册邮箱，或联系空间管理员。
			</p>
			<button type="button" class="career-login-submit" @click="returnToLogin">
				返回登录
			</button>
		</section>
		<template v-else>
			<p class="career-registration-intro">
				使用账号绑定的邮箱验证身份。重置后，原有登录会话将退出。
			</p>
			<p v-if="configLoading" class="career-registration-hint" role="status">
				正在检查邮件服务…
			</p>
			<el-alert
				v-else-if="configError || !config.enabled || !config.mailAvailable"
				:title="configError || '邮箱找回暂不可用，请联系管理员。'"
				type="warning"
				:closable="false"
				show-icon
			/>
			<el-button
				v-if="configError"
				:loading="configLoading"
				@click="loadConfig"
			>
				重新检查
			</el-button>
			<el-form
				id="password-reset-form"
				ref="formRef"
				:model="draft"
				:rules="rules"
				:disabled="busy"
				class="career-login-form career-registration-form"
				label-position="top"
				hide-required-asterisk
				@submit.prevent="submit"
			>
				<el-form-item label="绑定邮箱" prop="email" for="password-reset-email">
					<el-input
						id="password-reset-email"
						v-model="draft.email"
						maxlength="254"
						placeholder="注册时使用的邮箱"
						autocomplete="email"
						inputmode="email"
						:spellcheck="false"
					/>
				</el-form-item>
				<el-form-item
					label="邮箱验证码"
					prop="emailCode"
					for="password-reset-emailCode"
				>
					<div class="career-registration-code-row">
						<el-input
							id="password-reset-emailCode"
							v-model.trim="draft.emailCode"
							maxlength="6"
							placeholder="6 位验证码"
							autocomplete="one-time-code"
							inputmode="numeric"
						/>
						<el-button
							:loading="sending"
							:disabled="!available || busy || cooldown > 0"
							@click="sendCode"
						>
							{{ cooldown > 0 ? `${cooldown} 秒后重发` : '发送验证码' }}
						</el-button>
					</div>
				</el-form-item>
				<p v-if="codeSent" class="career-registration-hint" role="status">
					邮件已发送，请查收收件箱或垃圾邮件。
				</p>
				<el-form-item
					label="新密码"
					prop="password"
					for="password-reset-password"
				>
					<el-input
						id="password-reset-password"
						v-model="draft.password"
						type="password"
						show-password
						maxlength="128"
						placeholder="8 至 128 位"
						autocomplete="new-password"
					/>
				</el-form-item>
				<el-form-item
					label="确认新密码"
					prop="confirmPassword"
					for="password-reset-confirmPassword"
				>
					<el-input
						id="password-reset-confirmPassword"
						v-model="draft.confirmPassword"
						type="password"
						show-password
						maxlength="128"
						placeholder="再次输入新密码"
						autocomplete="new-password"
					/>
				</el-form-item>
				<p v-if="feedback" class="career-login-feedback" role="alert">
					{{ feedback }}
				</p>
			</el-form>
		</template>
		<template v-if="!completed" #footer>
			<div class="career-registration-actions">
				<button
					type="button"
					class="career-registration-back"
					:disabled="busy"
					@click="returnToLogin"
				>
					返回登录
				</button>
				<button
					type="submit"
					form="password-reset-form"
					class="career-login-submit"
					:disabled="!available || busy"
					:aria-busy="submitting"
				>
					{{ submitting ? '正在重置…' : '重置密码' }}
				</button>
			</div>
		</template>
	</el-dialog>
</template>

<script setup>
import {
	computed,
	nextTick,
	onBeforeUnmount,
	onMounted,
	reactive,
	ref,
	watch,
} from 'vue'
import {
	passwordResetConfigReq,
	passwordResetEmailCodeReq,
	passwordResetReq,
} from '~/api/home/user'
import {
	buildPasswordResetPayload,
	passwordResetErrors,
} from '~/utils/password-reset.mjs'
import {
	createRegistrationCodeCooldown,
	normalizeRegistrationEmail,
	registrationCodeThrottle,
	registrationEmailError,
} from '~/utils/registration.mjs'
import { createLatestRequest } from '~/utils/latest-request.mjs'

const props = defineProps({
	modelValue: Boolean,
	initialIdentifier: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'return-to-login'])
const formRef = ref()
const config = ref({ enabled: false, mailAvailable: false })
const configLoading = ref(true)
const configError = ref('')
const sending = ref(false)
const submitting = ref(false)
const validating = ref(false)
const busy = computed(
	() => sending.value || submitting.value || validating.value
)
const available = computed(
	() =>
		!configLoading.value &&
		!configError.value &&
		config.value.enabled &&
		config.value.mailAvailable
)
const visible = computed({
	get: () => props.modelValue,
	set: (value) => {
		if (!busy.value) emit('update:modelValue', value)
	},
})
const draft = reactive({
	email: registrationEmailError(props.initialIdentifier)
		? ''
		: normalizeRegistrationEmail(props.initialIdentifier),
	emailCode: '',
	password: '',
	confirmPassword: '',
})
const fields = ['email', 'emailCode', 'password', 'confirmPassword']
const rules = computed(() =>
	Object.fromEntries(
		fields.map((field) => [
			field,
			[
				{
					trigger: 'blur',
					validator: (_rule, _value, callback) => {
						const error = passwordResetErrors(draft)[field]
						callback(error ? new Error(error) : undefined)
					},
				},
			],
		])
	)
)
const feedback = ref('')
const completed = ref('')
const codeSent = ref(false)
const cooldown = ref(0)
const cooldownState = createRegistrationCodeCooldown()
const configRequest = createLatestRequest()
const codeRequest = createLatestRequest()
const resetRequest = createLatestRequest()
let timer
let disposed = false
const failureMessage = (failure, fallback) =>
	failure?.response?.data?.msg || failure?.data?.msg || fallback

function refreshCooldown() {
	clearInterval(timer)
	cooldown.value = cooldownState.remaining(draft.email)
	if (cooldown.value > 0)
		timer = setInterval(() => {
			cooldown.value = cooldownState.remaining(draft.email)
			if (!cooldown.value) clearInterval(timer)
		}, 1000)
}
watch(
	() => normalizeRegistrationEmail(draft.email),
	() => {
		codeRequest.cancel()
		sending.value = false
		draft.emailCode = ''
		codeSent.value = false
		feedback.value = ''
		formRef.value?.clearValidate(['email', 'emailCode'])
		refreshCooldown()
	}
)
async function focusField(field) {
	await nextTick()
	if (disposed || !props.modelValue) return
	formRef.value?.$el?.querySelector(`#password-reset-${field}`)?.focus()
}
async function loadConfig() {
	if (disposed) return
	const request = configRequest.begin()
	configLoading.value = true
	configError.value = ''
	try {
		const response = await passwordResetConfigReq({
			signal: request.signal,
			timeout: 15000,
			showDefaultMsg: false,
		})
		if (!disposed && request.isCurrent()) config.value = response.data
	} catch (failure) {
		if (!disposed && request.isCurrent())
			configError.value = failureMessage(
				failure,
				'找回服务暂时无法连接，请稍后重试。'
			)
	} finally {
		if (!disposed && request.isCurrent()) configLoading.value = false
	}
}
async function sendCode() {
	if (!available.value || busy.value || cooldown.value > 0 || !formRef.value)
		return
	validating.value = true
	try {
		await formRef.value.validateField('email')
	} catch {
		await focusField('email')
		return
	} finally {
		validating.value = false
	}
	if (disposed || busy.value || !available.value) return
	const email = normalizeRegistrationEmail(draft.email)
	const request = codeRequest.begin()
	sending.value = true
	feedback.value = ''
	try {
		await passwordResetEmailCodeReq(email, {
			signal: request.signal,
			timeout: 30000,
			showDefaultMsg: false,
		})
		if (
			disposed ||
			!request.isCurrent() ||
			email !== normalizeRegistrationEmail(draft.email)
		)
			return
		codeSent.value = true
		cooldownState.sent(email)
		refreshCooldown()
		sending.value = false
		await focusField('emailCode')
	} catch (failure) {
		if (!disposed && request.isCurrent()) {
			const throttle = registrationCodeThrottle(failure)
			if (throttle) {
				if (throttle.global) cooldownState.throttle(throttle.seconds)
				else cooldownState.sent(email, throttle.seconds)
				refreshCooldown()
			}
			feedback.value = failureMessage(failure, '邮件发送失败，请稍后重试。')
		}
	} finally {
		if (!disposed && request.isCurrent()) sending.value = false
	}
}
async function submit() {
	if (!available.value || busy.value || !formRef.value) return
	validating.value = true
	feedback.value = ''
	const errors = passwordResetErrors(draft)
	try {
		await formRef.value.validateField(fields)
		if (Object.keys(errors).length) throw errors
	} catch {
		validating.value = false
		const field = Object.keys(errors)[0] || 'email'
		feedback.value = errors[field] || '请检查输入内容'
		await focusField(field)
		return
	}
	validating.value = false
	if (disposed || busy.value) return
	submitting.value = true
	const request = resetRequest.begin()
	try {
		const response = await passwordResetReq(buildPasswordResetPayload(draft), {
			signal: request.signal,
			timeout: 30000,
			showDefaultMsg: false,
		})
		if (disposed || !request.isCurrent()) return
		completed.value =
			response.msg ||
			'验证已完成；若该邮箱绑定了可用账号，密码已重置，请重新登录。'
		draft.password = ''
		draft.confirmPassword = ''
		draft.emailCode = ''
		clearInterval(timer)
	} catch (failure) {
		if (!disposed && request.isCurrent())
			feedback.value = failureMessage(failure, '重置失败，请检查验证码后重试。')
	} finally {
		if (!disposed && request.isCurrent()) submitting.value = false
	}
}
function returnToLogin() {
	if (busy.value) return
	emit('return-to-login', normalizeRegistrationEmail(draft.email))
	emit('update:modelValue', false)
}
onMounted(loadConfig)
onBeforeUnmount(() => {
	disposed = true
	configRequest.cancel()
	codeRequest.cancel()
	resetRequest.cancel()
	clearInterval(timer)
	draft.password = ''
	draft.confirmPassword = ''
	draft.emailCode = ''
})
</script>
