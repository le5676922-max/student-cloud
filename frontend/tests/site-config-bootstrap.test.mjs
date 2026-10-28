import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { reactive } from 'vue'
import { createSiteConfigBootstrap } from '../src/utils/site-config-bootstrap.mjs'

function deferred() {
	let resolve
	const promise = new Promise((done) => {
		resolve = done
	})
	return { promise, resolve }
}
function fixture(load) {
	const state = {},
		applied = []
	const bootstrap = createSiteConfigBootstrap({
		state,
		load,
		apply: (data) => applied.push(data),
	})
	return { bootstrap, state, applied }
}

test('all consumers share one pending configuration read and reuse its confirmed result', async () => {
	const pending = deferred()
	let reads = 0
	const { bootstrap, state, applied } = fixture(() => {
		reads++
		return pending.promise
	})
	const first = bootstrap.ensure('session-A'),
		second = bootstrap.ensure('session-A')
	await Promise.resolve()
	assert.equal(first, second)
	assert.equal(reads, 1)
	assert.equal(state.ready, false)
	pending.resolve({ data: { installed: true, accountModel: 'independent' } })
	await first
	assert.equal(state.ready, true)
	assert.equal(applied.length, 1)
	await bootstrap.ensure('session-A')
	assert.equal(reads, 1)
})

test('configuration failures stop until an explicit retry rather than reloading on every navigation', async () => {
	let reads = 0
	const { bootstrap, state } = fixture(async () => {
		if (!reads++) throw new Error('offline')
		return { data: { installed: true } }
	})
	await bootstrap.ensure('session-A')
	assert.equal(state.ready, false)
	assert.match(state.error, /站点配置读取失败/)
	await bootstrap.ensure('session-A')
	await bootstrap.ensure('session-A')
	assert.equal(reads, 1)
	await bootstrap.retry()
	assert.equal(reads, 2)
	assert.equal(state.ready, true)
	assert.equal(state.error, '')
})

test('replacing the session or backend context cancels and discards an old response without changing the new mode', async () => {
	const old = deferred(),
		current = deferred(),
		calls = []
	const { bootstrap, state, applied } = fixture((config) => {
		calls.push(config)
		return calls.length === 1 ? old.promise : current.promise
	})
	const first = bootstrap.ensure('session-A'),
		next = bootstrap.ensure('session-B')
	await Promise.resolve()
	assert.equal(calls[0].signal.aborted, true)
	old.resolve({ data: { installed: true, accountModel: 'legacy' } })
	await first
	assert.equal(state.ready, false)
	assert.deepEqual(applied, [])
	current.resolve({ data: { installed: true, accountModel: 'independent' } })
	await next
	assert.equal(state.ready, true)
	assert.equal(applied[0].accountModel, 'independent')
})

test('public-route reset and application disposal cancel pending reads and keep late configuration out of the store', async () => {
	for (const method of ['reset', 'cancel']) {
		const pending = deferred()
		let signal
		const { bootstrap, state, applied } = fixture((config) => {
			signal = config.signal
			return pending.promise
		})
		const loading = bootstrap.ensure('session-A')
		await Promise.resolve()
		bootstrap[method]()
		assert.equal(signal.aborted, true)
		pending.resolve({ data: { installed: true, accountModel: 'independent' } })
		await loading
		assert.equal(state.ready, false)
		assert.deepEqual(applied, [])
	}
})

test('uninstalled or malformed responses never mount business pages, and legacy confirmation remains supported', async () => {
	for (const data of [
		null,
		[],
		'invalid',
		{},
		{ installed: 'true' },
		{ installed: true, accountModel: 'unknown-service' },
		{ installed: true, accountModel: 0 },
		{ installed: false, accountModel: 'independent' },
	]) {
		const { bootstrap, state, applied } = fixture(async () => ({ data }))
		await bootstrap.ensure('session-A')
		assert.equal(state.ready, false)
		assert.deepEqual(applied, [])
		assert.equal(state.needsInstall, data?.installed === false)
	}
	const legacy = fixture(async () => ({
		data: { installed: true, siteName: '旧站点' },
	}))
	await legacy.bootstrap.ensure('legacy-session')
	assert.equal(legacy.state.ready, true)
})

test('a synchronous loader exception releases the pending gate so an explicit retry can recover', async () => {
	let reads = 0
	const { bootstrap, state } = fixture(() => {
		if (!reads++) throw new Error('synchronous configuration failure')
		return Promise.resolve({
			data: { installed: true, accountModel: 'independent' },
		})
	})
	await bootstrap.ensure('session-A')
	assert.equal(state.ready, false)
	assert.match(state.error, /读取失败/)
	await bootstrap.retry()
	assert.equal(reads, 2)
	assert.equal(state.ready, true)
})

test('the shared composable keys state by store instance and preserves custom file suffix mappings', async () => {
	const source = await readFile(
		new URL('../src/composables/useSiteConfigBootstrap.js', import.meta.url),
		'utf8'
	)
	let store = {
			updateGlobalConfig(data) {
				this.globalConfig = data
			},
		},
		reads = 0
	let response = {
		installed: true,
		accountModel: 'independent',
		customAudioSuffix: 'ogg,wav',
		customTextSuffix: 'txt,md',
	}
	const constant = {
		fileTypeMap: {
			audio: ['mp3'],
			image: ['png'],
			text: ['txt'],
			video: ['mp4'],
			office: ['docx'],
		},
	}
	const modules = {
		vue: { reactive },
		'~/stores/storage-config': () => store,
		'~/api/home/home': {
			loadGlobalSiteConfigReq: async () => {
				reads++
				return { data: response }
			},
		},
		'~/constant': { constant },
		'~/utils/site-config-bootstrap.mjs': { createSiteConfigBootstrap },
	}
	const code = source
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_m, names, path) => `const {${names}} = modules[${JSON.stringify(path)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_m, name, path) => `const ${name} = modules[${JSON.stringify(path)}]`
		)
		.replace('export default function', 'return function')
	const useBootstrap = new Function('modules', code)(modules)
	const first = useBootstrap(),
		second = useBootstrap()
	assert.equal(first, second)
	await Promise.all([first.ensure('session-A'), second.ensure('session-A')])
	assert.equal(reads, 1)
	assert.equal(store.globalConfig.accountModel, 'independent')
	assert.deepEqual(constant.fileTypeMap.audio, ['ogg', 'wav'])
	assert.deepEqual(constant.fileTypeMap.text, ['txt', 'md'])
	assert.deepEqual(constant.fileTypeMap.image, ['png'])
	response = {
		installed: true,
		accountModel: 'independent',
		customAudioSuffix: '',
	}
	await first.ensure('another-backend-session')
	assert.deepEqual(constant.fileTypeMap.audio, ['mp3'])
	assert.deepEqual(constant.fileTypeMap.text, ['txt'])
	store = {
		updateGlobalConfig(data) {
			this.globalConfig = data
		},
	}
	assert.notEqual(useBootstrap(), first)
})
