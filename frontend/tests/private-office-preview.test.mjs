import assert from 'node:assert/strict'
import test from 'node:test'
import { deflateRawSync } from 'node:zlib'
import {
	parseOfficePreview,
	previewCrc32,
	validatePreviewZip,
} from '../src/utils/private-office-preview.mjs'

const encode = (text) => new TextEncoder().encode(text)
function zip(items, { overrideSize } = {}) {
	const locals = [],
		central = []
	let offset = 0
	for (const [name, source] of items) {
		const bytes = typeof source === 'string' ? encode(source) : source
		const key = encode(name),
			compressed = deflateRawSync(bytes)
		const local = new Uint8Array(30 + key.length + compressed.length),
			l = new DataView(local.buffer)
		l.setUint32(0, 0x04034b50, true)
		l.setUint16(4, 20, true)
		l.setUint16(8, 8, true)
		l.setUint32(14, previewCrc32(bytes), true)
		l.setUint32(18, compressed.length, true)
		l.setUint32(22, overrideSize ?? bytes.length, true)
		l.setUint16(26, key.length, true)
		local.set(key, 30)
		local.set(compressed, 30 + key.length)
		locals.push(local)
		const record = new Uint8Array(46 + key.length),
			c = new DataView(record.buffer)
		c.setUint32(0, 0x02014b50, true)
		c.setUint16(4, 20, true)
		c.setUint16(6, 20, true)
		c.setUint16(10, 8, true)
		c.setUint32(16, previewCrc32(bytes), true)
		c.setUint32(20, compressed.length, true)
		c.setUint32(24, overrideSize ?? bytes.length, true)
		c.setUint16(28, key.length, true)
		c.setUint32(42, offset, true)
		record.set(key, 46)
		central.push(record)
		offset += local.length
	}
	const centralBytes = central.reduce((n, x) => n + x.length, 0),
		end = new Uint8Array(22),
		e = new DataView(end.buffer)
	e.setUint32(0, 0x06054b50, true)
	e.setUint16(8, items.length, true)
	e.setUint16(10, items.length, true)
	e.setUint32(12, centralBytes, true)
	e.setUint32(16, offset, true)
	const output = Buffer.concat([...locals, ...central, end])
	return output.buffer.slice(
		output.byteOffset,
		output.byteOffset + output.byteLength
	)
}
function archive(items, options) {
	return zip(items, options)
}
const types =
	'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types" />'
