import axios from '~/http/request'
import useGlobalConfigStore from '~/stores/global-config'
import { consumeAiStream } from '~/utils/ai-stream.mjs'

/** POST streaming keeps authentication out of URLs and supports ordinary JSON + multipart attachments. */
export async function streamWithAiAgentReq(data, files, { signal, onEvent }) {
	const id = crypto.randomUUID()
	const config = useGlobalConfigStore()
	const base = config.studentCloudConfig.baseUrl.replace(/\/$/, '')
	const tokenName = 'tongligong-student-cloud-token'
	const token =
		window.localStorage.getItem(tokenName) ||
		document.cookie
			.split('; ')
			.find((row) => row.startsWith(`${tokenName}=`))
			?.slice(tokenName.length + 1)
	const headers = {
		Accept: 'text/event-stream',
		'axios-request': 'true',
		'axios-from': config.serverAddress,
	}
	if (token) headers[tokenName] = token
	const controller = new AbortController()
	let timedOut = false
	let cancelSent = false
	const cancel = () => {
		if (!cancelSent) {
			cancelSent = true
			// Independent timeout: aborting the stream must not abort its cancellation request.
			axios({
				url: `/api/ai/chat/cancel/${id}`,
				method: 'post',
				config: { showDefaultMsg: false, timeout: 5000 },
			}).catch(() => {})
		}
		controller.abort()
	}
	const timer = window.setTimeout(() => {
		timedOut = true
		cancel()
	}, 195_000)
	signal?.addEventListener('abort', cancel, { once: true })
	try {
		if (signal?.aborted) throw new DOMException('已停止生成', 'AbortError')
		let body
		if (files.length) {
			body = new FormData()
			body.append(
				'request',
				new Blob([JSON.stringify(data)], { type: 'application/json' })
			)
			files.forEach((file) => body.append('files', file))
		} else {
			headers['Content-Type'] = 'application/json'
			body = JSON.stringify(data)
		}
		const response = await fetch(
			`${base}/api/ai/chat/${files.length ? 'files/stream' : 'stream'}/${id}`,
			{
				method: 'POST',
				headers,
				body,
				signal: controller.signal,
				credentials: 'same-origin',
			}
		)
		return await consumeAiStream(response, {
			signal: controller.signal,
			onEvent,
		})
	} catch (error) {
		if (timedOut) throw new Error('本轮 AI 对话已超时，请缩小问题范围后重试')
		throw error
	} finally {
		window.clearTimeout(timer)
		signal?.removeEventListener('abort', cancel)
	}
}

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
