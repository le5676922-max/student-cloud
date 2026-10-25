import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileTemplate } from 'vue/compiler-sfc'
import postcss from 'postcss'

const source = await readFile(new URL('../src/pages/login.vue', import.meta.url), 'utf8')
const css = postcss.parse(await readFile(new URL('../src/styles/login.css', import.meta.url), 'utf8'))
const { descriptor } = parse(source)
const declarations = (selector) => Object.fromEntries(
	css.nodes.filter((node) => node.type === 'rule' && node.selector === selector)
		.flatMap((rule) => rule.nodes.filter((node) => node.type === 'decl').map((node) => [node.prop, node.value]))
)

test('reference login retains the character scene and original centered welcome copy', () => {
	assert.match(descriptor.template.content, /<LoginCharacters\b/)
	assert.match(descriptor.template.content, /欢迎回来!/)
	assert.match(descriptor.template.content, /登录学生云盘，继续整理你的资料/)
	assert.match(descriptor.template.content, /<router-link to="\/">返回云盘<\/router-link>/)
	assert.doesNotMatch(descriptor.template.content, /career-login-art-copy|career-login-eyebrow/)
	const compiled = compileTemplate({ source: descriptor.template.content, filename: 'login.vue', id: 'reference-login' })
	assert.deepEqual(compiled.errors, [])
})

test('reference appearance keeps dark background, grey character panel and underline inputs', () => {
	assert.equal(declarations('.career-login-page').background, '#1c1d20')
	assert.equal(declarations('.career-login-card')['grid-template-columns'], '58% 42%')
	assert.equal(declarations('.career-login-form-wrap')['max-width'], '300px')
	assert.equal(declarations('.career-login-intro')['text-align'], 'center')
	assert.equal(declarations('.career-login-mobile-brand').display, 'flex')
	assert.equal(declarations('.career-login-form .el-input__wrapper')['border-radius'], '0')
	assert.equal(declarations('.career-login-page .career-login-submit')['border-radius'], '24px')
	assert.match(declarations('.career-login-art').background, /^linear-gradient/)
})

test('visual restoration preserves registration, password recovery and credential handling hooks', () => {
	assert.match(descriptor.template.content, /RegistrationDialog/)
	assert.match(descriptor.template.content, /PasswordResetDialog/)
	assert.match(descriptor.scriptSetup.content, /loginReq\(/)
	assert.match(descriptor.scriptSetup.content, /saveLoginPreference/)
	assert.match(descriptor.template.content, /:paused="motionPaused"/)
})
