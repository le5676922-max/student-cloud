import axios from '~/http/request'

// 加载 S3 Bucket 列表 请求
export const loadS3BucketsReq = (data, config = {}) => {
	return axios({
		url: `/s3/getBuckets`,
		method: 'post',
		data,
		config: {
			showDefaultMsg: false,
			timeout: 15000,
			...config,
		},
	})
}

// 加载 S3 跨域配置列表 请求
export const loadS3CorsConfigReq = (data, config = {}) => {
	return axios({
		url: `/s3/getCorsConfig`,
		method: 'post',
		data,
		config: {
			showDefaultMsg: false,
			timeout: 15000,
			...config,
		},
	})
}
