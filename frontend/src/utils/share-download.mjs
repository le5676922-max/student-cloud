// Share content URLs are signed by the backend after all access checks.
// Never reconstruct a download endpoint or turn a denied (null) URL into a link.
export function shareDownloadUrl(row) {
	const url = row?.url
	if (typeof url !== 'string' || !url) return ''
	if (url.startsWith('/') && !url.startsWith('//')) return url
	try {
		const parsed = new URL(url)
		return ['http:', 'https:'].includes(parsed.protocol) ? url : ''
	} catch {
		return ''
	}
}
