import { marked } from 'marked'
import DOMPurify from 'dompurify'

const ALLOWED_TAGS = new Set([
	'p',
	'br',
	'strong',
	'em',
	'del',
	'ul',
	'ol',
	'li',
	'code',
	'pre',
	'blockquote',
	'h1',
	'h2',
	'h3',
	'h4',
	'h5',
	'h6',
	'a',
	'hr',
	'table',
	'thead',
	'tbody',
	'tr',
	'th',
	'td',
])

// Remove only blank lines outside the answer; indentation and code remain intact.
export function normalizeAssistantContent(content) {
	const text = String(content ?? '').replace(/^(?:[\t ]*\r?\n)+/, '')
	let fence = null
	for (const line of text.split(/\r?\n/)) {
		const match = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/)
		if (!match) continue
		if (!fence) {
			fence = { character: match[1][0], length: match[1].length }
		} else if (
			match[1][0] === fence.character &&
			match[1].length >= fence.length &&
			!match[2].trim()
		) {
			fence = null
		}
	}
	const lastContentLine =
		text.split(/\r?\n/).findLast((line) => line.trim()) || ''
	return fence || /^(?: {4}|\t)/.test(lastContentLine)
		? text
		: text.replace(/(?:\r?\n[\t ]*)+$/, '')
}

export function renderAssistantContent(content, { streaming = false } = {}) {
	let text = String(content ?? '')
	// A token can end inside a link, heading or emphasis marker. Commit complete
	// lines while streaming, then render every character in the final response.
	if (streaming) text = text.slice(0, text.lastIndexOf('\n') + 1)
	// Exclude resource-bearing elements before the extra document parse below;
	// deleting an image only after parsing its src can be too late to prevent a request.
	const html = DOMPurify.sanitize(
		marked.parse(normalizeAssistantContent(text), {
			breaks: false,
			gfm: true,
			mangle: false,
			headerIds: false,
		}),
		{
			ALLOWED_TAGS: Array.from(ALLOWED_TAGS),
			ALLOWED_ATTR: ['href', 'start'],
			ALLOW_DATA_ATTR: false,
			ALLOW_ARIA_ATTR: false,
		}
	)
	const documentNode = new DOMParser().parseFromString(html, 'text/html')
	const root = documentNode.body
	root.querySelectorAll('*').forEach((element) => {
		const tag = element.tagName.toLowerCase()
		if (!ALLOWED_TAGS.has(tag)) {
			element.replaceWith(
				documentNode.createTextNode(element.textContent || '')
			)
			return
		}
		const href = tag === 'a' ? element.getAttribute('href') : null
		const start = tag === 'ol' ? element.getAttribute('start') : null
		Array.from(element.attributes).forEach((attribute) =>
			element.removeAttribute(attribute.name)
		)
		if (tag === 'ol' && /^\d{1,9}$/.test(start || '')) {
			element.setAttribute('start', start)
		}
		if (tag !== 'a' || !href) return
		try {
			const url = new URL(href, window.location.origin)
			if (!['http:', 'https:'].includes(url.protocol)) return
			element.setAttribute('href', href)
			element.setAttribute('target', '_blank')
			element.setAttribute('rel', 'noopener noreferrer')
		} catch {
			// Invalid and executable links remain plain text.
		}
	})
	return root.innerHTML
}

export function assistantCoverageNotice(coverage) {
	if (!coverage || typeof coverage !== 'object') return ''
	const notice =
		typeof coverage.notice === 'string' ? coverage.notice.trim() : ''
	if (notice) return notice
	const parts = []
	if (Number.isSafeInteger(coverage.skipped) && coverage.skipped > 0) {
		parts.push(`${coverage.skipped} 份资料未能读取`)
	}
	if (coverage.truncated === true) parts.push('部分资料或检索范围受限')
	return parts.length
		? `检索范围提示：${parts.join('；')}。回答仅依据本轮可用片段。`
		: ''
}

export function materialCitationLocation(citation) {
	return [
		citation.courseId != null ? `课程 ${citation.courseId}` : '',
		citation.materialId != null ? `资料 ${citation.materialId}` : '',
		citation.storageKey || '',
		citation.path || '',
	]
		.filter(Boolean)
		.join(' · ')
}
