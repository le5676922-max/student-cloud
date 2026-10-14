import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('upload illustration has local explicit dimensions instead of its intrinsic SVG size', async () => {
	const component = await readFile(
		new URL('../src/components/file/ZUpload.vue', import.meta.url),
		'utf8'
	)
	assert.match(
		component,
		/<i-custom-upload class="upload-dropzone-icon" aria-hidden="true"/
	)
	const iconRule = component.match(/\.upload-dropzone-icon\s*\{([^}]+)\}/)?.[1]
	assert.ok(iconRule)
	assert.match(iconRule, /width:\s*80px;/)
	assert.match(iconRule, /height:\s*72px;/)
	assert.match(iconRule, /max-width:\s*100%;/)
	assert.doesNotMatch(component, /<i-custom-upload[^>]+text-7xl/)
})
