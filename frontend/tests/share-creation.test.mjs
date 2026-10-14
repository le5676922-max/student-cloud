import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const componentUrl = new URL(
	'../src/components/file/CreateShareDialog.vue',
	import.meta.url
)

test('share password input and validation match the optional eight-character server limit', async () => {
	const component = await readFile(componentUrl, 'utf8')
	const input = component.match(
		/<el-input\s+v-model="shareForm\.password"[\s\S]*?\/>/
	)
	assert.ok(input)
	assert.match(input[0], /maxlength="8"/)
	assert.match(input[0], /最多 8 个字符/)
	const passwordRule = component.match(/password: \[([\s\S]*?)\],/)?.[1]
	assert.ok(passwordRule)
	assert.match(passwordRule, /max: 8,/)
	assert.doesNotMatch(passwordRule, /min:|required:\s*true/)
	assert.match(passwordRule, /分享密码最多 8 个字符/)
})

test('share links encode special password characters instead of changing the supplied password', async () => {
	const component = await readFile(componentUrl, 'utf8')
	assert.match(
		component,
		/pwd=\$\{encodeURIComponent\(\s*shareForm\.value\.password\s*\)\}/
	)
	const password = 'a&b+?#'
	const url = new URL(
		`https://example.invalid/share/test?pwd=${encodeURIComponent(password)}`
	)
	assert.equal(url.searchParams.get('pwd'), password)
	assert.deepEqual([...url.searchParams.keys()], ['pwd'])
})
