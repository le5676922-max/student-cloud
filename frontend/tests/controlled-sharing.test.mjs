import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { ref } from 'vue'

const utility = await import('../src/utils/controlled-sharing.mjs')
test('sharing follows the live dialog ref when independent configuration mounts it after the page', async () => {
	const source = await readFile(
		new URL('../src/composables/file/useFileShare.js', import.meta.url),
		'utf8'
	)
	const rows = ref([{ type: 'FILE', name: 'note.txt', path: '/', size: 10 }]),
		dialog = ref(),
		opened = []
	const modules = {
		vue: { ref },
		'element-plus': { ElMessage: { warning() {} } },
		'~/stores/storage-config': () => ({
			globalConfig: { accountModel: 'independent' },
			loginInfo: { isLogin: true, role: 'USER' },
			folderConfig: { permission: { download: true } },
		}),
		'~/composables/useRouterData': () => ({
			storageKey: ref('own-drive'),
			currentPath: ref('/'),
		}),
		'~/utils/controlled-sharing.mjs': utility,
		'./useFileSelect': () => ({
			selectRows: rows,
			selectStatistics: ref({ isSelected: true }),
		}),
	}
	const code = source
		.replace(
			/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g,
			(_m, names, module) =>
				`const {${names}}=modules[${JSON.stringify(module)}]`
		)
		.replace(
			/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g,
			(_m, name, module) => `const ${name}=modules[${JSON.stringify(module)}]`
		)
		.replace('export default', 'return')
	const useShare = new Function('modules', code)(modules),
		actions = useShare()
	actions.setControlledShareDialogRef(dialog)
	dialog.value = { openDialog: (...args) => opened.push(args) }
	actions.openCreateShareDialog()
	assert.equal(opened.length, 1)
	assert.equal(opened[0][0].path, '/note.txt')
})
test('controlled shares accept only opaque IDs and build protected same-site URLs', () => {
	const id = 'x'.repeat(43)
	assert.equal(utility.controlledSharePath(id), `/controlled-shares/${id}`)
	assert.equal(
		utility.controlledShareContentPath(id),
		`/api/shares/${id}/content`
	)
	for (const invalid of [
		'https://external.invalid/file',
		'../a',
		id + '?token=secret',
		'x'.repeat(42),
		id + '/content',
	])
		assert.throws(() => utility.controlledShareContentPath(invalid))
})
test('independent sharing captures one file and requires explicit logged-in audience consent', () => {
	const context = { storageKey: 'private-drive', path: '/notes' }
	const input = utility.captureControlledShare(
		[{ type: 'FILE', path: '/notes', name: 'week.txt', size: 10 }],
		context
	)
	assert.equal(input.path, '/notes/week.txt')
	assert.equal(input.storageKey, 'private-drive')
	assert.ok(Object.isFrozen(input))
	for (const rows of [
		[],
		[{ type: 'FOLDER', name: 'notes' }],
		[
			{ type: 'FILE', name: 'a' },
			{ type: 'FILE', name: 'b' },
		],
		[{ type: 'FILE', name: '../secret' }],
	])
		assert.throws(() => utility.captureControlledShare(rows, context))
	assert.throws(() =>
		utility.controlledShareInput(input, {
			audience: 'OWN_WORKSPACE',
			consent: false,
		})
	)
	assert.deepEqual(
		utility.controlledShareInput(input, {
			audience: 'OWN_WORKSPACE',
			consent: true,
			days: 7,
		}),
		{
			...input,
			audience: 'OWN_WORKSPACE',
			courseId: null,
			expiresAt: undefined,
		}
	)
	assert.throws(() =>
		utility.controlledShareInput(input, { audience: 'PUBLIC', consent: true })
	)
	assert.throws(() =>
		utility.controlledShareInput(input, { audience: 'COURSE', consent: true })
	)
	assert.equal(
		utility.controlledShareInput(
			input,
			{ audience: 'COURSE', consent: true, courseId: 3, days: 30 },
			1000
		).expiresAt,
		1000 + 30 * 86400000
	)
})
test('download uses authenticated blob transport and rejects JSON errors without creating URLs', async () => {
	const code = await readFile(
		new URL('../src/api/home/controlled-shares.js', import.meta.url),
		'utf8'
	)
	const calls = []
	let response = { data: new Blob(['private']) }
	let requestFailure
	const request = async (input) => {
		calls.push(input)
		if (requestFailure) throw requestFailure
		return response
	}
	const api = new Function(
		'request',
		'controlledShareContentPath',
		code.replace(/^import .*\n/gm, '').replace(/export /g, '') +
			'\nreturn { downloadControlledShare }'
	)(request, utility.controlledShareContentPath)
	assert.equal(
		await (await api.downloadControlledShare('a'.repeat(43))).text(),
		'private'
	)
	assert.deepEqual(calls[0], {
		url: `/api/shares/${'a'.repeat(43)}/content`,
		method: 'get',
		config: {
			showDefaultMsg: false,
			timeout: 60000,
			responseType: 'blob',
			responseIntercept: false,
		},
	})
	response = {
		data: new Blob([JSON.stringify({ msg: '分享已撤销' })], {
			type: 'application/json',
		}),
	}
	await assert.rejects(
		api.downloadControlledShare('a'.repeat(43)),
		/分享已撤销/
	)
	requestFailure = {
		response: {
			data: new Blob([JSON.stringify({ msg: '请先登录' })], {
				type: 'application/json',
			}),
		},
	}
	await assert.rejects(api.downloadControlledShare('a'.repeat(43)), /请先登录/)
	assert.equal(
		calls.some((call) => /token=|https?:/.test(call.url)),
		false
	)
})
