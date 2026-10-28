import { shallowRef } from 'vue'
import request from '~/http/request'
import { createPrivatePreviewSession } from '~/utils/private-file-preview.mjs'
import { parsePrivatePreviewDocument } from '~/utils/private-preview-worker.mjs'

const state = shallowRef({
	visible: false,
	loading: false,
	name: '',
	kind: '',
	url: '',
	text: '',
	error: '',
	document: null,
	mediaType: '',
	buffer: null,
})
const session = createPrivatePreviewSession({
	parseDocument: parsePrivatePreviewDocument,
	request: (url, signal) =>
		request({
			method: 'get',
			url,
			config: {
				signal,
				responseType: 'blob',
				responseIntercept: false,
				showDefaultMsg: false,
				timeout: 120000,
				maxContentLength: 50 * 1048576,
			},
		}),
	onChange: (value) => {
		state.value = value
	},
})

export default function usePrivatePreviewDialog() {
	return { state, open: session.open, close: session.close }
}
