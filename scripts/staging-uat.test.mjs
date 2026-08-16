import assert from 'node:assert/strict'
import { describe, test } from 'node:test'

import { runStagingUat } from './staging-uat.mjs'

const branchId = '11111111-1111-4111-8111-111111111111'
const restaurantId = '22222222-2222-4222-8222-222222222222'
const reservationId = '33333333-3333-4333-8333-333333333333'
const manageToken = 'm'.repeat(43)
const adminToken = 'admin-access-token'
const nonAdminToken = 'staff-access-token'
const guestPhone = '+821055501234'
const runId = '44444444-4444-4444-8444-444444444444'

describe('staging write UAT', () => {
  test('refuses all requests without explicit write confirmation', async () => {
    let calls = 0
    await assert.rejects(
      runStagingUat({
        ...validOptions(),
        confirmWrite: false,
        fetchImpl: async () => {
          calls += 1
          throw new Error('must not be called')
        },
      }),
      /--confirm-write/u,
    )
    assert.equal(calls, 0)
  })

  test('checks authorization and completes an idempotent create-manage-cancel lifecycle', async () => {
    const service = mockService()

    const result = await runStagingUat({ ...validOptions(), fetchImpl: service.fetch })

    assert.equal(result.cleanup, 'cancelled')
    assert.equal(result.date, '2026-08-17')
    assert.equal(result.checks.length, 10)
    assert.equal(result.requestIds.length, service.calls.length)
    assert.equal(service.createCalls, 2)
    assert.equal(service.cancelled, true)
    const serialized = JSON.stringify(result)
    assert.equal(serialized.includes(adminToken), false)
    assert.equal(serialized.includes(nonAdminToken), false)
    assert.equal(serialized.includes(guestPhone), false)
    assert.equal(serialized.includes(manageToken), false)
  })

  test('stops before creating data when the admin token lacks its role', async () => {
    const service = mockService({ rejectAdmin: true })

    await assert.rejects(
      runStagingUat({ ...validOptions(), fetchImpl: service.fetch }),
      /HTTP 200 대신 403/u,
    )
    assert.equal(service.createCalls, 0)
  })

  test('uses the admin transition fallback when a create response cannot be parsed', async () => {
    const service = mockService({ unreadableCreateResponse: true, exposeCreatedInAdminList: true })

    await assert.rejects(
      runStagingUat({ ...validOptions(), fetchImpl: service.fetch }),
      /application\/json/u,
    )
    assert.equal(service.adminCleanupCalls, 1)
    assert.equal(service.cancelled, true)
  })

  test('reports a sanitized manual cleanup marker when automatic cleanup fails', async () => {
    const service = mockService({ unreadableCreateResponse: true })

    await assert.rejects(
      runStagingUat({ ...validOptions(), fetchImpl: service.fetch }),
      (error) => error instanceof Error
        && error.message.includes(`run ${runId.slice(0, 8)}`)
        && error.message.includes('자동 정리에 실패')
        && !error.message.includes(adminToken)
        && !error.message.includes(guestPhone)
        && !error.message.includes(manageToken),
    )
    assert.equal(service.cancelled, false)
  })
})

function validOptions() {
  return {
    baseUrl: 'https://staging.tableflow.test',
    adminToken,
    nonAdminToken,
    guestName: 'TableFlow UAT',
    guestPhone,
    partySize: 1,
    searchDays: 3,
    confirmWrite: true,
    now: new Date('2026-08-16T00:00:00Z'),
    runId,
  }
}

