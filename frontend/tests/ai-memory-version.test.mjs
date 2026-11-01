import test from 'node:test'
import assert from 'node:assert/strict'
import {
	createAiMemoryManager,
	normalizeMemoryState,
} from '../src/utils/ai-memory-panel.mjs'

const snapshot = (version = 1, content = 'A') => ({
	enabled: true,
	maxEntries: 50,
	maxChars: 500,
	items: [{ id: 'one', content, version, createdAt: '' }],
})

test('normalization preserves a read version and rejects missing or imprecise versions', () => {
	assert.equal(normalizeMemoryState(snapshot(7)).items[0].version, 7)
	for (const version of [
		undefined,
		null,
		0,
		-1,
		1.5,
		'7',
		Number.MAX_SAFE_INTEGER + 1,
	]) {
		const value = snapshot(1)
		value.items[0].version = version
		assert.throws(() => normalizeMemoryState(value))
	}
})

test('ordinary manager update keeps the expectedContent argument and adds the actually read version', async () => {
	const calls = []
	const manager = createAiMemoryManager({
		request: async (method, path, data) => {
			calls.push([method, path, data])
			return { data: method === 'get' ? snapshot(7) : snapshot(8, 'edited') }
		},
	})
	await manager.setIdentity('alice')
	assert.equal(await manager.update('one', 'edited', 'A'), true)
	assert.deepEqual(calls[1], [
		'put',
		'/one',
		{ content: 'edited', expectedContent: 'A', expectedVersion: 7 },
	])
	manager.dispose()
})

test('an explicitly bound edit version is not silently rebased after an ABA refresh', async () => {
	const calls = []
	let reads = 0
	const manager = createAiMemoryManager({
		request: async (method, path, data) => {
			calls.push([method, path, data])
			if (method === 'get') return { data: snapshot(++reads === 1 ? 1 : 3) }
			throw {
				response: {
					status: 409,
					data: { code: '41000', msg: '记忆已变化，请刷新后再编辑' },
				},
			}
		},
	})
	await manager.setIdentity('alice')
	await manager.load()
	assert.equal(await manager.update('one', 'old edit', 'A', 1), false)
	assert.deepEqual(calls[2], [
		'put',
		'/one',
		{ content: 'old edit', expectedContent: 'A', expectedVersion: 1 },
	])
	assert.equal(manager.state().items[0].version, 3)
	assert.ok(manager.state().error)
	const before = calls.length
	assert.equal(await manager.update('one', 'retry', 'A', 1), false)
	assert.equal(calls.length, before)
	manager.dispose()
})

test('single deletion carries the displayed version and conflict leaves the retained entry visible', async () => {
	const calls = []
	const manager = createAiMemoryManager({
		request: async (method, path, data) => {
			calls.push([method, path, data])
			if (method === 'get') return { data: snapshot(2) }
			throw {
				response: {
					status: 409,
					data: { code: '41000', msg: '记忆已变化，请刷新后再编辑' },
				},
			}
		},
	})
	await manager.setIdentity('alice')
	assert.equal(await manager.forget('one'), false)
	assert.deepEqual(calls[1], ['delete', '/one?expectedVersion=2', undefined])
	assert.equal(manager.state().items[0].content, 'A')
	assert.ok(manager.state().error)
	manager.dispose()
})
