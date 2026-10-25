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

export const learningCoverage = (material) => {
	if (
		!Number.isSafeInteger(material?.indexVersion) ||
		material.indexVersion < 2 ||
		!Number.isSafeInteger(material?.coveredChars) ||
		material.coveredChars < 0 ||
		typeof material.truncated !== 'boolean'
	)
		return {
			partial: true,
			text: '旧版索引：覆盖范围未知，请重新索引以覆盖长文。',
		}
	if (material.coveredChars === 0)
		return {
			partial: true,
			text: '未记录可检索文字，请重新索引并检查文件内容。',
		}
	const chars = Math.max(0, material.coveredChars).toLocaleString('zh-CN')
	const pages =
		Number.isInteger(material.indexedPages) &&
		Number.isInteger(material.totalPages)
			? ` · 已处理 ${material.indexedPages} / ${material.totalPages} 页`
			: ''
	const partial =
		material.truncated === true ||
		(Number.isInteger(material.indexedPages) &&
			material.indexedPages >= 0 &&
			Number.isInteger(material.totalPages) &&
			material.indexedPages < material.totalPages)
	return {
		partial,
		text: `${
			partial ? '部分索引' : '可提取文字已索引'
		} · ${chars} 字符${pages}${
			partial
				? pages
					? '，末页可能部分覆盖；请拆分剩余内容。'
					: '，剩余文字未覆盖；请拆分剩余内容。'
				: '（不含图片中的文字）'
		}`,
	}
}
