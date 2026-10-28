import { parsePrivateModel } from '../utils/private-model-preview.mjs'

self.onmessage = ({ data }) => {
	try {
		const geometry = parsePrivateModel(data.buffer, data.format)
		self.postMessage({ ok: true, geometry }, [
			geometry.positions.buffer,
			geometry.normals.buffer,
		])
	} catch {
		self.postMessage({ ok: false })
	}
}
