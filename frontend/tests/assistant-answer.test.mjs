import assert from 'node:assert/strict'
import test from 'node:test'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('', { url: 'https://cloud.example/' })
globalThis.window = dom.window
globalThis.DOMParser = dom.window.DOMParser
const {
	assistantCoverageNotice,
	materialCitationLocation,
	normalizeAssistantContent,
	renderAssistantContent,
} = await import('../src/utils/assistant-answer.mjs')
const documentFor = (html) => new JSDOM(html).window.document

test('normalization removes edge blank lines without changing facts, indentation or fenced code', () => {
	const answer =
		'## 结果\n\n数值：0.0042，不是 42。\n\n```python\n  print("  unchanged  ")\n\n```'
	assert.equal(normalizeAssistantContent(`\n \n${answer}\n\n  `), answer)
	assert.equal(normalizeAssistantContent('  indented text'), '  indented text')
	assert.equal(normalizeAssistantContent('    code\n\n'), '    code\n\n')
	assert.equal(
		normalizeAssistantContent('```text\n  code\n\n'),
		'```text\n  code\n\n'
	)
	assert.equal(normalizeAssistantContent(null), '')
	assert.equal(normalizeAssistantContent('事实。'.repeat(12000)).length, 36000)
})

test('Markdown keeps readable headings, paragraphs, ordered starts, nested lists and exact code text', () => {
	const result = documentFor(
		renderAssistantContent(
			'# 结论\n\n第一行\n自然换行。\n\n3. 检查权限\n4. 查看资料\n   - 保留原文\n\n```js\n  const text = "<script>";\n\n  run(text);\n```'
		)
	)
	assert.equal(result.querySelector('h1').textContent, '结论')
	assert.equal(result.querySelector('p').textContent, '第一行\n自然换行。')
	assert.equal(result.querySelector('p br'), null)
	assert.equal(result.querySelector('ol').getAttribute('start'), '3')
	assert.equal(result.querySelectorAll('li').length, 3)
	assert.equal(
		result.querySelector('pre code').textContent,
		'  const text = "<script>";\n\n  run(text);\n'
	)
})

test('incomplete streamed Markdown waits for its line and final rendering includes every character', () => {
	const partial = '先检查权限。\n\n请打开 **课程'
	assert.equal(
		documentFor(
			renderAssistantContent(partial, { streaming: true })
		).body.textContent.trim(),
		'先检查权限。'
	)
	assert.equal(
		renderAssistantContent('[查看](https://exa', { streaming: true }),
		''
	)
	const complete = `${partial}资料**，然后重试。`
	const final = documentFor(renderAssistantContent(complete))
	assert.equal(final.querySelector('strong').textContent, '课程资料')
	assert.ok(final.body.textContent.includes('然后重试。'))
})

test('Markdown and raw HTML cannot add executable links, handlers or hidden unsafe elements', () => {
	const html = renderAssistantContent(
		'<script>alert(1)</script><style>body{display:none}</style><img src=x onerror=alert(1)><svg onload=alert(1)></svg><iframe src=x></iframe><form><input></form>\n\n[坏链接](javascript:alert%281%29) [安全链接](https://example.com/a?q=1)\n\n<a href="data:text/html,test" onclick="alert(1)">数据链接</a><h2 style="display:none" id="x">可见标题</h2>'
	)
	const result = documentFor(html)
	assert.equal(
		result.querySelector(
			'script,style,img,svg,iframe,form,input,[onclick],[onload],[onerror],[style],[id]'
		),
		null
	)
	assert.equal(result.querySelectorAll('a[href]').length, 1)
	const link = result.querySelector('a[href]')
	assert.equal(link.href, 'https://example.com/a?q=1')
	assert.equal(link.target, '_blank')
	assert.equal(link.rel, 'noopener noreferrer')
	assert.equal(result.querySelector('h2').textContent, '可见标题')
})

test('coverage notices use supplied limits and disclose missing text when only counts are returned', () => {
	assert.equal(assistantCoverageNotice(undefined), '')
	assert.equal(assistantCoverageNotice({ skipped: 0, truncated: false }), '')
	assert.equal(
		assistantCoverageNotice({
			skipped: 2,
			truncated: true,
			notice: '  2 份资料无法提取文本。  ',
		}),
		'2 份资料无法提取文本。'
	)
	const fallback = assistantCoverageNotice({ skipped: 2, truncated: true })
	assert.match(fallback, /2 份资料未能读取/)
	assert.match(fallback, /检索范围受限/)
	assert.match(fallback, /本轮可用片段/)
	assert.doesNotMatch(fallback, /全部课程|所有课程/)
})

test('resource elements are absent before DOMParser sees the answer while safe user links remain', () => {
	const originalParser = globalThis.DOMParser
	const parsedInputs = []
	globalThis.DOMParser = class {
		parseFromString(html, type) {
			parsedInputs.push(html)
			return new originalParser().parseFromString(html, type)
		}
	}
	try {
		const output = renderAssistantContent(
			'![remote image](https://remote.example/track?secret=synthetic)\n\n<img src="https://remote.example/a" srcset="https://remote.example/b 2x"><video poster="https://remote.example/poster"><source src="https://remote.example/video"></video><audio src="https://remote.example/audio"></audio><iframe src="https://remote.example/frame"></iframe><object data="https://remote.example/object"></object><svg><image href="https://remote.example/svg"></image></svg>\n\n[查看课程](https://cloud.example/learning)'
		)
		assert.equal(parsedInputs.length, 1)
		assert.doesNotMatch(
			parsedInputs[0],
			/<(?:img|video|audio|source|iframe|object|svg|image)\b|\b(?:src|srcset|poster|data)=|remote\.example/
		)
		assert.equal(
			documentFor(output).querySelector('a').href,
			'https://cloud.example/learning'
		)
	} finally {
		globalThis.DOMParser = originalParser
	}
})

test('citation locations retain exact large identifiers and source storage paths', () => {
	assert.equal(
		materialCitationLocation({
			courseId: '9223372036854775806',
			materialId: '9223372036854775805',
			storageKey: 'workspace-12',
			path: '/高等数学/第一章.pdf',
		}),
		'课程 9223372036854775806 · 资料 9223372036854775805 · workspace-12 · /高等数学/第一章.pdf'
	)
	assert.equal(materialCitationLocation({}), '')
})
