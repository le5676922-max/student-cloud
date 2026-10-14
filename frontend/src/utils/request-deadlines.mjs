// Bound management metadata reads without interrupting uploads, downloads or AI work.
export function metadataRequestTimeout(method, url, config = {}) {
	if (String(method).toLowerCase() !== 'get' || typeof url !== 'string')
		return 0
	if (config.responseType && config.responseType !== 'json') return 0
	if (/^\/admin(?:\/|$)/.test(url)) return 12000
	if (url === '/user/login/check') return 10000
	return 0
}
