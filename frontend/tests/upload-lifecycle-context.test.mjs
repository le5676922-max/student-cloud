import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as vue from 'vue'
import { minimatch } from 'minimatch'
import {
	captureUploadTarget,
	uploadAccountIdentity,
} from '../src/composables/file/upload-lifecycle.mjs'

const signedIn = { isLogin: true, username: '10001', tenantId: 1 }
const valid = () => ({
	storageKey: 'one',
	directory: '/资料',
	rootPath: '/user',
	folderConfig: {
		permission: { upload: true, newFolder: true },
		metadata: { uploadType: 'S3' },
	},
	accountIdentity: uploadAccountIdentity(signedIn),
})

test('upload target is an immutable value snapshot, not live folder metadata', () => {
	const input = valid()
	const target = captureUploadTarget(input)
	input.directory = '/changed'
	input.folderConfig.metadata.uploadType = 'UPYUN'
	input.folderConfig.permission.newFolder = false
	assert.equal(target.directory, '/资料')
	assert.equal(target.uploadType, 'S3')
	assert.equal(target.canCreateFolder, true)
	assert.equal(Object.isFrozen(target), true)
})

test('identity excludes cosmetic account fields but differentiates login and tenant', () => {
	assert.equal(
		uploadAccountIdentity({ ...signedIn, nickname: 'changed' }),
		uploadAccountIdentity(signedIn)
	)
	assert.notEqual(
		uploadAccountIdentity({ ...signedIn, tenantId: 2 }),
		uploadAccountIdentity(signedIn)
	)
	assert.equal(uploadAccountIdentity({ ...signedIn, isLogin: false }), '')
	for (const field of ['id', 'accountId', 'role']) {
		assert.notEqual(uploadAccountIdentity({ ...signedIn, [field]: 'new-value' }), uploadAccountIdentity(signedIn))
	}
})

test('targets require authenticated identity, exact upload permission, path and known provider', () => {
	assert.throws(
		() => captureUploadTarget({ ...valid(), accountIdentity: '' }),
		/登录/
	)
	assert.throws(
		() => captureUploadTarget({ ...valid(), storageKey: '' }),
		/存储源/
	)
	assert.throws(
		() => captureUploadTarget({ ...valid(), directory: 'relative' }),
		/目录/
	)
	const denied = valid()
	delete denied.folderConfig.permission.upload
	assert.throws(() => captureUploadTarget(denied), /上传权限/)
	const unsupported = valid()
	unsupported.folderConfig.metadata.uploadType = 'UNKNOWN'
	assert.throws(() => captureUploadTarget(unsupported), /上传方式/)
})

const rewriteImports = (source) =>
	source.replace(
		/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/g,
		(_whole, bindings, name) =>
			bindings.trim().startsWith('{')
				? `const ${bindings.replace(
						/\s+as\s+/g,
						': '
				  )} = modules[${JSON.stringify(name)}];`
				: `const ${bindings.trim()} = modules[${JSON.stringify(name)}].default;`
	)

test('real password lookup uses captured storage and user root after route navigation', async () => {
	const pwdSource = await readFile(
		new URL('../src/composables/file/useFilePwd.js', import.meta.url),
		'utf8'
	)
	const route = vue.reactive({ storageKey: 'two', path: '/different' })
	const storage = { user: { rootPath: '/other-user' }, globalConfig: {} }
	const cache = {
		one: { '/user/original/**': 'synthetic-one' },
		two: { '/other-user/original/**': 'synthetic-two' },
	}
	const modules = {
		minimatch: { minimatch },
		'~/composables/useRouterData': {
			default: () => ({
				storageKey: vue.computed(() => route.storageKey),
				currentPath: vue.computed(() => route.path),
			}),
		},
		'~/utils': {
			concatPath: (...parts) =>
				parts.filter(Boolean).join('/').replace(/\/+/g, '/'),
			removeDuplicateSeparator: (path) => path.replace(/\/+/g, '/'),
		},
		'~/components/messageBox/messageBox': { default: {} },
		'~/stores/storage-config': { default: () => storage },
	}
	const context = vm.createContext({
		modules,
		...vue,
		console: { log() {} },
		useStorage: () => vue.ref(cache),
		sessionStorage: {},
	})
	const executable = rewriteImports(pwdSource).replace(
		'export default function',
		'function'
	)
	const api = vm.runInContext(
		`(function(){${executable}\nreturn useFilePwd();})()`,
		context
	)
	assert.equal(
		api.getPathPwd('/original', false, {
			storageKey: 'one',
			rootPath: '/user',
		}),
		'synthetic-one'
	)
	assert.equal(api.getPathPwd('/original'), 'synthetic-two')
})

test('upload and mkdir API wrappers forward cancellation but remove private target context from HTTP config', async () => {
	const apiSource = await readFile(
		new URL('../src/api/home/file-operator.js', import.meta.url),
		'utf8'
	)
	const calls = [],
		passwords = []
	const modules = {
		'~/http/request': {
			default: (request) => {
				calls.push(request)
				return Promise.resolve({})
			},
		},
		'~/composables/file/useFilePwd': {
			default: () => ({
				getPathPwd: (...args) => {
					passwords.push(args)
					return 'synthetic'
				},
			}),
		},
	}
	const executable = rewriteImports(apiSource).replaceAll(
		'export const',
		'const'
	)
	const api = vm.runInContext(
		`(function(){${executable}\nreturn { uploadFileReq, newFolderReq };})()`,
		vm.createContext({ modules })
	)
	const signal = new AbortController().signal
	const target = { storageKey: 'one', rootPath: '/user' }
	await api.uploadFileReq(
		{ path: '/original' },
		{ signal, uploadContext: target, timeout: 30000 }
	)
	await api.newFolderReq(
		{ path: '/original', name: 'nested' },
		{ signal, uploadContext: target }
	)
	assert.equal(passwords[0][2], target)
	assert.equal(passwords[1][2], target)
	assert.equal(calls[0].config.signal, signal)
	assert.equal(calls[1].config.signal, signal)
	assert.equal('uploadContext' in calls[0].config, false)
	assert.equal('uploadContext' in calls[1].config, false)
	await api.uploadFileReq({ path: '/legacy' })
	assert.equal(passwords[2][2], undefined)
})
