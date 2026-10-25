import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileTemplate } from 'vue/compiler-sfc'
import { compileString } from 'sass'
import postcss from 'postcss'

const read = (path) =>
	readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const main = postcss.parse(await read('styles/main.css'))
const motion = postcss.parse(await read('styles/workspace-interactions.css'))
const tokens = {}
main.walkRules(':root', (rule) => {
	rule.walkDecls((decl) => (tokens[decl.prop] = decl.value))
})
const paths = [
	'layouts/file.vue',
	'components/layout/Header.vue',
	'components/file/FileSelectionToolbar.vue',
	'components/file/view/CardFileView.vue',
	'components/file/view/TableFileView.vue',
	'components/file/ZUpload.vue',
	'pages/file.vue',
]
const components = await Promise.all(
	paths.map(async (path) => ({
		path,
		...parse(await read(path)),
	}))
)

test('operational palette follows the charcoal, grey and paper login reference', () => {
	assert.equal(tokens['--cloud-frame'], '#1c1d20')
	assert.equal(tokens['--cloud-panel'], '#dedede')
	assert.equal(tokens['--cloud-surface'], '#fff')
	assert.equal(tokens['--cloud-primary'], '#262629')
	assert.equal(tokens['--cloud-accent'], '#6d42f6')
	assert.equal(tokens['--el-color-primary'], tokens['--cloud-primary'])
	assert.equal(tokens['--el-text-color-primary'], tokens['--cloud-ink'])
	assert.equal(tokens['--cloud-radius'], '20px')
})

test('small text retains readable contrast on the reference grey heading panel', () => {
	const luminance = (hex) => {
		const channels = hex
			.slice(1)
			.match(/.{2}/g)
			.map((channel) => parseInt(channel, 16) / 255)
		const linear = channels.map((value) =>
			value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
		)
		return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
	}
	const paper = luminance(tokens['--cloud-panel'])
	for (const name of ['--cloud-ink', '--cloud-muted', '--cloud-accent-ink']) {
		const ink = luminance(tokens[name])
		assert.ok(
			(Math.max(paper, ink) + 0.05) / (Math.min(paper, ink) + 0.05) >= 4.5,
			name
		)
	}
})

test('new shared feedback is limited to operational controls, never authentication or preview internals', () => {
	assert.doesNotMatch(
		motion.toString(),
		/career-login|cloud-auth|LoginCharacters|svg\s*\{/
	)
	const rules = []
	motion.walkRules((rule) => rules.push(rule.selector))
	assert.ok(rules.some((selector) => selector.includes('.cloud-workspace')))
	assert.ok(
		rules.some((selector) =>
			selector.includes(':not(.is-disabled):not(.is-loading):active')
		)
	)
	assert.doesNotMatch(
		motion.toString(),
		/transition:\s*all|\binfinite\b|animation-delay/
	)
})

test('panel entrance cannot create a permanent containing block for inline fixed dialogs', () => {
	const keyframes = []
	motion.walkAtRules('keyframes', (rule) => keyframes.push(rule))
	assert.ok(keyframes.length > 0)
	for (const rule of keyframes) {
		rule.walkDecls((decl) => assert.notEqual(decl.prop, 'transform'))
	}
	const entrances = []
	motion.walkDecls('animation', (decl) => entrances.push(decl.value))
	assert.ok(entrances.length > 0)
	for (const value of entrances)
		assert.doesNotMatch(value, /\bboth\b|\bforwards\b/)
})

test('hover movement is fine-pointer gated and reduced motion disables operational button transforms', () => {
	const media = []
	motion.walkAtRules('media', (rule) => media.push(rule))
	assert.ok(
		media.some((rule) => rule.params === '(hover: hover) and (pointer: fine)')
	)
	const reduced = media.find(
		(rule) => rule.params === '(prefers-reduced-motion: reduce)'
	)
	assert.ok(reduced)
	assert.match(reduced.toString(), /transform: none !important/)
	assert.ok(
		media.some(
			(rule) => rule.params === '(prefers-reduced-motion: no-preference)'
		)
	)
})

test('all themed file components keep valid Vue templates and compilable scoped styles', () => {
	for (const { path, descriptor, errors } of components) {
		assert.deepEqual(errors, [], path)
		assert.deepEqual(
			compileTemplate({
				source: descriptor.template.content,
				filename: path,
				id: 'workspace-theme',
			}).errors,
			[],
			path
		)
		for (const style of descriptor.styles) {
			const css =
				style.lang === 'scss' ? compileString(style.content).css : style.content
			assert.doesNotThrow(() => postcss.parse(css), path)
		}
	}
})

test('file frame has bounded desktop and mobile height and keeps header controls reachable', () => {
	const frame = components.find((item) => item.path === 'layouts/file.vue')
		.descriptor.styles[0].content
	const header = components.find(
		(item) => item.path === 'components/layout/Header.vue'
	).descriptor.styles[0].content
	assert.match(frame, /height: calc\(100% - 32px\)/)
	assert.match(frame, /height: calc\(100% - 16px\)/)
	assert.match(frame, /overflow: hidden/)
	assert.match(
		header,
		/@media only screen and \(max-width: 767px\)[\s\S]*height: 64px;[\s\S]*min-height: 64px;/
	)
	const page = components.find((item) => item.path === 'pages/file.vue')
		.descriptor.styles[0].content
	assert.match(
		page,
		/\.cloud-drive-actions\s*\{\s*width: 100%;\s*flex-wrap: wrap;/
	)
	assert.match(
		page,
		/\.cloud-drive-actions \.el-button\s*\{\s*flex: 1 1 120px;/
	)
})

test('file cards preserve selected, keyboard and upload illustration sizing states', () => {
	const card = components.find((item) =>
		item.path.endsWith('CardFileView.vue')
	).descriptor
	const upload = components.find((item) =>
		item.path.endsWith('ZUpload.vue')
	).descriptor
	assert.match(
		card.template.content,
		/:aria-pressed="selectRows.includes\(item\)"/
	)
	assert.match(
		card.styles[0].content,
		/\.select-row[\s\S]*var\(--cloud-accent\)/
	)
	assert.match(card.styles[0].content, /&:focus-visible/)
	assert.match(card.styles[0].content, /prefers-reduced-motion: reduce/)
	assert.match(
		upload.styles[0].content,
		/\.upload-dropzone-icon\s*\{[^}]*width: 80px;[^}]*height: 72px;/
	)
})
