import axios from '~/http/request'

// 加载系统支持的所有存储引擎
export const loadSupportStorageReq = (config = {}) => {
	return axios({
		url: '/admin/support-storage',
		method: 'get',
		config: { timeout: 15000, ...config },
	})
}

// 获取所有存储源
export const loadStorageListReq = (data, config = {}) => {
	return axios({
		url: '/admin/storages',
		method: 'get',
		data,
		config: { timeout: 12000, ...config },
	})
}

// 获取指定存储源的所有参数
export const loadStorageItemReq = (storageKey, config = {}) => {
	return axios({
		url: `/admin/storage/${storageKey}`,
		method: 'get',
		config: { timeout: 15000, ...config },
	})
}

// 删除存储源
export const deleteStorageReq = (id) => {
	return axios({
		url: `/admin/storage/${id}`,
		method: 'delete',
	})
}

// 启用/关闭存储源的缓存功能
export const switchEnableReq = (id, action) => {
	return axios({
		url: `/admin/storage/${id}/${action}`,
		method: 'post',
	})
}

// 调整存储源的顺序
export const storageSortReq = (data, config = {}) => {
	return axios({
		url: `/admin/storage/sort`,
		method: 'post',
		data,
		config: { timeout: 12000, ...config },
	})
}

// 启用/关闭存储源的缓存功能
export const switchCacheEnableReq = (id, action) => {
	return axios({
		url: `/admin/cache/${id}/${action}`,
		method: 'post',
	})
}

// 启用/关闭存储源的缓存自动刷新功能
export const switchCacheAutoRefreshReq = (id, action) => {
	return axios({
		url: `/admin/cache/${id}/auto-refresh/${action}`,
		method: 'post',
	})
}

// 获取指定存储类型的字段
export const storageParamsReq = (data, config = {}) => {
	return axios({
		url: `/admin/storage-params`,
		method: 'get',
		data,
		config: { timeout: 15000, ...config },
	})
}

// 保存存储源参数
export const saveStorageSettingReq = (data, config = {}) => {
	return axios({
		url: `/admin/storage`,
		method: 'post',
		data,
		config: { timeout: 30000, ...config },
	})
}

// 只读测试未保存配置，不会通过保存接口探测连接。
export const testStorageConnectionReq = (data, config = {}) => {
	return axios({
		url: '/admin/storage/test-connection',
		method: 'post',
		data,
		config: { timeout: data?.verifyWrite ? 75000 : 22000, ...config },
	})
}

// 仅应用该源已保存的连接与 CORS；不提交未保存的表单凭据或规则。
export const applySavedStorageCorsReq = (id, config = {}) => {
	return axios({
		url: `/admin/storage/${id}/cors/apply`,
		method: 'post',
		data: { confirmed: true },
		config: { timeout: 22000, ...config },
	})
}

// 是否存在此存储源 key
export const existStorageKeyReq = (data, config = {}) => {
	return axios({
		url: `/admin/storage/exist/key`,
		method: 'get',
		data,
		config: { timeout: 10000, ...config },
	})
}

// 加载存储源过滤参数
export const loadStorageFilterReq = (storageKey) => {
	return axios({
		url: `/admin/storage/${storageKey}/filters`,
		method: 'get',
	})
}

// 保存存储源过滤参数
export const saveStorageFilterReq = (storageKey, data) => {
	return axios({
		url: `/admin/storage/${storageKey}/filters`,
		method: 'post',
		data,
	})
}

// 加载存储源密码参数
export const loadStoragePasswordReq = (storageKey) => {
	return axios({
		url: `/admin/storage/${storageKey}/password`,
		method: 'get',
	})
}

// 保存存储源密码参数
export const saveStoragePasswordReq = (storageKey, data) => {
	return axios({
		url: `/admin/storage/${storageKey}/password`,
		method: 'post',
		data,
	})
}

// 加载存储源密码参数
export const loadStorageReadmeReq = (storageKey) => {
	return axios({
		url: `/admin/storage/${storageKey}/readme`,
		method: 'get',
	})
}

// 保存存储源密码参数
export const saveStorageReadmeReq = (storageKey, data) => {
	return axios({
		url: `/admin/storage/${storageKey}/readme`,
		method: 'post',
		data,
	})
}

// 加载存储源权限参数
export const loadStoragePermissionReq = (storageKey) => {
	return axios({
		url: `/admin/storage/${storageKey}/permission`,
		method: 'get',
	})
}

// 保存存储源权限参数
export const saveStoragePermissionReq = (storageKey, data) => {
	return axios({
		url: `/admin/storage/${storageKey}/permission`,
		method: 'post',
		data,
		config: {
			responseIntercept: false,
		},
	})
}

// 启用/关闭存储源兼容 readme 模式
export const changeCompatibilityReadmeEnableReq = (id, action) => {
	return axios({
		url: `/admin/storage/${id}/compatibility_readme/${action}`,
		method: 'post',
	})
}

// 复制存储源
export const copyStorageReq = (data) => {
	return axios({
		url: `/admin/storage/copy`,
		method: 'post',
		data,
	})
}
