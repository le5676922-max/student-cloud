import { tryResumableUpload, readResumableStatus } from './resumable-upload.mjs'

const MAX_BYTES = 50 * 1024 * 1024

// Share only the browser hashing/transfer algorithm. The independent server contract is explicit.
export async function tryIndependentResumableUpload(options) {
	if (options.uploadType === 'PROXY' && options.file?.size > MAX_BYTES)
		throw new Error('当前独立云盘上传文件不能超过 50 MiB')
	const request = async (config) => {
		const response = await options.request(config)
		if (config.url.endsWith('/capabilities')) {
			const caps = response?.data
			if (caps?.protocol !== 'independent-v1' ||
				(caps.enabled === true && caps.maxFileSize !== MAX_BYTES))
				throw new Error('独立上传服务返回了不兼容的配置，请刷新后重试')
		}
		return response
	}
	return tryResumableUpload({ ...options, request })
}

export const readIndependentResumableStatus = readResumableStatus
