// This parser returns plain, bounded data. It never renders package HTML, runs
// formulas/macros, loads relationships, or follows an embedded resource URL.
const MiB = 1048576
const LIMITS = {
	bytes: 10 * MiB,
	expanded: 20 * MiB,
	entry: 4 * MiB,
	entries: 1000,
	nodes: 100000,
	text: 250000,
	cells: 20000,
}
const invalid = (message = '文档内容无效或超过预览预算，请下载后查看') =>
	new Error(message)
const utf8 = (bytes) => new TextDecoder('utf-8', { fatal: true }).decode(bytes)
const crcTable = new Uint32Array(256).map((_, index) => {
	let value = index
	for (let bit = 0; bit < 8; bit++)
		value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1
	return value >>> 0
})
export function previewCrc32(bytes) {
	let crc = 0xffffffff
	for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8)
	return (crc ^ 0xffffffff) >>> 0
}
async function inflateBounded(bytes, limit) {
	const Decompressor = globalThis.DecompressionStream
	if (typeof Decompressor !== 'function')
		throw invalid('浏览器不支持本地文档解压，请升级浏览器或下载后查看')
	const reader = new Blob([bytes])
		.stream()
		.pipeThrough(new Decompressor('deflate-raw'))
		.getReader()
	const chunks = []
	let size = 0
	try {
		for (;;) {
			const { done, value } = await reader.read()
			if (done) break
			size += value.byteLength
			if (size > limit) throw invalid()
			chunks.push(value)
		}
	} catch (error) {
		await reader.cancel().catch(() => {})
		throw error
	} finally {
		reader.releaseLock()
	}
	const result = new Uint8Array(size)
	let offset = 0
	for (const chunk of chunks) {
		result.set(chunk, offset)
		offset += chunk.length
	}
	return result
}

/** Validate both ZIP directories and actual decompression before a format parser. */
export async function validatePreviewZip(
	buffer,
	{ inflate = inflateBounded } = {}
) {
	if (
		!(buffer instanceof ArrayBuffer) ||
		buffer.byteLength < 22 ||
		buffer.byteLength > LIMITS.bytes
	)
		throw invalid()
	const bytes = new Uint8Array(buffer),
		view = new DataView(buffer)
	let end = -1
	for (
		let at = bytes.length - 22;
		at >= Math.max(0, bytes.length - 65557);
		at--
	)
		if (
			view.getUint32(at, true) === 0x06054b50 &&
			at + 22 + view.getUint16(at + 20, true) === bytes.length
		) {
			end = at
			break
		}
	if (end < 0 || view.getUint16(end + 4, true) || view.getUint16(end + 6, true))
		throw invalid()
	const count = view.getUint16(end + 10, true),
		directorySize = view.getUint32(end + 12, true),
		start = view.getUint32(end + 16, true)
	if (
		!count ||
		count > LIMITS.entries ||
		view.getUint16(end + 8, true) !== count ||
		start + directorySize !== end
	)
		throw invalid()
	let cursor = start,
		expanded = 0
	const seen = new Set(),
		ranges = [],
		records = []
	for (let index = 0; index < count; index++) {
		if (cursor + 46 > end || view.getUint32(cursor, true) !== 0x02014b50)
			throw invalid()
		const flags = view.getUint16(cursor + 8, true),
			method = view.getUint16(cursor + 10, true),
			crc = view.getUint32(cursor + 16, true)
		const compressed = view.getUint32(cursor + 20, true),
			size = view.getUint32(cursor + 24, true),
			nameSize = view.getUint16(cursor + 28, true)
		const extra = view.getUint16(cursor + 30, true),
			comment = view.getUint16(cursor + 32, true),
			offset = view.getUint32(cursor + 42, true)
		if (
			flags & 1 ||
			![0, 8].includes(method) ||
			size > LIMITS.entry ||
			compressed > LIMITS.bytes ||
			cursor + 46 + nameSize + extra + comment > end ||
			view.getUint16(cursor + 34, true)
		)
			throw invalid()
		const name = utf8(bytes.subarray(cursor + 46, cursor + 46 + nameSize))
		if (
			!name ||
			name.length > 512 ||
			// eslint-disable-next-line no-control-regex -- ZIP paths must not contain control characters.
			/[\\\u0000-\u001f]/.test(name) ||
			name.startsWith('/') ||
			name.split('/').some((part) => part === '..' || part === '.') ||
			seen.has(name)
		)
			throw invalid()
		seen.add(name)
		expanded += size
		if (
			expanded > LIMITS.expanded ||
			offset + 30 > start ||
			view.getUint32(offset, true) !== 0x04034b50
		)
			throw invalid()
		const localNameSize = view.getUint16(offset + 26, true),
			localExtra = view.getUint16(offset + 28, true)
		const dataStart = offset + 30 + localNameSize + localExtra,
			dataEnd = dataStart + compressed
		if (
			view.getUint16(offset + 6, true) !== flags ||
			view.getUint16(offset + 8, true) !== method ||
			dataEnd > start ||
			localNameSize !== nameSize ||
			utf8(bytes.subarray(offset + 30, offset + 30 + localNameSize)) !== name
		)
			throw invalid()
		if (
			!(flags & 8) &&
			(view.getUint32(offset + 14, true) !== crc ||
				view.getUint32(offset + 18, true) !== compressed ||
				view.getUint32(offset + 22, true) !== size)
		)
			throw invalid()
		ranges.push([offset, dataEnd])
		records.push({ name, method, crc, size, compressed, dataStart, dataEnd })
		cursor += 46 + nameSize + extra + comment
	}
	if (cursor !== end) throw invalid()
	ranges.sort((a, b) => a[0] - b[0])
	if (ranges.some((range, index) => index && range[0] < ranges[index - 1][1]))
		throw invalid()
	const files = new Map()
	let actualExpanded = 0,
		xmlNodes = 0
	for (const record of records) {
		const input = bytes.subarray(record.dataStart, record.dataEnd)
		const output =
			record.method === 0
				? input
				: await inflate(input, Math.min(LIMITS.entry, record.size))
		actualExpanded += output.length
		if (
			output.length !== record.size ||
			actualExpanded > LIMITS.expanded ||
			previewCrc32(output) !== record.crc
		)
			throw invalid()
		if (record.name.endsWith('.xml') || record.name.endsWith('.rels')) {
			const text = utf8(output)
			if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw invalid()
			xmlNodes += (text.match(/</g) || []).length
			if (xmlNodes > LIMITS.nodes) throw invalid()
			files.set(record.name, text)
		}
	}
	if (!files.has('[Content_Types].xml')) throw invalid()
	return files
}

