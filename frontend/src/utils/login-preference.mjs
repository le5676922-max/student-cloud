const KEY = 'student-cloud-login-storage'

function preference(remember, username) {
	return {
		remember: remember === true,
		username:
			remember === true && typeof username === 'string'
				? username.trim().slice(0, 256)
				: '',
	}
}

/** Rewrite old records on entry so passwords saved by earlier versions are removed. */
export function loadLoginPreference(storage) {
	try {
		const saved = JSON.parse(storage.getItem(KEY) || '{}')
		const safe = preference(saved?.remember, saved?.username)
		storage.setItem(KEY, JSON.stringify(safe))
		return safe
	} catch {
		try {
			storage.removeItem(KEY)
		} catch {
			/* Storage may be disabled by the browser. */
		}
		return preference(false, '')
	}
}

export function saveLoginPreference(storage, remember, username) {
	try {
		storage.setItem(KEY, JSON.stringify(preference(remember, username)))
	} catch {
		// Remembering a username is optional and must not prevent sign-in.
	}
}
