/** Only a browser request already same-origin with Vite may have its Origin rewritten upstream. */
export function isSameOriginSupportUpgrade(headers = {}, encrypted = false) {
	if (typeof headers.origin !== 'string' || typeof headers.host !== 'string')
		return false
	try {
		const origin = new URL(headers.origin)
		const host = new URL(`${encrypted ? 'https:' : 'http:'}//${headers.host}`)
		return (
			!origin.username &&
			!origin.password &&
			!host.username &&
			!host.password &&
			host.pathname === '/' &&
			!host.search &&
			!host.hash &&
			origin.pathname === '/' &&
			!origin.search &&
			!origin.hash &&
			origin.origin === host.origin
		)
	} catch {
		return false
	}
}
