import { readFile } from 'node:fs/promises'
import { relative, sep } from 'node:path'
import glob from 'fast-glob'
import type { Plugin } from 'vite'

// Bundles only trusted repository SVG assets. Keeps existing <svg-icon> IDs,
// without the obsolete svg-baker/PostCSS dependency chain.
export function createSvgIconsPlugin(options: {
	iconDirs: string[]
	symbolId: string
	customDomId: string
}): Plugin {
	const id = 'virtual:svg-icons-register'
	const resolvedId = '\0' + id
	const namesId = 'virtual:svg-icons-names'
	return {
		name: 'student-cloud-svg-sprite',
		resolveId(source) {
			if (source === id) return resolvedId
			if (source === namesId) return '\0' + namesId
		},
		async load(source) {
			if (source !== resolvedId && source !== '\0' + namesId) return
			const symbols: string[] = []
			const names: string[] = []
			for (const directory of options.iconDirs) {
				const files = (
					await glob('**/*.svg', { cwd: directory, absolute: true })
				).sort()
				for (const file of files) {
					this.addWatchFile(file)
					const parts = relative(directory, file).split(sep)
					const name = parts.pop()!.replace(/\.svg$/, '')
					const symbol = options.symbolId
						.replace('[dir]', parts.join('-'))
						.replace('[name]', name)
						.replace(/--+/g, '-')
					names.push(symbol)
					let svg = await readFile(file, 'utf8')
					// Gradient/clip IDs must be unique across the sprite.
					const ids = [...svg.matchAll(/\bid="([^"]+)"/g)].map(
						(match) => match[1]
					)
					for (const original of ids) {
						svg = svg
							.split(`id="${original}"`)
							.join(`id="${symbol}-${original}"`)
							.split(`url(#${original})`)
							.join(`url(#${symbol}-${original})`)
							.split(`href="#${original}"`)
							.join(`href="#${symbol}-${original}"`)
					}
					const match = svg.match(/<svg\b([^>]*)>([\s\S]*)<\/svg>/i)
					if (!match)
						throw new Error(`Invalid SVG asset: ${relative(directory, file)}`)
					const attributes = match[1].replace(
						/\s(?:id|width|height|xmlns(?::\w+)?)="[^"]*"/g,
						''
					)
					symbols.push(
						`<symbol id="${symbol}"${attributes}>${match[2]}</symbol>`
					)
				}
			}
			if (source === '\0' + namesId)
				return `export default ${JSON.stringify(names)}`
			return `const id = ${JSON.stringify(options.customDomId)};
if (!document.getElementById(id)) {
  const sprite = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  sprite.id = id; sprite.setAttribute('aria-hidden', 'true');
  sprite.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';
  sprite.innerHTML = ${JSON.stringify(symbols.join(''))};
  document.body.prepend(sprite);
}`
		},
	}
}
