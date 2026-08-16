#!/usr/bin/env node

import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const requiredCspDirectives = [
  "default-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "frame-ancestors 'none'",
]

export async function runStagingSmoke({
  baseUrl,
  timeoutMs = 10_000,
  allowHttp = false,
  fetchImpl = globalThis.fetch,
  requestId = `staging-smoke-${randomUUID()}`,
} = {}) {
  const origin = validateBaseUrl(baseUrl, allowHttp)
  if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 120_000) {
    throw new Error('timeoutMs는 100~120000 사이의 정수여야 합니다.')
  }
  if (typeof fetchImpl !== 'function') {
    throw new Error('Fetch API를 사용할 수 있는 Node.js 24 이상이 필요합니다.')
  }

  const passed = []
  const check = async (name, operation) => {
    try {
      await operation()
      passed.push(name)
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error)
      throw new Error(`${name} 실패: ${detail}`, { cause: error })
    }
  }
  const request = (path, options = {}) => fetchImpl(new URL(path, origin), {
    ...options,
    redirect: 'manual',
    signal: AbortSignal.timeout(timeoutMs),
  })

  await check('프런트 health', async () => {
    const response = await request('/healthz')
    assertStatus(response, 200)
    const body = (await response.text()).trim()
    if (body !== 'ok') throw new Error(`응답 본문이 ok가 아닙니다: ${body}`)
  })

  await check('백엔드 health', async () => {
    const response = await request('/actuator/health')
    assertStatus(response, 200)
    assertJson(response)
    const body = await response.json()
    if (body?.status !== 'UP') throw new Error(`백엔드 상태가 UP이 아닙니다: ${body?.status}`)
  })

  await check('SPA 딥링크와 보안 헤더', async () => {
    for (const path of ['/reservations/new', '/privacy', '/admin/settings']) {
      const response = await request(path)
      assertStatus(response, 200)
      assertContentType(response, 'text/html')
      assertSecurityHeaders(response)
      const body = await response.text()
      if (!body.includes('id="root"')) {
        throw new Error(`${path}가 SPA index를 반환하지 않았습니다.`)
      }
    }
  })

  await check('공개 예약 context', async () => {
    const response = await request('/api/public/booking-context', {
      headers: { 'X-Request-Id': requestId },
    })
    assertStatus(response, 200)
    assertJson(response)
    if (response.headers.get('x-request-id') !== requestId) {
      throw new Error('X-Request-Id가 그대로 반환되지 않았습니다.')
    }
    const body = await response.json()
    if (typeof body?.privacyPolicyVersion !== 'string' || body.privacyPolicyVersion === '') {
      throw new Error('privacyPolicyVersion이 비어 있습니다.')
    }
    if (!Number.isInteger(body?.privacyRetentionDays) || body.privacyRetentionDays < 1) {
      throw new Error('privacyRetentionDays가 양의 정수가 아닙니다.')
    }
    if (!Array.isArray(body?.branches)) throw new Error('branches가 배열이 아닙니다.')
    for (const branch of body.branches) assertPublicBranch(branch)
  })

  await check('비공개 Actuator 차단', async () => {
    const response = await request('/actuator/prometheus')
    assertStatus(response, 404)
    const body = await response.text()
    if (body.includes('# HELP') || body.includes('# TYPE')) {
      throw new Error('Prometheus 지표가 외부 응답에 포함되었습니다.')
    }
  })

  return { baseUrl: origin.origin, passed, requestId }
}

export function parseOptions(args, environment = process.env) {
  const options = {
    baseUrl: environment.TABLEFLOW_BASE_URL,
    timeoutMs: 10_000,
    allowHttp: false,
  }
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--base-url') options.baseUrl = requiredValue(args, ++index, argument)
    else if (argument === '--timeout-ms') {
      options.timeoutMs = Number(requiredValue(args, ++index, argument))
    } else if (argument === '--allow-http') options.allowHttp = true
    else if (argument === '--help' || argument === '-h') options.help = true
    else throw new Error(`알 수 없는 옵션입니다: ${argument}`)
  }
  return options
}

