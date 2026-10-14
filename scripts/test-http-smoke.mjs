import assert from 'node:assert/strict'
import { performance } from 'node:perf_hooks'

// Run only against an explicitly selected local test service. No user data is written.
const base = process.env.CLOUD_SMOKE_BASE_URL
assert.match(base || '', /^http:\/\/(127\.0\.0\.1|localhost):\d+$/)
assert.ok(process.env.CLOUD_SMOKE_USERNAME && process.env.CLOUD_SMOKE_PASSWORD,
  'Set CLOUD_SMOKE_USERNAME and CLOUD_SMOKE_PASSWORD to dedicated test credentials')

async function request(path, token, data) {
  const response = await fetch(base + path, {
    method: data ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'axios-request': 'true', ...(token ? { 'tongligong-student-cloud-token': token } : {}) },
    body: data ? JSON.stringify(data) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  const body = await response.text()
  assert.ok(response.headers.get('content-type')?.includes('application/json'),
    `Expected JSON from ${path}; received HTTP ${response.status}`)
  return { status: response.status, ...JSON.parse(body) }
}
const login = await request('/user/login', null, {
  username: process.env.CLOUD_SMOKE_USERNAME, password: process.env.CLOUD_SMOKE_PASSWORD,
})
assert.equal(login.code, '0', 'Test account login failed')
const token = login.data.token
const checks = []
const samples = Number(process.env.CLOUD_SMOKE_SAMPLES || 100)
assert.ok(Number.isInteger(samples) && samples >= 20 && samples <= 1000 && samples % 4 === 0,
  'CLOUD_SMOKE_SAMPLES must be a multiple of 4 between 20 and 1000')
try {
  for (const path of ['/admin/overview', '/admin/user/list', '/admin/storages', '/admin/tenants', '/admin/config']) {
    const result = await request(path, token)
    assert.equal(result.code, '0', `Admin read failed: ${path}`)
    const anonymous = await request(path)
    assert.notEqual(anonymous.code, '0', `Anonymous admin access allowed: ${path}`)
    checks.push({ path, admin: 'PASS', anonymousDenied: 'PASS' })
  }
  for (const path of ['/api/ai/tasks', '/api/ai/schedules']) {
    assert.equal((await request(path, token)).code, '0', `Agent endpoint failed: ${path}`)
    assert.notEqual((await request(path)).code, '0', `Anonymous Agent access allowed: ${path}`)
    checks.push({ path, authenticated: 'PASS', anonymousDenied: 'PASS' })
  }
  const benchmarks = []
  for (const [path, data] of [
    ['/api/site/config/global', null],
    ['/admin/overview', null],
    ['/api/storage/files', { storageKey: 'workflow', path: '/', pageSize: 50 }],
  ]) {
    const durations = []
    // Warm each route separately; cold start is not conflated with steady-state latency.
    const coldStart = performance.now()
    assert.equal((await request(path, token, data)).code, '0')
    const firstRequestMs = performance.now() - coldStart
    for (let batch = 0; batch < samples / 4; batch++) {
      await Promise.all(Array.from({ length: 4 }, async () => {
        const start = performance.now()
        const result = await request(path, token, data)
        assert.equal(result.code, '0', `Concurrent read failed: ${path}`)
        durations.push(performance.now() - start)
      }))
    }
    durations.sort((a, b) => a - b)
    benchmarks.push({ path, requests: durations.length, concurrency: 4,
      firstRequestMs: +firstRequestMs.toFixed(2),
      medianMs: +durations[Math.ceil(durations.length * 0.5) - 1].toFixed(2),
      p95Ms: +durations[Math.ceil(durations.length * 0.95) - 1].toFixed(2), maxMs: +durations.at(-1).toFixed(2) })
  }
  console.log(JSON.stringify({ base, checks, benchmarks, note: 'Local warm-service sample; not an Internet or capacity load test.' }, null, 2))
} finally {
  await request('/user/logout', token, {})
}
