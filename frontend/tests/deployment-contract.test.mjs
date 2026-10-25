import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const read = path => readFileSync(new URL(path, import.meta.url), 'utf8').replace(/\r\n/g, '\n')
const routes = read('../nginx/routes.conf')
const proxy = read('../nginx/proxy.conf')
const compose = read('../../docker/compose.production.yml')

test('production Nginx proxies every known fixed backend prefix', () => {
  const line = routes.split('\n').find(value => value.startsWith('location ~ ^/(api|'))
  const expression = new RegExp(line.slice('location ~ '.length, line.indexOf(' {')))
  for (const prefix of ['api', 'user', 'file', 'pd', 'd', 's', 'directlink', 'sso', 'onlyOffice',
    'onedrive', 'onedirve', 'gd', 's3', 'sharepoint', '115', 'open115']) {
    assert.ok(expression.test(`/${prefix}/test`), `Missing backend prefix: ${prefix}`)
    assert.ok(!expression.test(`/${prefix}-frontend-page/test`), `Overbroad proxy prefix: ${prefix}`)
  }
})
test('admin HTML navigation and JSON APIs are separated', () => {
  const config = read('../nginx/default.conf')
  assert.match(config, /\(GET\|HEAD\):\.\*text\/html/)
  assert.match(routes, /if \(\$admin_navigation\) \{ rewrite \^ \/index\.html last; \}/)
  assert.match(routes, /location ~ \^\/admin/)
})
test('SSE and whole-file uploads remain unbuffered; writes cannot be proxy-retried', () => {
  assert.match(routes, /location \^~ \/api\/ai\/chat\//)
  assert.match(routes, /proxy_buffering off;/)
  assert.match(routes, /gzip off;/)
  assert.match(routes.split('location = /healthz')[0], /proxy_request_buffering off;/)
  assert.doesNotMatch(proxy, /proxy_request_buffering\s+(?:on|off);/)
  assert.match(proxy, /proxy_next_upstream off;/)
  assert.match(proxy, /proxy_pass http:\/\/cloud_backend;/)
})

test('resumable chunk requests are bounded and fully buffered before reaching backend slots', () => {
  const expressionText = '^/api/uploads/resumable/[^/]+/chunks/[^/]+/?$'
  const header = `location ~ ${expressionText} {`
  const offset = routes.indexOf(header)
  assert.ok(offset >= 0)
  const block = routes.slice(offset).split('\n}')[0]
  const expression = new RegExp(expressionText)
  assert.ok(expression.test('/api/uploads/resumable/synthetic-id/chunks/0'))
  assert.ok(expression.test('/api/uploads/resumable/synthetic-id/chunks/999/'))
  for (const path of ['/api/uploads/resumable/init', '/api/uploads/resumable/synthetic-id/complete',
    '/api/uploads/resumable/synthetic-id/chunks/0/other', '/api/ai/chat/stream', '/file/upload']) {
    assert.ok(!expression.test(path), `Chunk rule must not change ${path}`)
  }
  assert.ok(offset < routes.indexOf('location ~ ^/(api|'), 'First matching regex must be the chunk rule')
  assert.match(block, /client_max_body_size 4m;/)
  assert.match(block, /client_body_timeout 15s;/)
  assert.match(block, /proxy_request_buffering on;/)
  assert.match(block, /proxy_buffering off;/)
  assert.match(block, /client_body_temp_path \/tmp\/nginx-chunk-body 1 2;/)
  assert.match(block, /limit_conn chunk_buffers_global 12;/)
  assert.match(block, /limit_conn chunk_buffers_ip 4;/)
  assert.match(block, /limit_conn_status 429;/)
  assert.doesNotMatch(block, /error_page|proxy_intercept_errors/, 'Nginx 413 must remain an HTTP error')
  assert.match(compose, /\/tmp:size=64m,mode=1777/)
  assert.ok(12 * 4 < 64, 'Bounded chunk bodies must fit private tmpfs with headroom')
})

test('HTTP and HTTPS share chunk protections without broad API-prefix shadowing', () => {
  for (const file of ['../nginx/default.conf', '../nginx/https.conf.example']) {
    const config = read(file)
    assert.match(config, /include \/etc\/nginx\/student-cloud\/routes\.conf;/)
    assert.match(config, /limit_conn_zone \$server_name zone=chunk_buffers_global:1m;/)
    assert.match(config, /limit_conn_zone \$binary_remote_addr zone=chunk_buffers_ip:1m;/)
    assert.doesNotMatch(config + routes, /location \^~ \/api\/?\s*\{/)
  }
})
test('Nginx overwrites forwarded IP headers and excludes private readiness', () => {
  assert.match(proxy, /X-Forwarded-For \$remote_addr;/)
  assert.doesNotMatch(proxy, /\$proxy_add_x_forwarded_for/)
  assert.match(routes, /location ~ \^\/internal/)
  assert.match(compose, /SERVER_FORWARD_HEADERS_STRATEGY: none/)
  assert.match(compose, /STUDENT_CLOUD_TRUSTED_PROXY_CIDRS: 10\.77\.0\.10\/32/)
})
test('production secrets do not enter build args; data services publish no ports', () => {
  for (const service of ['mysql', 'redis-cache', 'backend']) {
    const section = compose.split(`  ${service}:\n`)[1].split(/^  [\w-]+:/m)[0]
    assert.doesNotMatch(section, /\bports:/, `${service} must stay inside Docker`)
  }
  assert.doesNotMatch(compose, /\bargs:/)
  assert.match(compose, /business-data:\/app\/data/)
  assert.match(compose, /mysql-data:\/var\/lib\/mysql/)
})
test('shared cache and rate limiter fail closed instead of evicting counters', () => {
  assert.match(compose, /--maxmemory-policy noeviction/)
  assert.match(compose, /STUDENT_CLOUD_RATE_LIMIT_FAILURE_MODE: closed/)
  assert.match(compose, /REDIS_PASSWORD: \$\{STUDENT_CLOUD_REDIS_PASSWORD:\?/)
})
test('builds use frozen frontend source and overridable public backend base images', () => {
  const frontend = read('../Dockerfile.deploy')
  const backend = read('../../backend/Dockerfile')
  assert.match(frontend, /FROM node:22-alpine AS builder/)
  assert.match(frontend, /pnpm install --frozen-lockfile/)
  const dependencyCopy = frontend.indexOf('COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./')
  const dependencyInstall = frontend.indexOf('RUN pnpm install --frozen-lockfile')
  assert.ok(dependencyCopy >= 0 && dependencyCopy < dependencyInstall,
    'Workspace overrides and allowBuilds must be present before frozen dependency installation')
  assert.match(frontend, /COPY --from=builder \/app\/dist\//)
  assert.match(frontend, /FROM nginx:stable-alpine/)
  assert.doesNotMatch(frontend, /personal\.cr\.aliyuncs\.com/)
  assert.ok(backend.includes('ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21-alpine'))
  assert.ok(backend.includes('ARG RUNTIME_IMAGE=ibm-semeru-runtimes:open-21-jre-jammy'))
  assert.ok(backend.includes('FROM ${MAVEN_IMAGE} AS builder'))
  assert.ok(backend.includes('FROM ${RUNTIME_IMAGE} AS runtime'))
  assert.doesNotMatch(backend, /personal\.cr\.aliyuncs\.com/)
  assert.match(compose, /STUDENT_CLOUD_COURSE_STORAGE_ROOT: \/app\/data\/course-materials/)
  assert.match(backend, /mkdir -p .*\/app\/data\/course-materials/)
  assert.match(backend, /USER 10001:10001/)
  assert.match(backend, /\/internal\/ready/)
})
test('local profiles, runtime secrets and TLS private keys are excluded from image contexts', () => {
  assert.match(read('../../backend/.dockerignore'), /\*\*\/application-local\*\.properties/)
  assert.match(read('../.dockerignore'), /\*\*\/\*\.key/)
  assert.match(read('../../docker/.gitignore'), /\.env\.production/)
})
