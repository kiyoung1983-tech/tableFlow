import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { after, before, describe, it } from 'node:test'
import { runStagingSmoke } from './staging-smoke.mjs'

const csp = "default-src 'self'; connect-src 'self' https://idp.example.test; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
let server
let baseUrl

before(async () => {
  server = createServer((request, response) => {
    setSecurityHeaders(response)
    if (request.url === '/healthz') return send(response, 200, 'text/plain', 'ok\n')
    if (request.url === '/actuator/health') {
      return send(response, 200, 'application/json', JSON.stringify({ status: 'UP' }))
    }
    if (request.url === '/api/public/booking-context') {
      response.setHeader('X-Request-Id', request.headers['x-request-id'] ?? '')
      return send(response, 200, 'application/json', JSON.stringify({
        privacyPolicyVersion: '2026-08-16',
        privacyRetentionDays: 365,
        branches: [{
          id: '20000000-0000-0000-0000-000000000001',
          restaurantName: 'TableFlow',
          name: '강남점',
          address: '서울시 강남구',
          timezone: 'Asia/Seoul',
          bookingHorizonDays: 30,
          maxPartySize: 8,
        }],
      }))
    }
    if (request.url === '/actuator/prometheus') {
      return send(response, 404, 'application/problem+json', '{"status":404}')
    }
    if (['/reservations/new', '/privacy', '/admin/settings'].includes(request.url)) {
      return send(response, 200, 'text/html', '<!doctype html><div id="root"></div>')
    }
    return send(response, 404, 'text/plain', 'not found')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  baseUrl = `http://127.0.0.1:${address.port}`
})

after(async () => {
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
})

describe('staging smoke', () => {
  it('passes every read-only deployment check against a conforming service', async () => {
    const result = await runStagingSmoke({ baseUrl, requestId: 'smoke-contract-test' })

    assert.deepEqual(result.passed, [
      '프런트 health',
      '백엔드 health',
      'SPA 딥링크와 보안 헤더',
      '공개 예약 context',
      '비공개 Actuator 차단',
    ])
  })

  it('rejects insecure remote HTTP unless explicitly allowed', async () => {
    await assert.rejects(
      runStagingSmoke({ baseUrl: 'http://staging.example.test' }),
      /HTTPS가 필요합니다/,
    )
  })

  it('reports the exact failed gate', async () => {
    const fetchWithoutCsp = async (url, options) => {
      const response = await fetch(url, options)
      if (new URL(url).pathname !== '/privacy') return response
      const headers = new Headers(response.headers)
      headers.delete('content-security-policy')
      return new Response(await response.text(), {
        status: response.status,
        headers,
      })
    }

    await assert.rejects(
      runStagingSmoke({ baseUrl, fetchImpl: fetchWithoutCsp }),
      /SPA 딥링크와 보안 헤더 실패: Content-Security-Policy/,
    )
  })
})

function setSecurityHeaders(response) {
  response.setHeader('X-Content-Type-Options', 'nosniff')
  response.setHeader('X-Frame-Options', 'DENY')
  response.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin')
  response.setHeader('Content-Security-Policy', csp)
}

function send(response, status, contentType, body) {
  response.writeHead(status, { 'Content-Type': contentType })
  response.end(body)
}
