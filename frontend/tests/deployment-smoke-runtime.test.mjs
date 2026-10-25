import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { runDeploymentSmoke } from '../../scripts/deployment-smoke.mjs'

function fixture(overrides = {}) {
  const calls = []
  function reply(path, options) {
    calls.push({ path, ...options })
    if (overrides[path]) return overrides[path]
    if (options.headers.Accept === 'text/html')
      return { status: 200, type: 'text/html; charset=utf-8', body: '<!doctype html><html></html>' }
    if (path === '/api/install/status')
      return { status: 200, type: 'application/json', body: JSON.stringify({ code: '0', data: false }) }
    if (path === '/user/registration/config')
      return { status: 200, type: 'application/json', body: JSON.stringify({ code: '0', data: { enabled: true, mailAvailable: false } }) }
    if (path === '/internal/ready' || path.startsWith('/assets/'))
      return { status: 404, type: 'text/html', body: 'Not Found' }
    return { status: 401, type: 'application/json', body: JSON.stringify({ code: '42000', msg: '未登录' }) }
  }
  return { calls, reply, fetchImpl: async (url, options) => {
    const value = reply(url.pathname, options)
    return new Response(value.body, { status: value.status, headers: { 'Content-Type': value.type } })
  } }
}

test('fresh deployments use string business success, false install status and disabled mail are valid', async () => {
  const backend = fixture()
  assert.equal(await runDeploymentSmoke('https://cloud.example.test', backend), 16)
  assert.equal(backend.calls.length, 16)
  for (const call of backend.calls) {
    assert.equal(call.method, 'GET')
    assert.equal(call.redirect, 'manual')
    assert.equal(call.credentials, 'omit')
    assert.ok(call.signal instanceof AbortSignal)
    assert.equal(call.headers['axios-request'], call.headers.Accept === 'application/json' ? 'true' : undefined)
    assert.ok(!Object.keys(call.headers).some(key => /token|authorization|cookie/i.test(key)))
    assert.ok(!call.path.startsWith('/file/upload/'), 'Never substitute GET for a write-only handler')
  }
})

test('startup errors, wrong success types and missing data cannot pass as healthy APIs', async () => {
  for (const payload of [{ code: 200, data: false }, { code: 0, data: false },
    { code: '50000', data: false }, { code: '0' }, { code: '0', data: 'false' }]) {
    const backend = fixture({ '/api/install/status': { status: 200, type: 'application/json', body: JSON.stringify(payload) } })
    await assert.rejects(runDeploymentSmoke('http://127.0.0.1', backend))
  }
})

test('business success on a protected handler is rejected rather than treated as a denied request', async () => {
  const backend = fixture({ '/api/storage/list': { status: 200, type: 'application/json', body: '{"code":"0","data":[]}' } })
  await assert.rejects(runDeploymentSmoke('http://127.0.0.1', backend), /authentication rejection/)
})

test('server errors, missing routes and wrong business codes are not evidence of authentication', async () => {
  for (const value of [
    { status: 500, body: '{"code":"50000"}' },
    { status: 404, body: '{"code":"40400"}' },
    { status: 401, body: '{"code":"0"}' },
    { status: 401, body: '{"code":"50000"}' },
    { status: 401, body: '{}' },
  ]) {
    const backend = fixture({ '/api/storage/list': { type: 'application/json', ...value } })
    await assert.rejects(runDeploymentSmoke('http://127.0.0.1', backend))
  }
})

test('HTML fallbacks, malformed JSON and redirects cannot be accepted as protected JSON', async () => {
  for (const value of [
    { status: 200, type: 'text/html', body: '<html>login</html>' },
    { status: 401, type: 'application/json', body: 'not-json' },
    { status: 302, type: 'text/html', body: '' },
  ]) {
    const backend = fixture({ '/admin/overview': value })
    // This override applies to HTML navigation too: every failure must stop the probe.
    await assert.rejects(runDeploymentSmoke('http://127.0.0.1', backend))
  }
})

test('API-shaped HTML fallback is rejected even when it contains a JSON string', async () => {
  const backend = fixture({ '/api/install/status': { status: 200, type: 'text/html', body: '{"code":"0","data":false}' } })
  await assert.rejects(runDeploymentSmoke('http://127.0.0.1', backend), /Expected JSON/)
})

test('readiness and missing assets cannot fall back to successful SPA pages', async () => {
  for (const path of ['/internal/ready', '/assets/deployment-smoke-does-not-exist.js']) {
    const backend = fixture({ [path]: { status: 200, type: 'text/html', body: '<html>app</html>' } })
    await assert.rejects(runDeploymentSmoke('http://127.0.0.1', backend))
  }
})

test('invalid origins fail before any request without echoing credential values', async () => {
  for (const origin of ['file:///tmp/', 'https://cloud.example.test/login',
    'https://someone:synthetic-private-value@cloud.example.test',
    'https://cloud.example.test?token=synthetic-private-value', 'https://cloud.example.test#fragment']) {
    const backend = fixture()
    await assert.rejects(runDeploymentSmoke(origin, backend), error => {
      assert.ok(!error.message.includes('synthetic-private-value'))
      return true
    })
    assert.equal(backend.calls.length, 0)
  }
})

test('CLI executes actual loopback HTTP probes with API headers and no write requests', async (t) => {
  const backend = fixture()
  const server = createServer((request, response) => {
    const value = backend.reply(new URL(request.url, 'http://127.0.0.1').pathname,
      { method: request.method, headers: { Accept: request.headers.accept, 'axios-request': request.headers['axios-request'] } })
    response.writeHead(value.status, { 'Content-Type': value.type })
    response.end(value.body)
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  t.after(() => server.close())
  const child = spawn(process.execPath, [fileURLToPath(new URL('../../scripts/deployment-smoke.mjs', import.meta.url)),
    `http://127.0.0.1:${server.address().port}`], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  t.after(() => { if (child.exitCode === null) child.kill() })
  let output = ''
  let errors = ''
  child.stdout.on('data', data => { output += data })
  child.stderr.on('data', data => { errors += data })
  const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve) })
  assert.equal(code, 0, errors)
  assert.match(output, /passed: 16/)
  assert.equal(errors, '')
  assert.equal(backend.calls.length, 16)
  assert.ok(backend.calls.every(call => call.method === 'GET'))
  for (const call of backend.calls.filter(call => call.headers.Accept === 'application/json'))
    assert.equal(call.headers['axios-request'], 'true')
})