function validateBaseUrl(value, allowHttp) {
  if (!value) throw new Error('--base-url 또는 TABLEFLOW_BASE_URL이 필요합니다.')
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`올바른 URL이 아닙니다: ${value}`)
  }
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error('base URL은 http 또는 https여야 합니다.')
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('base URL에는 자격정보, query, fragment를 넣지 마세요.')
  }
  if (url.pathname !== '/' && url.pathname !== '') {
    throw new Error('base URL은 경로가 없는 서비스 origin이어야 합니다.')
  }
  const isLocal = ['localhost', '127.0.0.1', '::1'].includes(url.hostname)
  if (url.protocol !== 'https:' && !isLocal && !allowHttp) {
    throw new Error('원격 스테이징 검증에는 HTTPS가 필요합니다. 의도한 HTTP 환경만 --allow-http를 사용하세요.')
  }
  url.pathname = '/'
  return url
}

function assertStatus(response, expected) {
  if (response.status !== expected) {
    throw new Error(`HTTP ${expected} 대신 ${response.status}를 반환했습니다.`)
  }
}

function assertJson(response) {
  assertContentType(response, 'application/json')
}

function assertContentType(response, expected) {
  const actual = response.headers.get('content-type') ?? ''
  if (!actual.toLowerCase().includes(expected)) {
    throw new Error(`Content-Type에 ${expected}가 없습니다: ${actual || '(없음)'}`)
  }
}

function assertSecurityHeaders(response) {
  const expectedHeaders = {
    'x-content-type-options': 'nosniff',
    'x-frame-options': 'DENY',
    'referrer-policy': 'strict-origin-when-cross-origin',
  }
  for (const [name, expected] of Object.entries(expectedHeaders)) {
    const actual = response.headers.get(name)
    if (actual !== expected) throw new Error(`${name} 값이 ${expected}가 아닙니다: ${actual}`)
  }
  const csp = response.headers.get('content-security-policy') ?? ''
  for (const directive of requiredCspDirectives) {
    if (!csp.includes(directive)) throw new Error(`Content-Security-Policy에 ${directive}가 없습니다.`)
  }
}

function assertPublicBranch(branch) {
  const stringFields = ['id', 'restaurantName', 'name', 'address', 'timezone']
  for (const field of stringFields) {
    if (typeof branch?.[field] !== 'string' || branch[field] === '') {
      throw new Error(`지점 ${field}가 비어 있습니다.`)
    }
  }
  for (const field of ['bookingHorizonDays', 'maxPartySize']) {
    if (!Number.isInteger(branch?.[field]) || branch[field] < 1) {
      throw new Error(`지점 ${field}가 양의 정수가 아닙니다.`)
    }
  }
}

function requiredValue(args, index, option) {
  const value = args[index]
  if (!value || value.startsWith('--')) throw new Error(`${option} 값이 필요합니다.`)
  return value
}

function printHelp() {
  console.log(`TableFlow 스테이징 smoke test

사용법:
  node scripts/staging-smoke.mjs --base-url https://staging.example.com

옵션:
  --base-url <url>    검증할 프런트 origin (TABLEFLOW_BASE_URL로도 지정 가능)
  --timeout-ms <ms>   요청별 제한 시간, 기본 10000
  --allow-http        localhost 외 HTTP를 명시적으로 허용
  -h, --help          도움말`)
}

async function main() {
  try {
    const options = parseOptions(process.argv.slice(2))
    if (options.help) return printHelp()
    const result = await runStagingSmoke(options)
    for (const name of result.passed) console.log(`PASS  ${name}`)
    console.log(`PASS  ${result.baseUrl} smoke test 완료 (${result.requestId})`)
  } catch (error) {
    console.error(`FAIL  ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}

const entryPoint = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (import.meta.url === entryPoint) await main()
