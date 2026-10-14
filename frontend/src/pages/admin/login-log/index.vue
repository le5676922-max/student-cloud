<template>
	<div>
		<admin-form-header title="登录日志" hide-bottom-border />

		<el-form
			ref="searchFormRef"
			inline
			class="z-admin-search-from"
			:size="globalConfigStore.adminSearch.size"
			label-width="80"
			:label-position="globalConfigStore.adminSearch.labelPosition"
			:model="searchParam"
		>
			<el-row class="z-admin-search-from-row" :gutter="20">
				<el-col :span="globalConfigStore.adminSearch.spanSize">
					<el-form-item label="用户名" prop="username">
						<el-input v-model="searchParam.username" />
					</el-form-item>
				</el-col>
				<el-col :span="globalConfigStore.adminSearch.spanSize">
					<el-form-item label="登录结果" prop="result">
						<el-input v-model="searchParam.result" />
					</el-form-item>
				</el-col>
				<el-col :span="globalConfigStore.adminSearch.spanSize">
					<el-form-item label="请求 IP" prop="ip">
						<el-input v-model="searchParam.ip" />
					</el-form-item>
				</el-col>
				<el-col :span="globalConfigStore.adminSearch.spanSize">
					<el-form-item label="UserAgent" prop="userAgent">
						<el-input v-model="searchParam.userAgent" />
					</el-form-item>
				</el-col>
				<el-col :span="globalConfigStore.adminSearch.spanSize">
					<el-form-item label="Referer" prop="referer">
						<el-input v-model="searchParam.referer" />
					</el-form-item>
				</el-col>
				<el-col :span="globalConfigStore.adminSearch.spanSize">
					<el-form-item label="登录时间" prop="searchDate">
						<el-date-picker-plus
							v-model="searchParam.searchDate"
							:shortcuts="shortcuts"
							type="daterange"
							:value-format="dateValueFormat"
							:default-time="defaultTime"
							range-separator="至"
							start-placeholder="开始时间"
							end-placeholder="结束时间"
						/>
					</el-form-item>
				</el-col>
				<el-col :span="globalConfigStore.adminSearch.spanSize">
					<el-button
						type="primary"
						:icon="MagnifyingGlassIcon"
						:loading="loading"
						@click="search"
						>查询</el-button
					>
					<el-button :icon="ArrowPathIcon" :loading="loading" @click="resetForm"
						>重置</el-button
					>
				</el-col>
			</el-row>
		</el-form>
		<el-alert
			v-if="error"
			:title="error"
			type="error"
			:closable="false"
			show-icon
			class="mb-3"
		>
			<el-button text type="primary" @click="loadData">重新加载</el-button>
		</el-alert>
		<p class="mb-3 text-sm text-gray-500">
			这里只显示已记录的登录事件；日志为空不代表没有用户访问。密码不会记录或展示。
		</p>

		<el-table
			v-loading="loading"
			border
			:data="pageData"
			:size="globalConfigStore.adminTable.size"
			empty-text="暂无符合条件的登录日志"
		>
			<el-table-column label="用户名" prop="username" />
			<el-table-column
				width="250"
				show-overflow-tooltip
				prop="result"
				label="登录结果"
			/>
			<el-table-column width="180" prop="createTime" label="登录时间" />
			<el-table-column
				width="100"
				show-overflow-tooltip
				prop="ip"
				label="请求 IP"
			/>
			<el-table-column
				show-overflow-tooltip
				prop="userAgent"
				label="UserAgent"
			/>
			<el-table-column show-overflow-tooltip prop="referer" label="Referer" />
		</el-table>

		<el-pagination
			v-model:current-page="searchParam.page"
			v-model:page-size="searchParam.limit"
			class="mt-3"
			background
			:layout="globalConfigStore.adminTable.pager.layout"
			:size="globalConfigStore.adminTable.pager.size"
			:pager-count="globalConfigStore.adminTable.pager.pagerCount"
			:page-sizes="[10, 20, 50, 100]"
			:total="total"
			:disabled="loading || !!error"
			@current-change="handleCurrentChange"
			@size-change="handleSizeChange"
		/>
	</div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { MagnifyingGlassIcon, ArrowPathIcon } from '@heroicons/vue/24/outline'

import { dateValueFormat, defaultTime, shortcuts } from '~/utils'

import { getLoginLogListReq } from '~/api/admin/admin-login-log'
import { createLatestRequest } from '~/utils/latest-request.mjs'

import useGlobalConfigStore from '~/stores/global-config'
const globalConfigStore = useGlobalConfigStore()

const searchParam = reactive({
	page: 1,
	limit: 10,
	username: '',
	result: '',
	ip: '',
	userAgent: '',
	referer: '',
	searchDate: [],
	orderBy: 'create_time',
	orderDirection: 'desc',
})

const requests = createLatestRequest()
const total = ref(0)

const handleSizeChange = (val) => {
	searchParam.limit = val
	searchParam.page = 1
	loadData()
}

const handleCurrentChange = (val) => {
	searchParam.page = val
	loadData()
}

const pageData = ref([])
const loading = ref(false)
const error = ref('')
const loadData = async () => {
	const request = requests.begin()
	loading.value = true
	error.value = ''
	pageData.value = []
	const params = { ...searchParam }
	// Clearing the date picker sends no empty date-array query to Spring MVC.
	if (!Array.isArray(params.searchDate) || params.searchDate.length !== 2) {
		delete params.searchDate
	} else {
		params.searchDate = [...params.searchDate]
	}
	try {
		const response = await getLoginLogListReq(params, {
			signal: request.signal,
		})
		if (!request.isCurrent()) return
		pageData.value = Array.isArray(response.data) ? response.data : []
		total.value = response.dataCount ?? 0
	} catch (failure) {
		if (!request.isCurrent()) return
		error.value =
			failure?.response?.data?.msg ||
			failure?.data?.msg ||
			'登录日志加载失败，请重试'
	} finally {
		if (request.isCurrent()) loading.value = false
	}
}

const search = () => {
	searchParam.page = 1
	loadData()
}

onMounted(loadData)
onUnmounted(() => requests.cancel())

const searchFormRef = ref()
const resetForm = () => {
	searchFormRef.value?.resetFields()
	searchParam.page = 1
	loadData()
}
</script>

<style scoped lang="scss"></style>

<route lang="yaml">
meta:
  layout: admin
  name: 登录日志
</route>
