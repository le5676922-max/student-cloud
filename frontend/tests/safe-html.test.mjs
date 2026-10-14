import assert from 'node:assert/strict'
import test from 'node:test'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('')
globalThis.window = dom.window
const { sanitizeRichHtml } = await import('../src/utils/safe-html.mjs')

test('rich text removes scripts, event handlers and executable links', () => {
	const result = sanitizeRichHtml('<script>alert(1)</script><img src=x onerror=alert(1)><a href="javascript:alert(1)">bad</a><svg onload=alert(1)></svg><iframe src=x></iframe><form><input></form>')
	const doc = new JSDOM(result).window.document
	assert.equal(doc.querySelector('script,svg,iframe,form,[onerror],[onload],a[href]'), null)
})

test('rich text preserves useful Markdown structure and safe links', () => {
	const result = sanitizeRichHtml('<h2>标题</h2><ul><li>项目</li></ul><pre><code>example</code></pre><table><tr><td>表格</td></tr></table><a href="https://example.com">链接</a>')
	const doc = new JSDOM(result).window.document
	assert.equal(doc.querySelector('h2').textContent, '标题')
	assert.equal(doc.querySelectorAll('li,code,td').length, 3)
	assert.equal(doc.querySelector('a').getAttribute('href'), 'https://example.com')
	assert.equal(sanitizeRichHtml(null), '')
})
