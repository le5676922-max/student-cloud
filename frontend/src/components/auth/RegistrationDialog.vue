<template>
	<el-dialog
		v-model="visible"
		title="注册账号"
		class="career-login-entry-dialog career-registration-dialog"
		width="580px"
		:close-on-click-modal="!busy"
		:close-on-press-escape="!busy"
		:show-close="!busy"
		append-to-body
		@opened="focusStep"
	>
		<div class="career-registration">
			<section
				v-if="result"
				class="career-registration-result"
				aria-live="polite"
			>
				<h2>
					{{
						result.status === 'PENDING'
							? '账号已创建，等待加入审批'
							: '注册成功'
					}}
				</h2>
				<p>
					{{
						result.message ||
						(result.status === 'PENDING'
							? '空间管理员审批通过后，你即可登录。'
							: '现在可以使用邮箱或账号 ID 登录。')
					}}
				</p>
				<div class="career-registration-account">
					<label for="registration-account-id"
						>你的账号 ID（系统生成，不可修改）</label
					>
					<div class="career-registration-code-row">
						<el-input
							id="registration-account-id"
							:model-value="result.accountId"
							readonly
						/>
						<el-button :loading="copying" @click="copyAccountId"
							>复制</el-button
						>
					</div>
				</div>
				<dl class="career-registration-details">
					<div>
						<dt>邮箱</dt>
						<dd>{{ result.email }}</dd>
					</div>
					<div>
						<dt>工作空间</dt>
						<dd>{{ result.tenantName || '等待空间管理员审批' }}</dd>
					</div>
					<div>
						<dt>身份</dt>
						<dd>
							{{ result.role === 'TENANT_ADMIN' ? '空间管理员' : '成员' }}
						</dd>
					</div>
				</dl>
				<p class="career-registration-hint">
					请保存账号 ID。注册完成后，需要自行登录。
				</p>
				<button
					type="button"
					class="career-login-submit"
					@click="returnToLogin"
				>
					返回登录
				</button>
			</section>
			<template v-else>
				<ol class="career-registration-stepper" aria-label="注册进度">
					<li
						:class="{ 'is-current': step === 1, 'is-complete': step === 2 }"
						:aria-current="step === 1 ? 'step' : undefined"
					>
						<span class="career-registration-step-number" aria-hidden="true"
							>1</span
						>
						<span>选择工作空间</span>
					</li>
					<li
						:class="{ 'is-current': step === 2 }"
						:aria-current="step === 2 ? 'step' : undefined"
					>
						<span class="career-registration-step-number" aria-hidden="true"
							>2</span
						>
						<span>设置账号</span>
					</li>
				</ol>
				<div class="career-registration-heading" aria-live="polite">
					<h2>{{ step === 1 ? '你想如何使用云盘？' : '创建你的账号' }}</h2>
					<p class="career-registration-intro">
						{{
							step === 1
								? '加入已有工作空间，或创建一个属于你的新空间。'
								: '验证邮箱并设置密码。注册后可用邮箱或账号 ID 登录。'
						}}
					</p>
				</div>
				<p v-if="configLoading" class="career-registration-hint" role="status">
					正在检查注册服务…
				</p>
				<el-alert
					v-else-if="configError || !config.enabled || !config.mailAvailable"
					:title="
						configError ||
						(!config.enabled
							? '当前暂未开放注册。'
							: '邮箱验证暂时不可用，请联系管理员。')
					"
					type="warning"
					:closable="false"
					show-icon
				/>
				<el-button
					v-if="configError"
					class="career-registration-retry"
					:loading="configLoading"
					@click="loadConfig"
					>重新检查</el-button
				>
				<el-form
					id="career-registration-form"
					ref="formRef"
					:model="draft"
					:rules="rules"
					:disabled="busy"
					:validate-on-rule-change="false"
					class="career-login-form career-registration-form"
					label-position="top"
					:scroll-to-error="true"
					hide-required-asterisk
					@submit.prevent="submit"
				>
					<section
						v-show="step === 1"
						class="career-registration-step"
						aria-label="选择工作空间"
					>
						<el-form-item prop="registrationType">
							<div
								class="career-registration-identity-cards"
								role="group"
								aria-label="注册身份"
							>
								<button
									id="registration-type"
									type="button"
									class="career-registration-identity-card"
									:class="{ 'is-selected': draft.registrationType === 'USER' }"
									:aria-pressed="draft.registrationType === 'USER'"
									:disabled="busy"
									@click="selectIdentity('USER')"
								>
									<span
										class="career-registration-identity-icon"
										aria-hidden="true"
										><svg
											viewBox="0 0 24 24"
											width="22"
											height="22"
											fill="none"
											stroke="currentColor"
											stroke-width="1.7"
											stroke-linecap="round"
										>
											<circle cx="9" cy="8" r="3" />
											<path
												d="M3 20v-2a6 6 0 0 1 12 0v2M16 5a3 3 0 0 1 0 6m2 3a5 5 0 0 1 3 4v2"
											/></svg
									></span>
									<span class="career-registration-identity-copy"
										><strong>我是成员</strong
										><span>加入已有空间，与大家共享文件</span></span
									>
									<span
										class="career-registration-identity-check"
										aria-hidden="true"
										>{{ draft.registrationType === 'USER' ? '✓' : '' }}</span
									>
								</button>
								<button
									type="button"
									class="career-registration-identity-card"
									:class="{
										'is-selected': draft.registrationType === 'TENANT_ADMIN',
									}"
									:aria-pressed="draft.registrationType === 'TENANT_ADMIN'"
									:disabled="busy"
									@click="selectIdentity('TENANT_ADMIN')"
								>
									<span
										class="career-registration-identity-icon"
										aria-hidden="true"
										><svg
											viewBox="0 0 24 24"
											width="22"
											height="22"
											fill="none"
											stroke="currentColor"
											stroke-width="1.7"
											stroke-linecap="round"
											stroke-linejoin="round"
										>
											<path
												d="M4 21V4h12v17M16 10h4v11M2 21h20M8 8h4m-4 4h4m-4 4h4"
											/></svg
									></span>
									<span class="career-registration-identity-copy"
										><strong>我是管理员</strong
										><span>创建新空间，管理成员与文件</span></span
									>
									<span
										class="career-registration-identity-check"
										aria-hidden="true"
										>{{
											draft.registrationType === 'TENANT_ADMIN' ? '✓' : ''
										}}</span
									>
								</button>
							</div>
						</el-form-item>
						<template v-if="draft.registrationType === 'TENANT_ADMIN'">
							<el-form-item
								label="新工作空间名称"
								prop="workspaceName"
								for="registration-workspace-name"
							>
								<el-input
									id="registration-workspace-name"
									v-model="draft.workspaceName"
									maxlength="80"
									placeholder="例如：设计团队、学习小组"
								/>
							</el-form-item>
							<p class="career-registration-hint">
								邮箱验证后，你将成为这个空间的管理员，无需审批。
							</p>
						</template>
						<template v-else>
							<el-form-item label="加入方式" prop="joinType">
								<div
									class="career-registration-join-options"
									role="group"
									aria-label="加入工作空间的方式"
								>
									<button
										id="registration-join-type"
										type="button"
										:class="{ 'is-selected': draft.joinType === 'INVITE' }"
										:aria-pressed="draft.joinType === 'INVITE'"
										:disabled="busy"
										@click="selectJoinType('INVITE')"
									>
										使用邀请码
									</button>
									<button
										type="button"
										:class="{ 'is-selected': draft.joinType === 'NAME' }"
										:aria-pressed="draft.joinType === 'NAME'"
										:disabled="busy"
										@click="selectJoinType('NAME')"
									>
										按空间名称申请
									</button>
								</div>
							</el-form-item>
							<el-form-item
								:label="draft.joinType === 'INVITE' ? '邀请码' : '工作空间名称'"
								prop="joinValue"
								for="registration-join-value"
							>
								<el-input
									id="registration-join-value"
									v-model="draft.joinValue"
									maxlength="80"
									:placeholder="
										draft.joinType === 'INVITE'
											? '输入管理员提供的邀请码'
											: '输入完整的工作空间名称'
									"
									autocomplete="off"
									:spellcheck="false"
								/>
							</el-form-item>
							<p class="career-registration-hint">
								{{
									draft.joinType === 'NAME'
										? '提交申请后，需要空间管理员审批，审批通过后才能登录。'
										: '有效的邀请码可以直接加入对应空间，请向管理员获取。'
								}}
							</p>
						</template>
					</section>
					<section
						v-show="step === 2"
						class="career-registration-step"
						aria-label="设置账号"
					>
						<div class="career-registration-summary">
							<span
								><strong>{{
									draft.registrationType === 'TENANT_ADMIN'
										? '创建空间'
										: '加入空间'
								}}</strong
								><span>{{ workspaceSummary }}</span></span
							>
							<button type="button" :disabled="busy" @click="previousStep">
								修改
							</button>
						</div>
						<el-form-item label="邮箱" prop="email" for="registration-email">
							<el-input
								id="registration-email"
								v-model="draft.email"
								maxlength="254"
								placeholder="你的邮箱地址"
								autocomplete="email"
								inputmode="email"
								:spellcheck="false"
								@blur="normalizeEmail"
							/>
						</el-form-item>
						<el-form-item
							label="邮箱验证码"
							prop="emailCode"
							for="registration-email-code"
						>
							<div class="career-registration-code-row">
								<el-input
									id="registration-email-code"
									v-model.trim="draft.emailCode"
									maxlength="6"
									placeholder="6 位验证码"
									autocomplete="one-time-code"
									inputmode="numeric"
								/>
								<el-button
									:loading="sending"
									:disabled="!canRegister || busy || cooldown > 0"
									@click="sendCode"
									>{{
										cooldown > 0 ? `${cooldown} 秒后重发` : '发送验证码'
									}}</el-button
								>
							</div>
						</el-form-item>
						<p v-if="codeSent" class="career-registration-hint" role="status">
							验证码已发送至 {{ sentEmail }}，请查收邮件。
						</p>
						<div class="career-registration-columns">
							<el-form-item
								label="密码"
								prop="password"
								for="registration-password"
							>
								<el-input
									id="registration-password"
									v-model="draft.password"
									type="password"
									show-password
									maxlength="128"
									placeholder="至少 8 位"
									autocomplete="new-password"
								/>
							</el-form-item>
							<el-form-item
								label="确认密码"
								prop="confirmPassword"
								for="registration-confirm-password"
							>
								<el-input
									id="registration-confirm-password"
									v-model="draft.confirmPassword"
									type="password"
									show-password
									maxlength="128"
									placeholder="再次输入密码"
									autocomplete="new-password"
								/>
							</el-form-item>
						</div>
						<el-form-item
							label="昵称（选填）"
							prop="nickname"
							for="registration-nickname"
						>
							<el-input
								id="registration-nickname"
								v-model="draft.nickname"
								maxlength="100"
								placeholder="希望大家如何称呼你"
								autocomplete="nickname"
							/>
						</el-form-item>
					</section>
					<p v-if="feedback" class="career-login-feedback" role="alert">
						{{ feedback }}
					</p>
				</el-form>
			</template>
		</div>
		<template v-if="!result" #footer>
			<div class="career-registration-actions">
				<button
					v-if="step === 2"
					type="button"
					class="career-registration-back"
					:disabled="busy"
					@click="previousStep"
				>
					上一步
				</button>
				<button
					type="submit"
					form="career-registration-form"
					class="career-login-submit"
					:disabled="!canRegister || busy"
					:aria-busy="submitting"
				>
					{{
						submitting
							? '正在创建账号…'
							: step === 1
							? '下一步：设置账号'
							: '创建账号'
					}}
				</button>
			</div>
			<div class="career-registration-return">
				已有账号？<button type="button" :disabled="busy" @click="returnToLogin">
					返回登录
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
import { ElMessage } from 'element-plus'
import { useClipboard } from '@vueuse/core'
import {
	registerReq,
	registrationConfigReq,
	registrationEmailCodeReq,
} from '~/api/home/user'
import {
	buildRegistrationPayload,
	createRegistrationCodeCooldown,
	normalizeRegistrationEmail,
	registrationCodeThrottle,
	registrationEmailError,
	registrationErrors,
	registrationFieldsForStep,
	registrationStepErrors,
} from '~/utils/registration.mjs'
import { createLatestRequest } from '~/utils/latest-request.mjs'

