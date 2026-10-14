import DOMPurify from 'dompurify'

// Shared by Markdown previews and rich-text messages. Never render raw server/file HTML.
export function sanitizeRichHtml(html) {
	return DOMPurify.sanitize(String(html ?? ''), {
		USE_PROFILES: { html: true },
		FORBID_TAGS: ['style', 'form', 'iframe'],
	})
}