function entity(text) {
	return text.replace(
		/&(#x[\da-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g,
		(_all, value) => {
			const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }
			if (named[value]) return named[value]
			const code = value.startsWith('#x')
				? parseInt(value.slice(2), 16)
				: Number(value.slice(1))
			if (
				!Number.isInteger(code) ||
				code < 1 ||
				code > 0x10ffff ||
				(code >= 0xd800 && code <= 0xdfff)
			)
				throw invalid()
			return String.fromCodePoint(code)
		}
	)
}
// A bounded non-rendering XML reader. Attributes are deliberately ignored: no
// relationship target, stylesheet, script, image URL or hyperlink is returned.
function xmlTree(text) {
	if (typeof text !== 'string' || /<!DOCTYPE|<!ENTITY/i.test(text))
		throw invalid()
	const root = { name: 'root', children: [] },
		stack = [root]
	let position = 0,
		nodes = 0
	const tokens =
		/<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[[\s\S]*?\]\]>|<\/?[A-Za-z_][^>]*>|[^<]+/g
	for (const match of text.matchAll(tokens)) {
		if (match.index !== position) throw invalid()
		position += match[0].length
		const token = match[0]
		if (token.startsWith('<!--') || token.startsWith('<?')) continue
		if (token.startsWith('</')) {
			const name = token.slice(2, -1).trim()
			if (stack.length < 2 || stack.at(-1).fullName !== name) throw invalid()
			stack.pop()
			continue
		}
		if (token.startsWith('<') && !token.startsWith('<![CDATA[')) {
			const fullName = /^<([\w:.-]+)/.exec(token)?.[1]
			if (!fullName || ++nodes > LIMITS.nodes || stack.length > 64)
				throw invalid()
			const attributes = {}
			for (const attribute of token.matchAll(
				/\s([\w:.-]+)\s*=\s*("[^"]*"|'[^']*')/g
			)) {
				if (['r:id', 'Id', 'Target', 'TargetMode'].includes(attribute[1]))
					attributes[attribute[1]] = entity(attribute[2].slice(1, -1))
			}
			const node = {
				name: fullName.split(':').at(-1),
				fullName,
				attributes,
				children: [],
			}
			stack.at(-1).children.push(node)
			if (!token.endsWith('/>')) stack.push(node)
		} else {
			const current = stack.at(-1)
			current.children.push({
				text: token.startsWith('<![CDATA[')
					? token.slice(9, -3)
					: entity(token),
			})
		}
	}
	if (position !== text.length || stack.length !== 1) throw invalid()
	return root
}
const descendants = (node, name) => {
	const result = []
	const walk = (current) => {
		if (current.name === name) result.push(current)
		else current.children?.forEach(walk)
	}
	walk(node)
	return result
}
function paragraph(node) {
	let text = ''
	const walk = (current) => {
		if (
			['instrText', 'drawing', 'pict', 'object', 'altChunk'].includes(
				current.name
			)
		)
			return
		if (current.name === 't')
			text += current.children
				.filter((child) => child.text != null)
				.map((child) => child.text)
				.join('')
		else if (['br', 'cr'].includes(current.name)) text += '\n'
		else if (current.name === 'tab') text += '\t'
		else current.children?.forEach(walk)
	}
	walk(node)
	return text
}
function csvModel(text, separator) {
	const rows = [],
		row = []
	let cell = '',
		quoted = false,
		textBytes = 0,
		cells = 0,
		truncated = false
	const add = () => {
		textBytes += cell.length
		cells++
		if (cells > LIMITS.cells || textBytes > LIMITS.text || row.length >= 100) {
			truncated = true
			return false
		}
		row.push(cell.slice(0, 2000))
		cell = ''
		return true
	}
	for (let index = 0; index <= text.length; index++) {
		const character = text[index]
		if (character === '"') {
			if (quoted && text[index + 1] === '"') {
				cell += '"'
				index++
			} else quoted = !quoted
		} else if (
			!quoted &&
			(character === separator || character === '\n' || character === undefined)
		) {
			if (!add()) break
			if (character !== separator) {
				rows.push([...row])
				row.length = 0
				if (rows.length >= 1000) {
					truncated = index < text.length
					break
				}
			}
		} else if (character !== '\r' || quoted) cell += character ?? ''
	}
	if (quoted) throw invalid('CSV 引号不完整，请下载后检查文件')
	return { kind: 'workbook', sheets: [{ name: '数据', rows }], truncated }
}