const props = defineProps({
	modelValue: Boolean,
	initialIdentifier: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'return-to-login'])
const visible = computed({
	get: () => props.modelValue,
	set: (value) => {
		if (!busy.value) {
			if (!value && result.value) emit('return-to-login', result.value.email)
			emit('update:modelValue', value)
		}
	},
})
const formRef = ref()
const step = ref(1)
const config = ref({ enabled: false, mailAvailable: false })
const configLoading = ref(true)
const configError = ref('')
const sending = ref(false)
const submitting = ref(false)
const validating = ref(false)
const busy = computed(
	() => validating.value || sending.value || submitting.value
)
const canRegister = computed(
	() =>
		!configLoading.value &&
		!configError.value &&
		config.value.enabled &&
		config.value.mailAvailable
)
const feedback = ref('')
const result = ref(null)
const copying = ref(false)
const { copy } = useClipboard({ legacy: true })
const draft = reactive({
	email: registrationEmailError(props.initialIdentifier)
		? ''
		: normalizeRegistrationEmail(props.initialIdentifier),
	emailCode: '',
	password: '',
	confirmPassword: '',
	nickname: '',
	registrationType: 'USER',
	workspaceName: '',
	joinType: 'INVITE',
	joinValue: '',
})
const joinDrafts = { INVITE: '', NAME: '' }
const workspaceSummary = computed(() =>
	draft.registrationType === 'TENANT_ADMIN'
		? draft.workspaceName.trim()
		: draft.joinType === 'NAME'
		? `${draft.joinValue.trim()} · 需要审批`
		: '通过邀请码加入 · 无需审批'
)
const rules = computed(() =>
	Object.fromEntries(
		[
			'email',
			'emailCode',
			'password',
			'confirmPassword',
			'nickname',
			'registrationType',
			'workspaceName',
			'joinType',
			'joinValue',
		].map((field) => [
			field,
			[
				{
					trigger: 'blur',
					validator: (_rule, _value, callback) => {
						const message = registrationErrors(draft)[field]
						callback(message ? new Error(message) : undefined)
					},
				},
			],
		])
	)
)
const cooldown = ref(0)
const codeSent = ref(false)
const sentEmail = ref('')
const cooldownState = createRegistrationCodeCooldown()
const configRequest = createLatestRequest()
const codeRequest = createLatestRequest()
const submitRequest = createLatestRequest()
let cooldownTimer
let disposed = false
const failureMessage = (failure, fallback) =>
	failure?.response?.data?.msg || failure?.data?.msg || fallback
