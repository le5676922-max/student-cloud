import { tryResumableUpload, readResumableStatus } from './resumable-upload.mjs'

const MAX_MULTIPART_BYTES = 50 * 1024 * 1024
const MAX_LOCAL_BYTES = 1024 ** 3

// Share only the browser hashing/transfer algorithm. The independent server contract is explicit.
export async function tryIndependentResumableUpload(options) {
	if (options.file?.size > MAX_LOCAL_BYTES)
		throw new Error('当前独立云盘上传文件不能超过 1 GiB')
	const request = async (config) => {
		const response = await options.request(config)
		if (config.url.endsWith('/capabilities')) {
			const caps = response?.data
			if (
				caps?.protocol !== 'independent-v1' ||
				(caps.enabled === true &&
					(!Number.isSafeInteger(caps.maxFileSize) ||
						caps.maxFileSize <= 0 ||
						caps.maxFileSize > MAX_LOCAL_BYTES))
			)
				throw new Error('独立上传服务返回了不兼容的配置，请刷新后重试')
		}
		return response
	}
	const result = await tryResumableUpload({ ...options, request })
	if (!result.handled && options.file?.size > MAX_MULTIPART_BYTES)
		throw new Error('此目录未启用大文件续传，普通上传不能超过 50 MiB')
	return result
}

export const readIndependentResumableStatus = readResumableStatus
