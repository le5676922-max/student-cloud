// Used only to scope an unfinished login check. Never persisted, logged or put in a URL.
export function navigationCredentialIdentity({
	storage,
	cookie = '',
	baseUrl = '',
}) {
	const name = 'tongligong-student-cloud-token'
	let token
	try {
		token = storage?.getItem(name)
	} catch {
		// The existing HTTP interceptor also falls back to the visible session cookie.
	}
	if (!token)
		token = String(cookie)
			.split(';')
			.map((part) => part.trim())
			.find((part) => part.startsWith(`${name}=`))
			?.slice(name.length + 1)
	return token ? JSON.stringify([baseUrl, token]) : ''
}