const normalizeEmail = () => {
	draft.email = normalizeRegistrationEmail(draft.email)
}
watch(
	() => normalizeRegistrationEmail(draft.email),
	() => {
		codeRequest.cancel()
		sending.value = false
		draft.emailCode = ''
		codeSent.value = false
		sentEmail.value = ''
		feedback.value = ''
		formRef.value?.clearValidate(['email', 'emailCode'])
		refreshCooldown()
	}
)
watch(
	() => draft.password,
	() => {
		if (draft.confirmPassword && formRef.value) {
			formRef.value.validateField('confirmPassword').catch(() => {})
		}
	}
)
watch(draft, () => {
	feedback.value = ''
})

function refreshCooldown() {
	clearInterval(cooldownTimer)
	cooldown.value = cooldownState.remaining(draft.email)
	if (cooldown.value > 0)
		cooldownTimer = setInterval(() => {
			cooldown.value = cooldownState.remaining(draft.email)
			if (!cooldown.value) clearInterval(cooldownTimer)
		}, 1000)
}

const fieldIds = {
	registrationType: 'registration-type',
	joinType: 'registration-join-type',
	joinValue: 'registration-join-value',
	workspaceName: 'registration-workspace-name',
	email: 'registration-email',
	emailCode: 'registration-email-code',
	password: 'registration-password',
	confirmPassword: 'registration-confirm-password',
	nickname: 'registration-nickname',
}
async function focusField(field) {
	await nextTick()
	if (disposed || !props.modelValue) return
	const input = formRef.value?.$el?.querySelector(`#${fieldIds[field]}`)
	input?.focus()
	input?.scrollIntoView?.({ block: 'nearest' })
}
function focusStep() {
	if (!result.value)
		return focusField(
			step.value === 2
				? 'email'
				: draft.registrationType === 'TENANT_ADMIN'
				? 'workspaceName'
				: 'joinValue'
		)
}
function selectIdentity(identity) {
	if (busy.value) return
	draft.registrationType = identity
	formRef.value?.clearValidate([
		'registrationType',
		'workspaceName',
		'joinType',
		'joinValue',
	])
}
function selectJoinType(joinType) {
	if (busy.value || draft.joinType === joinType) return
	joinDrafts[draft.joinType] = draft.joinValue
	draft.joinType = joinType
	draft.joinValue = joinDrafts[joinType] || ''
	formRef.value?.clearValidate(['joinType', 'joinValue'])
}
async function previousStep() {
	if (busy.value) return
	step.value = 1
	feedback.value = ''
	formRef.value?.clearValidate()
	await focusStep()
}
async function validateStep(targetStep) {
	const errors = registrationStepErrors(draft, targetStep)
	try {
		await formRef.value.validateField(
			registrationFieldsForStep(draft, targetStep)
		)
	} catch {
		// The same field rules are shared by the form and the step validator.
	}
	const firstError = Object.keys(errors)[0]
	if (firstError) {
		step.value = targetStep
		feedback.value = errors[firstError]
		validating.value = false
		await focusField(firstError)
		return false
	}
	return true
}
async function loadConfig() {
	if (disposed) return
	const request = configRequest.begin()
	configLoading.value = true
	configError.value = ''
	try {
		const response = await registrationConfigReq({
			signal: request.signal,
			timeout: 15000,
			showDefaultMsg: false,
		})
		if (!disposed && request.isCurrent()) config.value = response.data
	} catch (failure) {
		if (!disposed && request.isCurrent())
			configError.value = failureMessage(
				failure,
				'注册服务暂时无法连接，请稍后重试。'
			)
	} finally {
		if (!disposed && request.isCurrent()) configLoading.value = false
	}
}
async function sendCode() {
	if (!canRegister.value || busy.value || cooldown.value > 0) return
	validating.value = true
	normalizeEmail()
	try {
		await formRef.value.validateField('email')
	} catch {
		validating.value = false
		await focusField('email')
		return
	} finally {
		validating.value = false
	}
	if (disposed || busy.value || !canRegister.value) return
	sending.value = true
	const request = codeRequest.begin()
	feedback.value = ''
	const email = draft.email
	try {
		await registrationEmailCodeReq(email, {
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
		sentEmail.value = email
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
			feedback.value = `${email}：${failureMessage(
				failure,
				'验证码发送失败，请稍后重试。'
			)}`
		}
	} finally {
		if (!disposed && request.isCurrent()) sending.value = false
	}
}
async function submit() {
	if (!canRegister.value || busy.value || !formRef.value) return
	validating.value = true
	feedback.value = ''
	try {
		if (!(await validateStep(1))) return
		if (step.value === 1) {
			step.value = 2
			formRef.value.clearValidate()
			validating.value = false
			await focusStep()
			return
		}
		normalizeEmail()
		if (!(await validateStep(2))) return
		if (disposed) return
		submitting.value = true
		const request = submitRequest.begin()
		const response = await registerReq(buildRegistrationPayload(draft), {
			signal: request.signal,
			timeout: 30000,
			showDefaultMsg: false,
		})
		if (disposed || !request.isCurrent()) return
		result.value = response.data
		draft.password = ''
		draft.confirmPassword = ''
		draft.emailCode = ''
		clearInterval(cooldownTimer)
	} catch (failure) {
		if (!disposed)
			feedback.value = failureMessage(failure, '注册失败，请检查信息后重试。')
	} finally {
		if (!disposed) {
			validating.value = false
			submitting.value = false
		}
	}
}
async function copyAccountId() {
	if (copying.value || !result.value?.accountId) return
	copying.value = true
	try {
		await copy(result.value.accountId)
		ElMessage.success('账号 ID 已复制')
	} catch {
		ElMessage.warning('复制失败，请选中账号 ID 手动复制。')
	} finally {
		copying.value = false
	}
}
function returnToLogin() {
	if (busy.value) return
	emit(
		'return-to-login',
		result.value?.email || normalizeRegistrationEmail(draft.email)
	)
	emit('update:modelValue', false)
}
onMounted(loadConfig)
onBeforeUnmount(() => {
	disposed = true
	configRequest.cancel()
	codeRequest.cancel()
	submitRequest.cancel()
	clearInterval(cooldownTimer)
})
</script>