test('bounded DOCX preview renders paragraphs and table cells as inert data', async () => {
	const data = archive([
		['[Content_Types].xml', types],
		[
			'word/document.xml',
			'<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>&lt;script&gt;课程&lt;/script&gt;</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>成员</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>',
		],
	])
	const model = await parseOfficePreview(data, '资料.docx')
	assert.equal(model.kind, 'document')
	assert.equal(model.blocks[0].text, '<script>课程</script>')
	assert.deepEqual(model.blocks[1].rows, [['成员']])
})
test('PowerPoint pages follow numeric slide order and do not render active XML', async () => {
	const slide = (text) =>
		`<p:sld xmlns:p="p" xmlns:a="a"><p:cSld><a:p><a:r><a:t>${text}</a:t></a:r></a:p></p:cSld></p:sld>`
	const data = archive([
		['[Content_Types].xml', types],
		['ppt/presentation.xml', '<p:presentation xmlns:p="p"/>'],
		['ppt/slides/slide10.xml', slide('十')],
		['ppt/slides/slide2.xml', slide('二')],
	])
	const model = await parseOfficePreview(data, '讲义.pptx')
	assert.deepEqual(
		model.pages.map((page) => page.paragraphs),
		[['二'], ['十']]
	)
})
test('PowerPoint follows presentation relationship order after slides are rearranged', async () => {
	const slide = (text) =>
		`<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>${text}</a:t></a:r></a:p></p:sld>`
	const data = archive([
		['[Content_Types].xml', types],
		[
			'ppt/presentation.xml',
			'<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst><p:sldId r:id="r2"/><p:sldId r:id="r1"/></p:sldIdLst></p:presentation>',
		],
		[
			'ppt/_rels/presentation.xml.rels',
			'<Relationships><Relationship Id="r1" Target="slides/slide1.xml"/><Relationship Id="r2" Target="slides/slide2.xml"/></Relationships>',
		],
		['ppt/slides/slide1.xml', slide('一')],
		['ppt/slides/slide2.xml', slide('二')],
	])
	assert.deepEqual(
		(await parseOfficePreview(data, 'reordered.pptx')).pages.map(
			(page) => page.paragraphs
		),
		[['二'], ['一']]
	)
})
test('real XLSX cached values are bounded, multiple sheets remain selectable and formulas are not executed', async () => {
	const XLSX = await import('xlsx')
	const workbook = XLSX.utils.book_new()
	XLSX.utils.book_append_sheet(
		workbook,
		XLSX.utils.aoa_to_sheet([
			['姓名', '内容'],
			['成员', '<script>safe</script>'],
		]),
		'课程'
	)
	XLSX.utils.book_append_sheet(
		workbook,
		{
			A1: { t: 'n', v: 7, f: 'HYPERLINK("https://external.invalid",7)' },
			'!ref': 'A1',
		},
		'只读'
	)
	const buffer = XLSX.write(workbook, { type: 'array', bookType: 'xlsx' })
	const model = await parseOfficePreview(buffer, '资料.xlsx', { xlsx: XLSX })
	assert.deepEqual(
		model.sheets.map((sheet) => sheet.name),
		['课程', '只读']
	)
	assert.equal(model.sheets[0].rows[1][1], '<script>safe</script>')
	assert.equal(model.sheets[1].rows[0][0], '7')
})
test('real XLSX row clipping is reported using SheetJS full range metadata', async () => {
	const XLSX = await import('xlsx'),
		workbook = XLSX.utils.book_new()
	XLSX.utils.book_append_sheet(
		workbook,
		XLSX.utils.aoa_to_sheet(
			Array.from({ length: 1001 }, (_, index) => [`row ${index}`])
		),
		'大表'
	)
	const buffer = XLSX.write(workbook, { type: 'array', bookType: 'xlsx' })
	const model = await parseOfficePreview(buffer, 'large.xlsx', { xlsx: XLSX })
	assert.equal(model.sheets[0].rows.length, 1000)
	assert.equal(model.truncated, true)
})
test('ZIP preview rejects declared expansion, actual expansion, CRC mismatch and dangerous containers', async () => {
	for (const input of [
		archive([['word/document.xml', 'x']], { overrideSize: 5 * 1048576 }),
		archive([['word/document.xml', 'x'.repeat(5 * 1048576)]], {
			overrideSize: 1,
		}),
		archive([['../document.xml', 'x']]),
		archive([
			['word/document.xml', 'x'],
			['word/document.xml', 'y'],
		]),
	])
		await assert.rejects(() => validatePreviewZip(input))
	const crc = new Uint8Array(archive([['word/document.xml', 'safe']]))
	crc[14] ^= 1
	await assert.rejects(() => validatePreviewZip(crc.buffer))
})
test('XML documents containing DTD or excessive nesting are refused before rendering', async () => {
	for (const xml of [
		'<!DOCTYPE x [<!ENTITY e SYSTEM "file:///secret">]><w:document>&e;</w:document>',
		'<a>'.repeat(70) + 'nested' + '</a>'.repeat(70),
	])
		await assert.rejects(() =>
			parseOfficePreview(
				archive([
					['[Content_Types].xml', types],
					['word/document.xml', xml],
				]),
				'x.docx'
			)
		)
})
test('CSV quoting is read as values, never formulas or markup, and retains multiline fields', async () => {
	const model = await parseOfficePreview(
		encode('姓名,内容\n学生,"第一行\n第二行"\n=SUM(A1),"<img src=x>"').buffer,
		'表.csv'
	)
	assert.equal(model.kind, 'workbook')
	assert.deepEqual(model.sheets[0].rows[1], ['学生', '第一行\n第二行'])
	assert.equal(model.sheets[0].rows[2][0], '=SUM(A1)')
})
