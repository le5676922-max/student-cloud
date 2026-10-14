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
		Number(material?.indexVersion) < 2 ||
		!Number.isInteger(material?.coveredChars)
	)
		return {
			partial: true,
			text: '旧版索引：覆盖范围未知，请重新索引以覆盖长文。',
		}
	const chars = Math.max(0, material.coveredChars).toLocaleString('zh-CN')
	const pages =
		Number.isInteger(material.indexedPages) &&
		Number.isInteger(material.totalPages)
			? ` · 已处理 ${material.indexedPages} / ${material.totalPages} 页`
			: ''
	return {
		partial: material.truncated === true,
		text: `${
			material.truncated === true ? '部分索引' : '可提取文字已索引'
		} · ${chars} 字符${pages}${
			material.truncated === true
				? '，末页可能部分覆盖；请拆分剩余内容。'
				: '（不含图片中的文字）'
		}`,
	}
}
