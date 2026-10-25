import { filterLearningMaterials } from './learning.mjs'

// These are stored index facts, never a claim that the source file is still accessible.
export function learningIndexState(material) {
	if (
		!Number.isSafeInteger(material?.indexVersion) ||
		material.indexVersion < 2 ||
		!Number.isSafeInteger(material?.coveredChars) ||
		material.coveredChars <= 0 ||
		typeof material.truncated !== 'boolean'
	)
		return 'unknown'
	if (material.truncated) return 'partial'
	const { indexedPages, totalPages } = material
	if (
		Number.isSafeInteger(indexedPages) &&
		Number.isSafeInteger(totalPages) &&
		indexedPages >= 0 &&
		totalPages > indexedPages
	)
		return 'partial'
	return 'indexed'
}

function hasTags(material) {
	return (
		typeof material?.tags === 'string' &&
		material.tags.split(/[,，]/).some((tag) => tag.trim().length > 0)
	)
}

function records(materials) {
	return (Array.isArray(materials) ? materials : []).filter(
		(material) => material && typeof material === 'object'
	)
}

export function learningIndexOverview(materials) {
	const items = records(materials)
	const result = {
		all: items.length,
		indexed: 0,
		partial: 0,
		unknown: 0,
		untagged: 0,
	}
	for (const item of items) {
		result[learningIndexState(item)]++
		if (!hasTags(item)) result.untagged++
	}
	return result
}

export function filterLearningIndex(materials, keyword = '', state = 'all') {
	const items = filterLearningMaterials(
		records(materials),
		typeof keyword === 'string' ? keyword : ''
	)
	return items.filter((item) => {
		if (state === 'untagged') return !hasTags(item)
		if (['indexed', 'partial', 'unknown'].includes(state))
			return learningIndexState(item) === state
		return true
	})
}
