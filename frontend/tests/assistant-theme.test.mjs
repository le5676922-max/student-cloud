import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { parse, compileStyle, compileTemplate } from 'vue/compiler-sfc'
import postcss from 'postcss'

const read = (file) =>
	readFile(new URL(`../src/components/ai/${file}`, import.meta.url), 'utf8')
const components = await Promise.all(
	[
		'AiAssistant.vue',
		'AiMascotSprite.vue',
		'AiMemoryPanel.vue',
		'AgentTaskCard.vue',
	].map(async (filename) => ({
		filename,
		descriptor: parse(await read(filename)).descriptor,
	}))
)
const assistant = components[0].descriptor
const stylesheet = postcss.parse(assistant.styles[0].content)

test('assistant theme templates and locally scoped styles compile', () => {
	for (const { filename, descriptor } of components) {
		assert.deepEqual(
			compileTemplate({
				source: descriptor.template.content,
				filename,
				id: 'assistant-theme',
			}).errors,
			[]
		)
		for (const style of descriptor.styles) {
			assert.equal(style.scoped, true)
			assert.deepEqual(
				compileStyle({
					source: style.content,
					filename,
					id: 'assistant-theme',
					scoped: true,
				}).errors,
				[]
			)
		}
	}
})

test('assistant surfaces and primary operations use the shared neutral theme', () => {
	for (const { descriptor } of components.filter(
		(component) => component.filename !== 'AiMascotSprite.vue'
	)) {
		assert.match(descriptor.styles[0].content, /var\(--cloud-surface\)/)
		assert.match(descriptor.styles[0].content, /var\(--cloud-accent\)/)
	}
	assert.match(
		assistant.styles[0].content,
		/\.ai-agent-send-button\s*\{[^}]*background: var\(--cloud-primary\)/
	)
})

test('memory overlay shares the actual assistant header height', () => {
	assert.match(assistant.styles[0].content, /--ai-agent-header-height: 64px/)
	assert.match(
		assistant.styles[0].content,
		/min-height: var\(--ai-agent-header-height\)/
	)
	assert.match(
		components[2].descriptor.styles[0].content,
		/inset: var\(--ai-agent-header-height, 64px\) 0 0/
	)
})

test('mascot decoration is CSS-only and new arrival motion is bounded and reducible', () => {
	const reduced = []
	stylesheet.walkAtRules('media', (rule) => {
		if (rule.params === '(prefers-reduced-motion: reduce)')
			reduced.push(rule.toString())
	})
	assert.equal(reduced.length, 1)
	assert.match(reduced[0], /\.ai-agent-mascot-stage::before/)
	assert.match(reduced[0], /\.ai-agent-message-row/)
	assert.match(reduced[0], /animation: none/)
	assert.match(reduced[0], /transform: none !important/)
	assert.match(
		assistant.styles[0].content,
		/animation: ai-agent-message-arrive 180ms var\(--cloud-ease\);/
	)
	assert.doesNotMatch(
		assistant.styles[0].content,
		/animation:\s*ai-agent-message-arrive[^;]*infinite/
	)
	assert.match(
		assistant.styles[0].content,
		/@media \(hover: hover\) and \(pointer: fine\)/
	)
	assert.match(
		assistant.styles[0].content,
		/\.ai-agent-floating-button:focus-visible/
	)
})

test('task status styling retains success and warning semantics rather than repainting all states', () => {
	const task = components[3].descriptor.styles[0].content
	assert.match(task, /\.status-running\s*\{[^}]*var\(--cloud-accent\)/)
	assert.match(task, /\.status-succeeded\s*\{[^}]*color: #287563/)
	assert.match(task, /\.status-interrupted\s*\{[^}]*color: #995536/)
	assert.match(
		assistant.template.content,
		/@click="isLoading \? stopGeneration\(\) : sendMessage\(\)"/
	)
	assert.match(
		assistant.template.content,
		/@drop.prevent.stop="handleAgentFileDrop"/
	)
})