export async function parseOfficePreview(buffer, filename, options = {}) {
	const extension = filename.toLowerCase().split('.').at(-1)
	if (['csv', 'tsv'].includes(extension)) {
		if (buffer.byteLength > MiB) throw invalid()
		return csvModel(
			utf8(new Uint8Array(buffer)),
			extension === 'tsv' ? '\t' : ','
		)
	}
	if (!['docx', 'xlsx', 'pptx'].includes(extension))
		throw invalid('此格式暂不支持本地预览，请下载后查看')
	const files = await validatePreviewZip(buffer, options)
	if (extension === 'docx') {
		const tree = xmlTree(files.get('word/document.xml')),
			body = descendants(tree, 'body')[0]
		if (!body) throw invalid()
		const blocks = []
		let length = 0,
			truncated = false,
			cells = 0
		const take = (text, max = 10000) => {
			const output = text.slice(
				0,
				Math.max(0, Math.min(max, LIMITS.text - length))
			)
			if (output.length < text.length) truncated = true
			length += output.length
			return output
		}
		for (const node of body.children) {
			if (
				blocks.length >= 2000 ||
				length >= LIMITS.text ||
				cells >= LIMITS.cells
			) {
				truncated = true
				break
			}
			if (node.name === 'p')
				blocks.push({ type: 'paragraph', text: take(paragraph(node)) })
			if (node.name === 'tbl') {
				const allRows = descendants(node, 'tr'),
					rows = []
				if (allRows.length > 100) truncated = true
				for (const row of allRows.slice(0, 100)) {
					const values = [],
						allCells = descendants(row, 'tc')
					if (allCells.length > 30) truncated = true
					for (const cell of allCells.slice(0, 30)) {
						if (cells >= LIMITS.cells || length >= LIMITS.text) {
							truncated = true
							break
						}
						values.push(
							take(descendants(cell, 'p').map(paragraph).join('\n'), 2000)
						)
						cells++
					}
					rows.push(values)
					if (cells >= LIMITS.cells || length >= LIMITS.text) break
				}
				blocks.push({ type: 'table', rows })
			}
		}
		return { kind: 'document', blocks, truncated }
	}
	if (extension === 'pptx') {
		if (!files.has('ppt/presentation.xml')) throw invalid()
		let slides = [...files.keys()]
			.filter((name) => /^ppt\/slides\/slide[1-9]\d*\.xml$/.test(name))
			.sort(
				(a, b) =>
					Number(/slide(\d+)/.exec(a)[1]) - Number(/slide(\d+)/.exec(b)[1])
			)
		const ids = descendants(xmlTree(files.get('ppt/presentation.xml')), 'sldId')
		if (ids.length) {
			const relationships = files.get('ppt/_rels/presentation.xml.rels')
			if (!relationships) throw invalid()
			const links = new Map(
				descendants(xmlTree(relationships), 'Relationship').map((node) => [
					node.attributes.Id,
					node.attributes,
				])
			)
			slides = ids.map((node) => {
				const link = links.get(node.attributes['r:id'])
				if (
					!link ||
					link.TargetMode === 'External' ||
					!/^slides\/slide[1-9]\d*\.xml$/.test(link.Target || '')
				)
					throw invalid()
				const target = 'ppt/' + link.Target
				if (!files.has(target)) throw invalid()
				return target
			})
		}
		if (!slides.length) throw invalid()
		let length = 0,
			truncated = slides.length > 100
		const pages = []
		for (const slide of slides.slice(0, 100)) {
			const nodes = descendants(xmlTree(files.get(slide)), 'p'),
				paragraphs = []
			if (nodes.length > 100) truncated = true
			for (const node of nodes.slice(0, 100)) {
				const text = paragraph(node),
					value = text.slice(
						0,
						Math.max(0, Math.min(10000, LIMITS.text - length))
					)
				if (value.length < text.length) truncated = true
				paragraphs.push(value)
				length += value.length
				if (length >= LIMITS.text) break
			}
			pages.push({ paragraphs })
			if (length >= LIMITS.text) {
				truncated = true
				break
			}
		}
		return { kind: 'slides', pages, truncated }
	}
	if (!files.has('xl/workbook.xml')) throw invalid()
	const XLSX = options.xlsx
	if (!XLSX) throw invalid('表格解析器不可用，请下载后查看')
	const workbook = XLSX.read(buffer, {
		type: 'array',
		cellHTML: false,
		cellFormula: false,
		bookVBA: false,
		cellStyles: false,
		sheetRows: 1000,
	})
	let cells = 0,
		length = 0,
		truncated = workbook.SheetNames.length > 50
	const sheets = []
	for (const name of workbook.SheetNames.slice(0, 50)) {
		const sheet = workbook.Sheets[name],
			ref = sheet?.['!ref']
		if (!ref) {
			sheets.push({ name, rows: [] })
			continue
		}
		const range = XLSX.utils.decode_range(ref)
		// sheetRows clips !ref before we read it; !fullref retains the original
		// worksheet extent, including rows not materialized by the parser.
		const fullRange = XLSX.utils.decode_range(sheet['!fullref'] || ref)
		if (
			fullRange.e.r > range.e.r ||
			fullRange.e.c > range.e.c ||
			fullRange.s.r < range.s.r ||
			fullRange.s.c < range.s.c
		)
			truncated = true
		if (range.e.r - range.s.r >= 1000 || range.e.c - range.s.c >= 100)
			truncated = true
		range.e.r = Math.min(range.e.r, range.s.r + 999)
		range.e.c = Math.min(range.e.c, range.s.c + 99)
		const values = XLSX.utils.sheet_to_json(sheet, {
			header: 1,
			range,
			raw: false,
			defval: '',
			blankrows: false,
		})
		const rows = []
		for (const row of values) {
			const output = []
			for (const value of row) {
				const cell = String(value ?? '').slice(0, 2000)
				cells++
				length += cell.length
				if (cells > LIMITS.cells || length > LIMITS.text) {
					truncated = true
					break
				}
				output.push(cell)
			}
			if (cells > LIMITS.cells || length > LIMITS.text) break
			rows.push(output)
		}
		sheets.push({ name: String(name).slice(0, 100), rows })
		if (cells > LIMITS.cells || length > LIMITS.text) break
	}
	return { kind: 'workbook', sheets, truncated }
}
