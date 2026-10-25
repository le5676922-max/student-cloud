import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import {
	parse,
	compileScript,
	compileTemplate,
	compileStyle,
} from 'vue/compiler-sfc'
import * as chartHelpers from '../src/utils/admin-overview-charts.mjs'

const fixture = () => ({
	members: 8,
	workspaceAdmins: 2,
	storageSources: 4,
	workspaces: 3,
	statuses: { enabledStorages: 3, enabledWorkspaces: 2 },
	loginLoggingEnabled: true,
	timeZone: 'Asia/Shanghai',
	dailyLogins: [
		{ date: '2026-10-24', successes: 4, failures: 1 },
		{ date: '2026-10-25', successes: 8, failures: 2 },
	],
})

test('global distributions preserve actual totals and reject missing or inconsistent statistics', () => {
	const value = chartHelpers.overviewDistributions(fixture())
	assert.deepEqual(
		value.map((chart) => chart.items.map((item) => item.value)),
		[
			[2, 6],
			[3, 1],
			[2, 1],
		]
	)
	assert.ok(
		chartHelpers
			.overviewDistributions({})
			.every((chart) => chart.total === null)
	)
	assert.equal(
		chartHelpers.overviewDistributions({ members: 1, workspaceAdmins: 2 })[0]
			.total,
		null
	)
	assert.equal(
		chartHelpers.overviewDistributions({ members: 8, workspaceAdmins: NaN })[0]
			.total,
		null
	)
})
test('rings have a complete bounded circumference and zero never fabricates a sector', () => {
	const items = chartHelpers.overviewDistributions(fixture())[0].items
	const segments = chartHelpers.ringSegments(items)
	assert.equal(segments[0].percent, 25)
	assert.equal(segments[1].percent, 75)
	assert.equal(segments[1].offset, -25)
	assert.deepEqual(chartHelpers.ringSegments([{ value: 0 }, { value: 0 }]), [])
	assert.deepEqual(chartHelpers.ringSegments([{ value: null }]), [])
})
test('trend coordinates stay finite and missing daily counts are not plotted as zero', () => {
	const chart = chartHelpers.overviewTrendSeries(fixture().dailyLogins)
	for (const series of chart.series)
		for (const point of series.points) {
			assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y))
			assert.ok(point.y >= 28 && point.y <= 132)
		}
	assert.equal(
		chartHelpers.overviewTrendSeries([
			{ date: '2026-10-25', successes: null, failures: 0 },
		]),
		null
	)
	assert.equal(chartHelpers.overviewTrendSeries([]), null)
	assert.equal(
		chartHelpers.overviewTrendSeries([
			{ date: 'invalid', successes: 0, failures: 0 },
		]),
		null
	)
})

const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of [
	'window',
	'document',
	'Node',
	'Element',
	'HTMLElement',
	'SVGElement',
])
	globalThis[key] = dom.window[key]
const vue = await import('vue')
after(() => dom.window.close())
const descriptor = parse(
	await readFile(
		new URL('../src/components/admin/AdminSummaryCharts.vue', import.meta.url),
		'utf8'
	)
).descriptor
const compiled = compileScript(descriptor, { id: 'admin-summary-charts-test' })
const template = compileTemplate({
	source: descriptor.template.content,
	filename: 'AdminSummaryCharts.vue',
	id: 'admin-summary-charts-test',
	compilerOptions: { bindingMetadata: compiled.bindings },
})
assert.deepEqual(template.errors, [])
assert.deepEqual(
	compileStyle({
		source: descriptor.styles[0].content,
		filename: 'AdminSummaryCharts.vue',
		id: 'charts',
		scoped: true,
	}).errors,
	[]
)
const modules = { vue, '~/utils/admin-overview-charts.mjs': chartHelpers }
const transform = (code) =>
	code.replace(
		/import\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"];?/g,
		(_all, names, path) =>
			`const {${names.replace(/\s+as\s+/g, ': ')}} = modules[${JSON.stringify(
				path
			)}];`
	)
const Component = new Function(
	'modules',
	transform(compiled.content).replace('export default', 'return')
)(modules)
Component.render = new Function(
	'modules',
	transform(template.code).replace(
		'export function render',
		'return function render'
	)
)(modules)

test('actual dashboard chart controls switch to a semantic table and highlight selected legend without fetching', async () => {
	const root = document.createElement('div')
	document.body.append(root)
	const app = vue.createApp(Component, { overview: fixture() })
	try {
		app.mount(root)
		assert.equal(root.querySelectorAll('.chart-card').length, 4)
		assert.equal(root.querySelectorAll('svg').length, 4)
		const legend = root.querySelector('.chart-legend button')
		legend.click()
		await vue.nextTick()
		assert.equal(legend.getAttribute('aria-pressed'), 'true')
		const button = [...root.querySelectorAll('button')].find(
			(node) => node.textContent === '数据明细'
		)
		button.click()
		await vue.nextTick()
		assert.equal(root.querySelectorAll('table').length, 4)
		assert.equal(root.querySelectorAll('svg').length, 0)
		assert.match(root.textContent, /2026-10-25/)
	} finally {
		app.unmount()
		root.remove()
	}
})
test('actual dashboard distinguishes a disabled login log from an empty successful series', () => {
	const root = document.createElement('div')
	document.body.append(root)
	const app = vue.createApp(Component, {
		overview: { ...fixture(), loginLoggingEnabled: false },
	})
	try {
		app.mount(root)
		assert.match(root.textContent, /登录日志未启用/)
		assert.equal(root.querySelector('.trend-card svg'), null)
	} finally {
		app.unmount()
		root.remove()
	}
})