function mockService({
  rejectAdmin = false,
  unreadableCreateResponse = false,
  exposeCreatedInAdminList = false,
} = {}) {
  let created = false
  let cancelled = false
  let createCalls = 0
  let adminCleanupCalls = 0
  const calls = []

  const fetch = async (input, init = {}) => {
    const url = new URL(input)
    const headers = new Headers(init.headers)
    const authorization = headers.get('authorization')
    const method = init.method ?? 'GET'
    calls.push({ method, path: url.pathname, authorization })

    if (url.pathname === '/api/admin/restaurants' && method === 'GET') {
      if (!authorization) return jsonResponse({ title: 'Unauthorized' }, 401, headers)
      if (authorization === `Bearer ${nonAdminToken}`) return jsonResponse({ title: 'Forbidden' }, 403, headers)
      if (rejectAdmin) return jsonResponse({ title: 'Forbidden' }, 403, headers)
      return jsonResponse([{ id: restaurantId, name: 'Test restaurant' }], 200, headers)
    }
    if (url.pathname === `/api/admin/restaurants/${restaurantId}/branches`) {
      return jsonResponse([{
        id: branchId,
        timezone: 'Asia/Seoul',
        changeCutoffMinutes: 180,
      }], 200, headers)
    }
    if (url.pathname === '/api/public/booking-context') {
      return jsonResponse({
        privacyPolicyVersion: '2026-08-16',
        privacyRetentionDays: 365,
        branches: [{
          id: branchId,
          restaurantName: 'Test restaurant',
          name: 'Seoul',
          address: 'Test address',
          timezone: 'Asia/Seoul',
          bookingHorizonDays: 30,
          maxPartySize: 8,
        }],
      }, 200, headers)
    }
    if (url.pathname === `/api/public/branches/${branchId}/availability`) {
      return jsonResponse({
        branchId,
        date: url.searchParams.get('date'),
        timezone: 'Asia/Seoul',
        partySize: 1,
        asOf: '2026-08-16T00:00:00Z',
        slots: [{
          startsAt: '2026-08-17T10:00:00+09:00',
          endsAt: '2026-08-17T11:30:00+09:00',
          availableTableCount: 1,
        }],
      }, 200, headers)
    }
    if (url.pathname === '/api/public/reservations' && method === 'POST') {
      createCalls += 1
      created = true
      if (unreadableCreateResponse) {
        return new Response('created', {
          status: 201,
          headers: responseHeaders(headers, { 'Content-Type': 'text/plain' }),
        })
      }
      return jsonResponse(createdResponse(), 201, headers)
    }
    if (url.pathname === '/api/public/reservations/ABCDEF2345' && method === 'GET') {
      return jsonResponse(reservationResponse(), 200, headers)
    }
    if (url.pathname === '/api/public/reservations/ABCDEF2345/cancellation' && method === 'POST') {
      cancelled = true
      return jsonResponse(reservationResponse(), 200, headers)
    }
    if (url.pathname === `/api/admin/branches/${branchId}/reservations`) {
      const items = created && exposeCreatedInAdminList && !cancelled
        ? [{
            ...reservationResponse(),
            id: reservationId,
            guestName: `TableFlow UAT ${runId.slice(0, 8)}`,
            guestPhoneLastFour: '1234',
            history: [],
          }]
        : []
      return jsonResponse({
        items,
        page: 0,
        size: 100,
        totalElements: items.length,
        totalPages: items.length === 0 ? 0 : 1,
        first: true,
        last: true,
      }, 200, headers)
    }
    if (url.pathname === `/api/admin/reservations/${reservationId}/transitions` && method === 'POST') {
      adminCleanupCalls += 1
      cancelled = true
      return jsonResponse({ ...reservationResponse(), id: reservationId }, 200, headers)
    }
    throw new Error(`unexpected request: ${method} ${url.pathname}${url.search}`)
  }

  function reservationResponse() {
    return {
      reservationCode: 'ABCDEF2345',
      branchId,
      branchName: 'Seoul',
      timezone: 'Asia/Seoul',
      tableName: 'T1',
      partySize: 1,
      startsAt: '2026-08-17T10:00:00+09:00',
      endsAt: '2026-08-17T11:30:00+09:00',
      status: cancelled ? 'CANCELLED' : 'PENDING',
      version: cancelled ? 1 : 0,
      createdAt: '2026-08-16T00:00:00Z',
      updatedAt: '2026-08-16T00:00:00Z',
    }
  }

  function createdResponse() {
    return { reservation: reservationResponse(), manageToken }
  }

  return {
    fetch,
    calls,
    get createCalls() { return createCalls },
    get adminCleanupCalls() { return adminCleanupCalls },
    get cancelled() { return cancelled },
  }
}

function jsonResponse(body, status, requestHeaders) {
  return new Response(JSON.stringify(body), {
    status,
    headers: responseHeaders(requestHeaders, { 'Content-Type': 'application/json' }),
  })
}

function responseHeaders(requestHeaders, additional) {
  return {
    'X-Request-Id': requestHeaders.get('x-request-id'),
    ...additional,
  }
}
