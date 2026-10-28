import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

async function api(transport) {
	const source = await readFile(
		new URL('../src/api/home/courses.js', import.meta.url),
		'utf8'
	)
	return new Function(
		'request',
		source
			.replace(/^import .*$/m, '')
			.replaceAll('export const ', 'const ')
			.replaceAll('export async function ', 'async function ') +
			'\nreturn { downloadCourseMaterial }'
	)(transport)
}

test('course downloads use the protected relative blob endpoint, ignoring untrusted row URLs', async () => {
	const calls = [],
		blob = new Blob(['course'])
	const course = await api(async (request) => {
		calls.push(request)
		return { data: blob }
	})
	assert.equal(
		await course.downloadCourseMaterial(7, {
			id: 21,
			downloadUrl: 'https://external.invalid/token',
		}),
		blob
	)
	assert.equal(calls[0].url, '/api/courses/7/materials/21/download')
	assert.equal(calls[0].config.responseType, 'blob')
	assert.equal(calls[0].config.responseIntercept, false)
	assert.equal(calls[0].data, undefined)
	for (const invalid of ['../7', '7?token=secret', '//other.invalid', '0'])
		await assert.rejects(
			course.downloadCourseMaterial(invalid, { id: 21 }),
			/标识无效/
		)
	assert.equal(calls.length, 1)
})

test('JSON API errors cannot be downloaded as successful course files', async () => {
	const course = await api(async () => ({
		data: new Blob([JSON.stringify({ msg: '课程共享已关闭' })], {
			type: 'application/json',
		}),
	}))
	await assert.rejects(
		course.downloadCourseMaterial('a'.repeat(32), { id: 'b'.repeat(32) }),
		/课程共享已关闭/
	)
})
