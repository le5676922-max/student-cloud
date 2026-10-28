import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'

// Runtime CSS remains authoritative; this check prevents its maintained mirror from drifting.
const root = new URL('../../', import.meta.url)
const design = await readFile(new URL('DESIGN.md', root), 'utf8')
const css = await readFile(
	new URL('frontend/src/styles/main.css', root),
	'utf8'
)
const contract = await readFile(new URL('UX-CONTRACT.md', root), 'utf8')
const mappings = {
	primary: '--cloud-primary',
	ink: '--cloud-ink',
	muted: '--cloud-muted',
	frame: '--cloud-frame',
	canvas: '--cloud-canvas',
	surface: '--cloud-surface',
	line: '--cloud-line',
	accent: '--cloud-accent',
}
const normalize = (value) =>
	value.toLowerCase().replace(/^#([\da-f])([\da-f])([\da-f])$/, '#$1$1$2$2$3$3')
for (const [name, variable] of Object.entries(mappings)) {
	const documented = design.match(
		new RegExp(`^  ${name}: "(#[\\da-fA-F]+)"`, 'm')
	)?.[1]
	const implemented = css.match(
		new RegExp(`${variable}:\\s*(#[\\da-fA-F]+);`)
	)?.[1]
	assert.ok(
		documented && implemented,
		`Missing documented/runtime token: ${name}`
	)
	assert.equal(
		normalize(documented),
		normalize(implemented),
		`DESIGN.md drift: ${name}`
	)
}
for (const capability of [
	'Select/Listbox',
	'Form',
	'Scrollbar',
	'Toast',
	'CRUD',
]) {
	assert.ok(
		contract.includes(`| ${capability} |`),
		`Unresolved canonical capability: ${capability}`
	)
}
assert.match(css, /scrollbar-color:\s*var\(--cloud-scroll-thumb\)/)
assert.match(css, /scrollbar-width:\s*thin/)
assert.match(css, /forced-colors:\s*active/)
assert.match(css, /prefers-reduced-motion:\s*reduce/)
console.log(
	'Design contract: 8 runtime color mappings, 5 canonical owners, scrollbars and reduced-motion verified.'
)
