#!/usr/bin/env node
// 部署后的只读冒烟验收，不注册用户、不上传/删除文件、不初始化数据库。
// node scripts/deployment-smoke.mjs https://cloud.example.com
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'

function envelope(response, body, path) {
  assert.match(response.headers.get('content-type') || '', /application\/(?:[\w.+-]+\+)?json(?:;|$)/i,
    `Expected JSON, not an HTML fallback: ${path}`)
  let payload
  try { payload = JSON.parse(body) }
  catch { assert.fail(`Invalid JSON response: ${path}`) }
  assert.ok(payload && typeof payload === 'object' && !Array.isArray(payload)
    && typeof payload.code === 'string' && payload.code.length > 0,
  `Invalid business response envelope: ${path}`)
  return payload
}

/** Importing this file never makes requests. All probes are anonymous GETs. */
export async function runDeploymentSmoke(base, { fetchImpl = globalThis.fetch } = {}) {
  const origin = new URL(base)
  assert.ok(['http:', 'https:'].includes(origin.protocol), 'Only HTTP(S) deployment URLs are accepted')
  assert.equal(origin.pathname, '/', 'Pass the website origin, not a page URL')
  // Never echo supplied credentials or query strings in assertion diagnostics.
  assert.ok(!origin.username && !origin.password && !origin.search && !origin.hash,
    'No credentials/query/fragment in URL')

  async function get(path, accept = 'application/json') {
    const response = await fetchImpl(new URL(path, origin), {
      method: 'GET',
      headers: { Accept: accept, ...(accept === 'application/json' ? { 'axios-request': 'true' } : {}) },
      credentials: 'omit',
      redirect: 'manual',
      signal: AbortSignal.timeout(15000),
    })
    return { response, body: await response.text() }
  }

  let checks = 0
  for (const path of ['/login', '/workflow/documents', '/admin/overview', '/admin/storage-list']) {
    const { response, body } = await get(path, 'text/html')
    assert.equal(response.status, 200, `SPA refresh failed: ${path}`)
    assert.match(response.headers.get('content-type') || '', /text\/html/i, `Not an HTML page: ${path}`)
    assert.match(body, /<html|<!doctype html/i, `SPA document missing: ${path}`)
    checks++
  }
  for (const path of ['/api/install/status', '/user/registration/config']) {
    const { response, body } = await get(path)
    assert.equal(response.status, 200, `Public startup API failed: ${path}`)
    const payload = envelope(response, body, path)
    // AjaxJson.CODE_SUCCESS is the string "0", not the HTTP status 200.
    assert.equal(payload.code, '0', `Business startup error: ${path}`)
    if (path === '/api/install/status')
      assert.equal(typeof payload.data, 'boolean', 'Install status must be a boolean, including false for a fresh site')
    else assert.ok(payload.data && typeof payload.data.enabled === 'boolean'
      && typeof payload.data.mailAvailable === 'boolean', 'Registration configuration is incomplete')
    checks++
  }
  // Probe actual GET handlers. Upload endpoints are PUT/POST and are not read-only probes;
  // custom direct-link prefixes cannot be assumed to remain /d on an existing deployment.
  for (const path of ['/api/storage/list', '/admin/overview',
    '/api/share/info/deployment-smoke-nonexistent', '/api/share/download/deployment-smoke-nonexistent',
    '/s/deployment-smoke-nonexistent', '/pd/deployment-smoke-nonexistent/test.txt',
    '/open115/url/1/deployment-smoke-nonexistent', '/api/site/config/userRootPath/deployment-smoke-nonexistent']) {
    const { response, body } = await get(path)
    const payload = envelope(response, body, path)
    assert.equal(response.status, 401, `Expected anonymous authentication rejection, not a server/routing error: ${path}`)
    assert.equal(payload.code, '42000', `Expected authentication rejection: ${path}`)
    checks++
  }
  const privateProbe = await get('/internal/ready')
  assert.equal(privateProbe.response.status, 404, 'Private backend readiness must not be public')
  checks++
  const missingAsset = await get('/assets/deployment-smoke-does-not-exist.js')
  assert.equal(missingAsset.response.status, 404, 'Missing assets must not silently return index.html')
  checks++
  return checks
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runDeploymentSmoke(process.argv[2] || 'http://127.0.0.1')
    .then(checks => console.log(`Deployment read-only smoke checks passed: ${checks}. No account/file/database mutations performed.`))
    .catch(error => { console.error(`Deployment smoke failed: ${error.message}`); process.exitCode = 1 })
}
