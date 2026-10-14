// Vite 7 removed globEager. Resolve this small asset map once per module.
const storageIcons = import.meta.glob('/src/assets/icons/storage-*.svg', {
	eager: true,
	import: 'default',
	query: '?url',
})

export function getStorageIcon(name) {
	return (
		storageIcons[`/src/assets/icons/storage-${name}.svg`] ||
		storageIcons['/src/assets/icons/storage-local.svg']
	)
}
