import assert from 'node:assert/strict'
import test from 'node:test'
import { loadLoginPreference, saveLoginPreference } from '../src/utils/login-preference.mjs'

const key = 'student-cloud-login-storage'
function memoryStorage(value) {
	const data = new Map([[key, value]])
	return { getItem: (name) => data.get(name), setItem: (name, next) => data.set(name, next),
		removeItem: (name) => data.delete(name) }
}

test('migrates remembered usernames without retaining historical plaintext passwords', () => {
	const storage = memoryStorage(JSON.stringify({ remember: true, username: ' test-user ', password: 'old-password' }))
	assert.deepEqual(loadLoginPreference(storage), { remember: true, username: 'test-user' })
	assert.deepEqual(JSON.parse(storage.getItem(key)), { remember: true, username: 'test-user' })
})

test('unchecked remember clears old account data and newly saved preferences contain no password', () => {
	const storage = memoryStorage(JSON.stringify({ remember: false, username: 'stale', password: 'old-password' }))
	assert.deepEqual(loadLoginPreference(storage), { remember: false, username: '' })
	saveLoginPreference(storage, true, 'next-user')
	assert.deepEqual(JSON.parse(storage.getItem(key)), { remember: true, username: 'next-user' })
	saveLoginPreference(storage, false, 'next-user')
	assert.deepEqual(JSON.parse(storage.getItem(key)), { remember: false, username: '' })
})

test('corrupted and disabled browser storage do not prevent sign-in', () => {
	const storage = memoryStorage('{broken')
	assert.deepEqual(loadLoginPreference(storage), { remember: false, username: '' })
	assert.equal(storage.getItem(key), undefined)
	const blocked = { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') }, removeItem() { throw new Error('blocked') } }
	assert.deepEqual(loadLoginPreference(blocked), { remember: false, username: '' })
	assert.doesNotThrow(() => saveLoginPreference(blocked, true, 'user'))
})
