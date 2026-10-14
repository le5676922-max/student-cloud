// Bound both network payload and resident DOM rows. A page replaces, never appends, the previous page.
export function directoryPageSize(configured) {
	const size = Number(configured)
	return Number.isFinite(size) && size > 0
		? Math.max(1, Math.min(200, Math.floor(size)))
		: 100
}

// A cursor must travel with the successful page's query/sort, not an unsubmitted input draft.
export function directoryRequestOptions(input = {}, defaults = {}) {
	if ((input.nativePagination ?? defaults.nativePagination) === true) {
		return {
			pageSize: directoryPageSize(input.pageSize ?? defaults.pageSize),
			nameQuery: '',
			nativePagination: true,
		}
	}
	return {
		pageSize: directoryPageSize(input.pageSize ?? defaults.pageSize),
		nameQuery: String(input.nameQuery ?? defaults.nameQuery ?? '').trim(),
		orderBy: input.orderBy || defaults.orderBy,
		orderDirection: input.orderDirection || defaults.orderDirection,
	}
}

export function reusableDirectoryListing(listing, storageKey, path) {
	const normalized = (value) => String(value || '/').replace(/\/+$/, '')
	return listing?.storageKey === storageKey &&
		normalized(listing.path) === normalized(path)
		? listing
		: undefined
}

export function directoryPageMetadata(data) {
	if (data?.paged !== true) return null // Existing folder pickers / share endpoints remain compatible.
	const { files, total, offset, pageSize, nextCursor, previousCursor } = data
	if (data.nativePagination === true) {
		const cursorValid = (cursor) =>
			cursor == null || /^np_[A-Za-z0-9_-]{43}$/.test(cursor)
		if (
			data.totalKnown !== false ||
			total !== null ||
			!Array.isArray(files) ||
			!Number.isInteger(pageSize) ||
			pageSize < 1 ||
			pageSize > 200 ||
			files.length > pageSize ||
			!Number.isInteger(data.pageIndex) ||
			data.pageIndex < 0 ||
			!cursorValid(nextCursor) ||
			!cursorValid(previousCursor)
		)
			throw new Error('快速浏览分页数据无效，请刷新重试')
		return {
			total: null,
			totalKnown: false,
			nativePagination: true,
			pageIndex: data.pageIndex,
			offset: 0,
			pageSize,
			count: files.length,
			nextCursor: nextCursor || null,
			previousCursor: previousCursor || null,
		}
	}
	if (
		!Array.isArray(files) ||
		!Number.isInteger(total) ||
		total < 0 ||
		!Number.isInteger(offset) ||
		offset < 0 ||
		offset > total ||
		!Number.isInteger(pageSize) ||
		pageSize < 1 ||
		pageSize > 500 ||
		files.length > pageSize ||
		offset + files.length > total
	)
		throw new Error('目录分页数据无效，请刷新重试')
	return {
		total,
		offset,
		pageSize,
		count: files.length,
		nextCursor: typeof nextCursor === 'string' ? nextCursor : null,
		previousCursor: typeof previousCursor === 'string' ? previousCursor : null,
	}
}

export function supportsNativeDirectoryPagination(storageType) {
	return new Set([
		'aliyun',
		'tencent',
		'huawei',
		'minio',
		's3',
		'qiniu',
		'doge-cloud',
	]).has(storageType)
}
