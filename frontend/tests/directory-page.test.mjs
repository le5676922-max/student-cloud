import test from 'node:test'
import assert from 'node:assert/strict'
import { directoryPageSize, directoryPageMetadata, directoryRequestOptions, reusableDirectoryListing, supportsNativeDirectoryPagination } from '../src/utils/directory-page.mjs'

test('directory pages bound resident rows regardless of large legacy maxShowSize setting', () => {
	assert.equal(directoryPageSize(10000), 200)
	assert.equal(directoryPageSize(25), 25)
	assert.equal(directoryPageSize(0), 100)
	assert.equal(directoryPageSize('invalid'), 100)
	assert.equal(directoryPageSize(0.5), 1)
})
test('next page is independent, excludes the synthetic parent row and never accumulates previous rows', () => {
	const data = { paged: true, files: [1, 2], total: 1000, offset: 200, pageSize: 200, previousCursor: 'previous', nextCursor: 'next' }
	assert.deepEqual(directoryPageMetadata(data), { total: 1000, offset: 200, pageSize: 200, count: 2, nextCursor: 'next', previousCursor: 'previous' })
	assert.equal(directoryPageMetadata({ ...data, files: [], total: 0, offset: 0, nextCursor: null }).count, 0)
})
test('legacy endpoints remain unpaged and malformed responses cannot enter paging state', () => {
	assert.equal(directoryPageMetadata({ files: [1] }), null)
	assert.equal(directoryPageMetadata(undefined), null)
	assert.throws(() => directoryPageMetadata({ paged: true, files: [1], total: 0, offset: 0, pageSize: 20 }))
	assert.throws(() => directoryPageMetadata({ paged: true, files: [1], total: 1, offset: 0, pageSize: 2000 }))
})

test('paging preserves committed query and ordering despite unsubmitted filter drafts', () => {
	const committed = { pageSize: 100, nameQuery: '', orderBy: 'name', orderDirection: 'asc' }
	const drafts = { pageSize: 200, nameQuery: 'unsubmitted', orderBy: 'size', orderDirection: 'desc' }
	assert.deepEqual(directoryRequestOptions(committed, drafts), committed)
	assert.deepEqual(directoryRequestOptions(undefined, drafts), drafts)
	assert.equal(directoryRequestOptions({ nameQuery: ' notes ' }, drafts).nameQuery, 'notes')
})

test('failed page reads retain a retryable cursor context but navigation does not', () => {
	const listing = { storageKey: 'local', path: '/notes/', nameQuery: 'txt' }
	assert.equal(reusableDirectoryListing(listing, 'local', '/notes'), listing)
	assert.equal(reusableDirectoryListing(listing, 'other', '/notes'), undefined)
	assert.equal(reusableDirectoryListing(listing, 'local', '/'), undefined)
	assert.equal(reusableDirectoryListing(undefined, 'local', '/'), undefined)
	assert.ok(reusableDirectoryListing({ storageKey: 'local', path: '' }, 'local', '/'))
})

test('native pages declare unknown total and retain a next page when all entries were hidden', () => {
	const nextCursor = `np_${'a'.repeat(43)}`
	const page = directoryPageMetadata({ paged: true, nativePagination: true, totalKnown: false,
		files: [], total: null, offset: 0, pageSize: 100, pageIndex: 2, nextCursor })
	assert.equal(page.total, null)
	assert.equal(page.totalKnown, false)
	assert.equal(page.count, 0)
	assert.equal(page.nextCursor, nextCursor)
	assert.equal(page.pageIndex, 2)
	assert.throws(() => directoryPageMetadata({ paged: true, nativePagination: true, totalKnown: false,
		files: [], total: 0, pageSize: 100, pageIndex: 0 }))
	assert.throws(() => directoryPageMetadata({ paged: true, nativePagination: true, totalKnown: false,
		files: [], total: null, pageSize: 100, pageIndex: 0, nextCursor: 'raw-provider-token' }))
})

test('fast mode omits global ordering/filter fields and only S3 family is enabled', () => {
	assert.deepEqual(directoryRequestOptions({ nativePagination: true },
		{ nameQuery: 'draft', orderBy: 'size', orderDirection: 'desc', pageSize: 500 }),
		{ pageSize: 200, nameQuery: '', nativePagination: true })
	for (const type of ['s3', 'aliyun', 'minio', 'huawei', 'tencent', 'qiniu', 'doge-cloud']) assert.equal(supportsNativeDirectoryPagination(type), true)
	for (const type of ['local', 'ftp', 'sftp', 'webdav', '', null]) assert.equal(supportsNativeDirectoryPagination(type), false)
})
