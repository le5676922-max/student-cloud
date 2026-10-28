// URL context is a form convenience, never an authorization or execution instruction.
export const AUTOMATION_TYPES = Object.freeze([
	'ORGANIZE_FILES',
	'RENAME_BY_DATE',
	'IMPORT_LEARNING',
])

function hasControlCharacter(value) {
	return Array.from(value).some(
		(character) =>
			character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127
	)
}

export function normalizeAutomationDirectory(value) {
	if (
		typeof value !== 'string' ||
		!value.startsWith('/') ||
		value.length > 500 ||
		value.trim() !== value ||
		/[\\%:?#]/u.test(value) ||
		hasControlCharacter(value) ||
		value.includes('//') ||
		value.split('/').some((part) => part === '.' || part === '..')
	)
		return null
	return value === '/' ? '/' : value.replace(/\/$/u, '')
}

export const normalizeAutomationPath = normalizeAutomationDirectory

function storageKey(value) {
	return typeof value === 'string' &&
		value.length > 0 &&
		value.length <= 100 &&
		value.trim() === value &&
		!(/[\\/%:?#]/u.test(value) || hasControlCharacter(value))
		? value
		: null
}

export function parseAutomationContext(query = {}) {
	const context = {}
	const key = storageKey(query?.storageKey)
	const path = normalizeAutomationDirectory(query?.path)
	if (key) context.storageKey = key
	if (path) context.path = path
	if (AUTOMATION_TYPES.includes(query?.type)) context.type = query.type
	return context
}

export function buildAutomationRoute(context = {}) {
	return { path: '/agent-tasks', query: parseAutomationContext(context) }
}

export function automationContextFromRoute(route) {
	if (route?.meta?.layout !== 'file') return {}
	const key = storageKey(route?.params?.storageKey)
	if (!key) return {}
	const segments = route.params.fullpath
	if (
		Array.isArray(segments) &&
		segments.some((part) => typeof part !== 'string' || part.includes('/'))
	)
		return {}
	const rawPath = Array.isArray(segments)
		? `/${segments.join('/')}`
		: typeof segments === 'string'
		? segments.startsWith('/')
			? segments
			: `/${segments}`
		: segments == null
		? '/'
		: null
	const path = normalizeAutomationDirectory(rawPath)
	return path ? { storageKey: key, path } : {}
}

export function canPreviewDirectory(context, source, permission, type) {
	const sourceType = source?.type?.key || source?.type
	if (
		!context?.storageKey ||
		source?.key !== context.storageKey ||
		!['local', 'INDEPENDENT_LOCAL'].includes(sourceType)
	)
		return false
	if (type === 'ORGANIZE_FILES')
		return permission?.move === true && permission?.newFolder === true
	return type === 'RENAME_BY_DATE' && permission?.rename === true
}
