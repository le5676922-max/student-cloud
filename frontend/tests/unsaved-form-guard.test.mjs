import assert from 'node:assert/strict'
import test from 'node:test'
import { createUnsavedFormGuard } from '../src/utils/unsaved-form-guard.mjs'

test('unmodified and newly switched account drafts never trigger a private confirmation', async () => {
	let value = 'initial',
		scope = 'account-a',
		prompts = 0
	const guard = createUnsavedFormGuard({
		readSnapshot: () => value,
		readScope: () => scope,
		confirmLeave: () => {
			prompts++
			return true
		},
	})
	guard.accept()
	assert.equal(await guard.mayLeave(), true)
	value = 'edited'
	scope = 'account-b'
	assert.equal(await guard.mayLeave(), true)
	assert.equal(prompts, 0)
})
test('cancel preserves dirty state, save accepts it, and failed confirmation stays on the form', async () => {
	let value = 'initial',
		decision = false
	const guard = createUnsavedFormGuard({
		readSnapshot: () => value,
		readScope: () => 1,
		confirmLeave: () => decision,
	})
	guard.accept()
	value = 'draft'
	assert.equal(await guard.mayLeave(), false)
	assert.equal(guard.hasChanges(), true)
	decision = true
	assert.equal(await guard.mayLeave(), true)
	assert.equal(guard.hasChanges(), true)
	guard.accept()
	assert.equal(guard.hasChanges(), false)
})
test('duplicate navigation shares one confirmation and an account change invalidates its approval', async () => {
	let value = 'initial',
		scope = 'a',
		prompts = 0,
		resolve
	const guard = createUnsavedFormGuard({
		readSnapshot: () => value,
		readScope: () => scope,
		confirmLeave: () => {
			prompts++
			return new Promise((r) => (resolve = r))
		},
	})
	guard.accept()
	value = 'draft'
	const first = guard.mayLeave(),
		second = guard.mayLeave()
	await Promise.resolve()
	assert.equal(prompts, 1)
	scope = 'b'
	resolve(true)
	assert.equal(await first, false)
	assert.equal(await second, false)
})
