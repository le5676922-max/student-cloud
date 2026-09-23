export const supportedLearningFile = (name) =>
	/\.(txt|md|pdf|docx)$/i.test(name)

// Each path component is encoded separately, so names cannot change the route's query/hash.
export const learningFileRoute = (storageKey, path) =>
	`/${encodeURIComponent(storageKey)}/${path
		.replace(/^\/+/, '')
		.split('/')
		.map(encodeURIComponent)
		.join('/')}`

export const filterLearningMaterials = (materials, keyword) => {
	const query = keyword.trim().toLocaleLowerCase()
	return materials.filter((item) =>
		`${item.name} ${item.tags}`.toLocaleLowerCase().includes(query)
	)
}
