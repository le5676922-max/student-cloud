import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileTemplate } from 'vue/compiler-sfc'
import { compileString } from 'sass'
import postcss from 'postcss'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8')
const navigation = parse(
	await read('../src/components/admin/AdminNavigation.vue')
).descriptor
const overview = parse(await read('../src/pages/admin/overview.vue')).descriptor
const adminCss = postcss.parse(
	compileString(await read('../src/styles/admin.scss')).css
)
const navCss = postcss.parse(navigation.styles[0].content)
const overviewCss = postcss.parse(overview.styles[0].content)
const declaration = (tree, selector, property) => {
	let value
	tree.walkRules(selector, (rule) => {
		if (rule.parent.type === 'root')
			rule.walkDecls(property, (decl) => (value = decl.value))
	})
	return value
}

test('the neutral console shell retains bounded desktop spacing and a paper content surface', () => {
	assert.equal(
		declaration(adminCss, '.admin-shell', 'background'),
		'var(--cloud-frame)'
	)
	assert.equal(declaration(adminCss, '.admin-shell', 'padding'), '12px')
	assert.equal(
		declaration(adminCss, '.admin-shell .admin-sidebar', 'background'),
		'var(--cloud-panel)'
	)
	assert.equal(
		declaration(adminCss, '.admin-shell .admin-content-panel', 'background'),
		'var(--cloud-surface)'
	)
	assert.equal(
		declaration(adminCss, '.admin-shell .admin-sidebar', 'overflow-x'),
		'hidden'
	)
	adminCss.walkAtRules('keyframes', (rule) => {
		if (rule.params === 'admin-panel-arrive') {
			rule.walkDecls('transform', () =>
				assert.fail(
					'a panel containing dialogs must not become a fixed-position containing block'
				)
			)
		}
	})
	adminCss.walkDecls('animation', (decl) => {
		if (decl.value.includes('admin-panel-arrive'))
			assert.doesNotMatch(decl.value, /\b(?:both|forwards)\b/)
	})
})

test('console navigation keeps current-page semantics while folding groups with a local transition', () => {
	assert.match(
		navigation.template.content,
		/<Transition name="navigation-fold">/
	)
	assert.match(
		navigation.template.content,
		/:aria-expanded="expanded.includes\(group.id\)"/
	)
	assert.match(
		navigation.template.content,
		/:aria-controls="`\$\{instanceId\}-\$\{group.id\}`"/
	)
	assert.match(
		navigation.template.content,
		/:aria-current="active\(item\) \? 'page' : undefined"/
	)
	assert.equal(declaration(navCss, '.navigation-item.active', 'color'), '#fff')
	assert.equal(
		declaration(navCss, '.navigation-item.active', 'background'),
		'var(--cloud-primary)'
	)
})

test('console motion is bounded to fine pointers and offers reduced-motion alternatives', () => {
	for (const css of [navCss, overviewCss]) {
		const media = []
		css.walkAtRules('media', (rule) => media.push(rule.params))
		assert.ok(media.includes('(hover: hover) and (pointer: fine)'))
		assert.ok(media.includes('(prefers-reduced-motion: reduce)'))
	}
	assert.match(adminCss.toString(), /prefers-reduced-motion: no-preference/)
	assert.match(overviewCss.toString(), /prefers-reduced-motion: no-preference/)
	assert.doesNotMatch(overviewCss.toString(), /animation:[^;]*\binfinite\b/)
	assert.doesNotMatch(navCss.toString(), /animation:[^;]*\binfinite\b/)
})

test('console theme templates compile without changing overview unknown-data and request hooks', () => {
	for (const [name, descriptor] of [
		['AdminNavigation.vue', navigation],
		['overview.vue', overview],
	]) {
		const compiled = compileTemplate({
			source: descriptor.template.content,
			filename: name,
			id: 'admin-theme-layout',
		})
		assert.deepEqual(compiled.errors, [])
	}
	assert.match(
		overview.scriptSetup.content,
		/overviewGate\.isCurrent\(request\)/
	)
	assert.match(
		overview.scriptSetup.content,
		/workspaceGate\.isCurrent\(request\)/
	)
	assert.match(overview.template.content, /未获取的指标|趋势数据未返回/)
})
