#!/usr/bin/env node
// 部署后的只读冒烟验收，不注册用户、不上传/删除文件、不初始化数据库。
// node scripts/deployment-smoke.mjs https://cloud.example.com
import assert from 'node:assert/strict'

const origin = new URL(process.argv[2] || 'http://127.0.0.1')
assert.ok(['http:', 'https:'].includes(origin.protocol), 'Only HTTP(S) deployment URLs are accepted')
assert.equal(origin.pathname, '/', 'Pass the website origin, not a page URL')
assert.equal(origin.username + origin.password + origin.search + origin.hash, '', 'No credentials/query/fragment in URL')

async function get(path, accept = 'application/json') {
  const response = await fetch(new URL(path, origin), {
    headers: { Accept: accept },
    redirect: 'manual',
    signal: AbortSignal.timeout(15000),
  })
  return { response, body: await response.text() }
}

let checks = 0
for (const path of ['/login', '/workflow/documents', '/admin/overview', '/admin/storage-list']) {
  const { response, body } = await get(path, 'text/html')
  assert.equal(response.status, 200, `SPA refresh failed: ${path}`)
  assert.match(response.headers.get('content-type') || '', /text\/html/, `Not an HTML page: ${path}`)
  assert.match(body, /<html|<!doctype html/i, `SPA document missing: ${path}`)
  checks++
}
for (const path of ['/api/install/status', '/user/registration/config']) {
  const { response, body } = await get(path)
  assert.equal(response.status, 200, `Public startup API failed: ${path}`)
  assert.match(response.headers.get('content-type') || '', /json/, `API accidentally served SPA: ${path}`)
  assert.equal(JSON.parse(body).code, 200, `Business startup error: ${path}`)
  checks++
}
// 未登录接口必须拒绝，不能因为 /admin 的 SPA 回退而得到 HTML，也不能放行文件。
for (const path of ['/api/storage/list', '/admin/overview', '/s/deployment-smoke-nonexistent',
  '/pd/deployment-smoke-nonexistent/test.txt', '/d/deployment-smoke-nonexistent/test.txt',
  '/open115/url/1/deployment-smoke-nonexistent', '/file/upload/deployment-smoke-nonexistent/test.txt']) {
  const { response, body } = await get(path)
  assert.match(response.headers.get('content-type') || '', /json/, `Protected route served SPA: ${path}`)
  const payload = JSON.parse(body)
  assert.ok(response.status >= 400 || payload.code !== 200, `Anonymous business access unexpectedly succeeded: ${path}`)
  checks++
}
const privateProbe = await get('/internal/ready')
assert.equal(privateProbe.response.status, 404, 'Private backend readiness must not be public')
checks++
const missingAsset = await get('/assets/deployment-smoke-does-not-exist.js')
assert.equal(missingAsset.response.status, 404, 'Missing assets must not silently return index.html')
checks++
console.log(`Deployment read-only smoke checks passed: ${checks}. No account/file/database mutations performed.`)
