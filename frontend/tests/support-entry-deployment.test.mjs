import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { isSameOriginSupportUpgrade } from '../src/utils/support-proxy-origin.mjs'
import { adminPrimaryNavigation } from '../src/utils/admin-navigation.mjs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')

test('support is a direct global-administrator navigation entry using the established icon set', () => {
	assert.deepEqual(
		adminPrimaryNavigation.find((item) => item.id === 'support'),
		{
			id: 'support',
			name: '人工客服',
			href: '/admin/support',
			icon: 'members',
		}
	)
})

test('AI handoff stays inside the assistant and requires a review before mounting human chat', () => {
	const assistant = read('../src/components/ai/AiAssistant.vue')
	assert.match(assistant, /loginInfo\.isLogin/)
	assert.match(assistant, /\['USER', 'TENANT_ADMIN'\]\.includes/)
	assert.match(assistant, /AI 未解决？转人工客服/)
	assert.match(
		assistant.replace(/\s+/g, ' '),
		/不自动共享其他 AI 对话、附件或长期记忆/
	)
	assert.match(assistant, /取消转人工不会撤回反馈/)
	assert.match(assistant, /v-if="supportSession"\s+v-show="supportMode"/)
	assert.match(assistant, /v-show="isOpen"/)
	assert.match(assistant, /@submit\.prevent="confirmHumanSupport"/)
	assert.doesNotMatch(assistant, /to="\/support"/)
	assert.equal(assistant.includes('ai-agent-support-entry'), true)
})

test('desktop and mobile account menus offer support even without opening the AI assistant', () => {
	const header = read('../src/components/layout/Header.vue')
	assert.equal(
		(header.match(/'\/admin\/support'\s*:\s*'\/support'/g) || []).length,
		2
	)
	assert.equal((header.match(/'客服工作台'\s*:\s*'人工客服'/g) || []).length, 2)
})

test('Vite accepts only an already same-origin browser WebSocket upgrade before rewriting upstream Origin', () => {
	for (const [headers, encrypted] of [
		[{ origin: 'http://127.0.0.1:5178', host: '127.0.0.1:5178' }, false],
		[{ origin: 'https://cloud.example', host: 'cloud.example' }, true],
		[{ origin: 'http://[::1]:5178', host: '[::1]:5178' }, false],
	])
		assert.equal(isSameOriginSupportUpgrade(headers, encrypted), true)
	for (const headers of [
		{},
		{ host: '127.0.0.1:5178' },
		{ origin: 'null', host: '127.0.0.1:5178' },
		{ origin: 'http://evil.example', host: '127.0.0.1:5178' },
		{ origin: 'https://127.0.0.1:5178', host: '127.0.0.1:5178' },
		{ origin: 'http://127.0.0.1:5178', host: '127.0.0.1:5178/unsafe' },
		{ origin: 'http://127.0.0.1:5178/path', host: '127.0.0.1:5178' },
		{ origin: 'http://user:secret@127.0.0.1:5178', host: '127.0.0.1:5178' },
		{ origin: ['http://127.0.0.1:5178'], host: '127.0.0.1:5178' },
	])
		assert.equal(isSameOriginSupportUpgrade(headers), false)
	const vite = read('../vite.config.ts')
	const block = vite.slice(
		vite.indexOf("'^/ws/support"),
		vite.indexOf("'^/(api|")
	)
	assert.match(block, /ws: true/)
	assert.match(block, /proxy\.on\('proxyReqWs'/)
	assert.ok(
		block.indexOf('isSameOriginSupportUpgrade') <
			block.indexOf("setHeader('origin'")
	)
	assert.match(block, /403 Forbidden/)
	assert.match(block, /proxyReq\.destroy\(\)/)
	assert.doesNotMatch(block, /token=|getItem/)
})

test('Nginx WebSocket handshake cannot fall back to SPA or lose upgrade headers', () => {
	const routes = read('../nginx/routes.conf')
	const start = routes.indexOf('location = /ws/support {')
	assert.ok(start >= 0)
	const block = routes.slice(start).split('\n}')[0]
	assert.match(block, /access_log off;/)
	assert.match(block, /proxy_http_version 1\.1;/)
	assert.match(block, /proxy_set_header Upgrade \$http_upgrade;/)
	assert.match(block, /proxy_set_header Connection "upgrade";/)
	assert.match(block, /proxy_set_header Host \$http_host;/)
	assert.match(block, /proxy_pass http:\/\/cloud_backend;/)
	assert.match(block, /proxy_read_timeout 90s;/)
	assert.match(block, /proxy_next_upstream off;/)
	assert.doesNotMatch(block, /try_files|proxy\.conf|set_header Origin/)
})
