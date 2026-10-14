<template>
	<el-dialog
		v-model="isShow"
		append-to-body
		:destroy-on-close="true"
		title="绑定双因素认证"
		draggable
		top="5vh"
		width="30%"
		@close="closeDialog"
	>
		<div class="text-center">
			<div>可使用双因素认证软件扫描下方二维码或复制下方密钥到软件中添加。</div>
			<QrCodePreview
				class="block my-4 mx-auto w-56 h-56"
				:src="twoFAData?.svg"
				:alt="twoFAData?.qrcode"
				filename="two-fa-qrcode.png"
				@click="load2FAInfo"
			/>
			<div class="text-gray-300 mb-4" @click="copyTwoFASecret">
				{{ twoFAData.secret }}
			</div>
			<div class="flex justify-between space-x-2">
				<el-input
					v-model="twoFAData.code"
					placeholder="请绑定双因素认证软件后，输入显示的验证码。"
				/>
				<el-button
					:loading="saveLoading"
					type="primary"
					@click="verifyCodeAndBind"
					>验证并绑定</el-button
				>
			</div>
		</div>
	</el-dialog>
</template>

<script setup>
import QrCodePreview from '~/components/common/QrCodePreview.vue'

import { rendererRect } from 'beautify-qrcode'
import { generator2FAInfoReq, verify2FAInfoReq } from '~/api/admin/admin-2fa'
import { toClipboard } from '@soerenmartius/vue3-clipboard'
import { generateQRCode } from '~/utils'

const emit = defineEmits(['close'])
const props = defineProps({
	visible: {
		type: Boolean,
		required: true,
	},
})

const isShow = ref(props.visible)
const isSuccess = ref(false)
const saveLoading = ref(false)

watch(
	() => props.visible,
	(newVal) => {
		isShow.value = newVal
		if (newVal) {
			isSuccess.value = false
			load2FAInfo()
		}
	}
)

const closeDialog = () => {
	if (!isSuccess.value) {
		emit('close', '')
	}
}

const twoFAData = ref({})
const load2FAInfo = () => {
	generator2FAInfoReq().then((res) => {
		twoFAData.value = res.data
		// 生成二维码
		twoFAData.value.svg = generateQRCode(
			{
				text: twoFAData.value.qrcode,
				correctLevel: 1,
				isSpace: false,
			},
			rendererRect
		)
	})
}

// 验证并绑定双因素认证
const verifyCodeAndBind = () => {
	saveLoading.value = true
	verify2FAInfoReq(twoFAData.value)
		.then(() => {
			ElMessage.success('绑定成功')
			isSuccess.value = true
			emit('close', twoFAData.value.secret)
		})
		.finally(() => {
			saveLoading.value = false
		})
}

// 复制 2fa 密钥
const copyTwoFASecret = () => {
	toClipboard(twoFAData.value.secret).then(() => {
		ElMessage.success('复制成功')
	})
}
</script>

<style lang="scss" scoped></style>
