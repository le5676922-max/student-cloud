import axios from '~/http/request'

// 与站内 AI Agent 对话
export const chatWithAiAgentReq = (data) => {
	return axios({
		url: '/api/ai/chat',
		method: 'post',
		data,
		config: {
			showDefaultMsg: false,
		},
	})
}
// 携带临时附件与 AI Agent 对话，附件不会写入云盘
export const chatWithAiAgentFilesReq = (data, files) => {
	const formData = new FormData()
	formData.append(
		'request',
		new Blob([JSON.stringify(data)], { type: 'application/json' })
	)
	files.forEach((file) => formData.append('files', file))

	return axios({
		url: '/api/ai/chat/files',
		method: 'post',
		data: formData,
		config: {
			showDefaultMsg: false,
			headers: {
				// 覆盖实例的 JSON 默认值，让浏览器为 FormData 自动补充 boundary。
				'Content-Type': false,
			},
		},
	})
}
