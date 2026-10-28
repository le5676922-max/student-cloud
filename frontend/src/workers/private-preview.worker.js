import { parseOfficePreview } from '../utils/private-office-preview.mjs'
import * as XLSX from 'xlsx'

self.onmessage = async ({ data }) => {
	try {
		const value = await parseOfficePreview(data.buffer, data.name, {
			xlsx: XLSX,
		})
		self.postMessage({ ok: true, value })
	} catch {
		// Parser details may contain document data; keep worker errors generic.
		self.postMessage({ ok: false })
	}
}
