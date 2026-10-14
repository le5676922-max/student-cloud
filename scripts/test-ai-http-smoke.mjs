import assert from 'node:assert/strict'
import { performance } from 'node:perf_hooks'
import { consumeAiStream } from '../frontend/src/utils/ai-stream.mjs'

// Opt-in: these two requests may incur provider charges. Use only a synthetic test account.
assert.equal(process.env.CLOUD_LIVE_AI_SMOKE, 'true', 'Explicitly enable CLOUD_LIVE_AI_SMOKE=true')
const base = process.env.CLOUD_SMOKE_BASE_URL
assert.match(base || '', /^http:\/\/(127\.0\.0\.1|localhost):\d+$/)
const username = process.env.CLOUD_SMOKE_USERNAME
const password = process.env.CLOUD_SMOKE_PASSWORD
assert.ok(username && password, 'Set dedicated synthetic test credentials')
const headers = { 'Content-Type': 'application/json', 'axios-request': 'true' }
const loginResponse = await fetch(`${base}/user/login`, {
  method: 'POST', headers, body: JSON.stringify({ username, password }),
  signal: AbortSignal.timeout(15000),
})
const login = await loginResponse.json()
assert.equal(login.code, '0', 'Test login failed')
headers['tongligong-student-cloud-token'] = login.data.token
const results = []
try {
  for (const expectsTool of [false, true]) {
    const started = performance.now()
    const events = []
    const signal = AbortSignal.timeout(65000)
    const response = await fetch(`${base}/api/ai/chat/stream/${crypto.randomUUID()}`, {
      method: 'POST', headers: { ...headers, Accept: 'text/event-stream' }, signal,
      body: JSON.stringify({
        message: expectsTool
          ? '请调用 get_current_user 查询当前测试账号，在回复中原样包含 username。不要调用其他工具。'
          : '请只回复：连接成功。不要调用工具。',
        history: [], pagePath: '/', answerStyle: 'concise',
      }),
    })
    const result = await consumeAiStream(response, {
      signal, onEvent: (name) => events.push(name),
    })
    assert.equal(events.at(-1), 'done')
    assert.ok(events.includes('token'), 'No incremental token received')
    assert.ok(result.answer.includes(expectsTool ? username : '连接成功'))
    if (expectsTool) {
      assert.ok(result.tools.some((tool) => tool.name === 'get_current_user' && tool.success))
    }
    // Do not print the token, account details, complete answer, or provider key.
    results.push({ expectsTool, status: 'PASS', elapsedMs: Math.round(performance.now() - started),
      tokenEvents: events.filter((name) => name === 'token').length })
  }
  console.log(JSON.stringify({ base, results, note: 'Synthetic account only; real provider, HTTP SSE and frontend stream parser.' }, null, 2))
} finally {
  await fetch(`${base}/user/logout`, {
    method: 'POST', headers, body: '{}', signal: AbortSignal.timeout(15000),
  })
}
