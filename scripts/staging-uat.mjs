#!/usr/bin/env node

import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

export async function runStagingUat({
  baseUrl,
  adminToken,
  nonAdminToken,
  guestName,
  guestPhone,
  branchId,
  date,
  partySize = 1,
  searchDays = 14,
  timeoutMs = 10_000,
  allowHttp = false,
  confirmWrite = false,
  fetchImpl = globalThis.fetch,
  now = new Date(),
  runId = randomUUID(),
} = {}) {
  const origin = validateBaseUrl(baseUrl, allowHttp)
  validateInputs({
    adminToken,
    nonAdminToken,
    guestName,
    guestPhone,
    branchId,
    date,
    partySize,
    searchDays,
    timeoutMs,
    confirmWrite,
    fetchImpl,
  })

  const checks = []
  const warnings = []
  const requestIds = []
  let requestSequence = 0
  let created = null
  let cancelled = false
  let selected = null
  let creationAttempted = false
  let requestBody = null

  const request = async (path, {
    method = 'GET',
    token,
    headers = {},
    json,
    expectedStatus = 200,
  } = {}) => {
    const requestId = `staging-uat-${runId}-${++requestSequence}`
    const requestHeaders = new Headers({
      Accept: 'application/json',
      'X-Request-Id': requestId,
      ...headers,
    })
    if (token) requestHeaders.set('Authorization', `Bearer ${token}`)
    if (json !== undefined) requestHeaders.set('Content-Type', 'application/json')

    let response
    try {
      response = await fetchImpl(new URL(path, origin), {
        method,
        headers: requestHeaders,
        body: json === undefined ? undefined : JSON.stringify(json),
        redirect: 'manual',
        signal: AbortSignal.timeout(timeoutMs),
      })
    } catch (error) {
      throw new Error(`${method} ${safePath(path)} 요청 실패: ${error instanceof Error ? error.message : String(error)}`)
    }
    if (response.status !== expectedStatus) {
      throw new Error(`${method} ${safePath(path)}가 HTTP ${expectedStatus} 대신 ${response.status}를 반환했습니다.`)
    }
    const echoedRequestId = response.headers.get('x-request-id')
    if (echoedRequestId !== requestId) {
      if (method === 'GET') {
        throw new Error(`${method} ${safePath(path)}가 X-Request-Id를 그대로 반환하지 않았습니다.`)
      }
      warnings.push(`${method} ${safePath(path)}가 X-Request-Id를 그대로 반환하지 않았습니다.`)
    }
    requestIds.push(requestId)
    if (expectedStatus === 204) return null

    const contentType = response.headers.get('content-type') ?? ''
    if (!contentType.toLowerCase().includes('application/json')) {
      throw new Error(`${method} ${safePath(path)} 응답이 application/json이 아닙니다.`)
    }
    try {
      return await response.json()
    } catch {
      throw new Error(`${method} ${safePath(path)} 응답을 JSON으로 해석할 수 없습니다.`)
    }
  }

  const pass = (name) => checks.push(name)

  await request('/api/admin/restaurants', { expectedStatus: 401 })
  pass('관리자 API 비인증 401')

  if (nonAdminToken) {
    await request('/api/admin/restaurants', { token: nonAdminToken, expectedStatus: 403 })
    pass('관리자 역할 없는 token 403')
  } else {
    warnings.push('TABLEFLOW_UAT_NON_ADMIN_TOKEN이 없어 비관리자 역할 403 검사를 건너뛰었습니다.')
  }

  const restaurants = await request('/api/admin/restaurants', { token: adminToken })
  if (!Array.isArray(restaurants)) throw new Error('관리자 식당 목록이 배열이 아닙니다.')
  pass('관리자 역할 token 200')

  const context = await request('/api/public/booking-context')
  assertBookingContext(context)
  pass('공개 booking context')

  const publicBranches = branchId
    ? context.branches.filter((branch) => branch.id === branchId)
    : context.branches
  if (publicBranches.length === 0) {
    throw new Error(branchId ? '지정한 branch ID가 공개 booking context에 없습니다.' : '예약 가능한 활성 지점이 없습니다.')
  }

  const adminBranches = []
  for (const restaurant of restaurants) {
    if (typeof restaurant?.id !== 'string') throw new Error('관리자 식당 응답의 id가 올바르지 않습니다.')
    const branches = await request(`/api/admin/restaurants/${encodeURIComponent(restaurant.id)}/branches`, {
      token: adminToken,
    })
    if (!Array.isArray(branches)) throw new Error('관리자 지점 목록이 배열이 아닙니다.')
    adminBranches.push(...branches)
  }

  selected = await findSafeSlot({
    publicBranches,
    adminBranches,
    requestedDate: date,
    partySize,
    searchDays,
    now,
    request,
  })
  pass('취소 가능한 공개 예약 슬롯')

  const idempotencyKey = randomUUID()
  requestBody = {
    branchId: selected.branch.id,
    startsAt: selected.slot.startsAt,
    partySize,
    guestName: `${guestName} ${runId.slice(0, 8)}`,
    guestPhone,
    privacyAgreement: {
      agreed: true,
      policyVersion: context.privacyPolicyVersion,
    },
  }

  let primaryError = null
  try {
    creationAttempted = true
    created = await request('/api/public/reservations', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      json: requestBody,
      expectedStatus: 201,
    })
    assertCreatedReservation(created, selected.branch.id)
    pass('예약 생성')

    const replay = await request('/api/public/reservations', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      json: requestBody,
      expectedStatus: 201,
    })
    assertCreatedReservation(replay, selected.branch.id)
    if (replay.reservation.reservationCode !== created.reservation.reservationCode
        || replay.manageToken !== created.manageToken) {
      throw new Error('같은 멱등 요청이 동일한 예약과 관리 token을 반환하지 않았습니다.')
    }
    pass('예약 생성 멱등 재시도')

    const managed = await request(
      `/api/public/reservations/${encodeURIComponent(created.reservation.reservationCode)}`,
      { headers: { 'X-Reservation-Token': created.manageToken } },
    )
    assertManagedReservation(managed, created.reservation.reservationCode)
    pass('고객 관리 token 조회')

    const cancellation = await cancelCreatedReservation({ created, managed, request })
    cancelled = true
    pass('고객 예약 취소')

    const afterCancellation = await request(
      `/api/public/reservations/${encodeURIComponent(created.reservation.reservationCode)}`,
      { headers: { 'X-Reservation-Token': created.manageToken } },
    )
    if (afterCancellation?.status !== 'CANCELLED'
        || afterCancellation?.version !== cancellation.version) {
      throw new Error('취소 후 조회가 최신 CANCELLED 상태를 반환하지 않았습니다.')
    }
    pass('취소 상태 재조회')
  } catch (error) {
    primaryError = error
  } finally {
    if (creationAttempted && !cancelled) {
      let customerCleanupError = null
      try {
        if (created) {
          let current = created.reservation
          try {
            current = await request(
              `/api/public/reservations/${encodeURIComponent(created.reservation.reservationCode)}`,
              { headers: { 'X-Reservation-Token': created.manageToken } },
            )
          } catch {
            // 생성 응답 version으로 정리를 계속 시도한다.
          }
          if (current?.status === 'CANCELLED') cancelled = true
          else {
            await cancelCreatedReservation({ created, managed: current, request })
            cancelled = true
          }
        }
      } catch (cleanupError) {
        customerCleanupError = cleanupError
      }

      let adminCleanupError = null
      if (!cancelled) {
        try {
          const found = await adminCleanupReservation({
            adminToken,
            branchId: selected.branch.id,
            date: selected.date,
            guestName: requestBody.guestName,
            startsAt: requestBody.startsAt,
            request,
          })
          if (found) {
            warnings.push('고객 token 정리 대신 관리자 전이로 UAT 예약을 취소했습니다.')
            cancelled = true
          }
        } catch (cleanupError) {
          adminCleanupError = cleanupError
        }
      }

      if (!cancelled) {
        const cleanupMessages = [customerCleanupError, adminCleanupError]
          .filter(Boolean)
          .map((error) => error instanceof Error ? error.message : String(error))
          .join('; ')
        const originalMessage = primaryError instanceof Error ? primaryError.message : String(primaryError ?? '없음')
        throw new Error(`UAT 예약 자동 정리에 실패했습니다. 관리자 보드에서 run ${runId.slice(0, 8)}을 정리하세요. 원래 오류: ${originalMessage}; 정리 오류: ${cleanupMessages || '예약을 찾지 못했습니다.'}`)
      }
    }
  }
  if (primaryError) throw primaryError

  return {
    baseUrl: origin.origin,
    runId,
    branchId: selected.branch.id,
    date: selected.date,
    checks,
    warnings,
    requestIds,
    cleanup: 'cancelled',
  }
}

export function parseOptions(args, environment = process.env) {
  const options = {
    baseUrl: environment.TABLEFLOW_BASE_URL,
    adminToken: environment.TABLEFLOW_UAT_ADMIN_TOKEN,
    nonAdminToken: environment.TABLEFLOW_UAT_NON_ADMIN_TOKEN,
    guestName: environment.TABLEFLOW_UAT_GUEST_NAME,
    guestPhone: environment.TABLEFLOW_UAT_GUEST_PHONE,
    branchId: environment.TABLEFLOW_UAT_BRANCH_ID,
    date: environment.TABLEFLOW_UAT_DATE,
    partySize: 1,
    searchDays: 14,
    timeoutMs: 10_000,
    allowHttp: false,
    confirmWrite: false,
  }
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--base-url') options.baseUrl = requiredValue(args, ++index, argument)
    else if (argument === '--branch-id') options.branchId = requiredValue(args, ++index, argument)
    else if (argument === '--date') options.date = requiredValue(args, ++index, argument)
    else if (argument === '--party-size') options.partySize = Number(requiredValue(args, ++index, argument))
    else if (argument === '--search-days') options.searchDays = Number(requiredValue(args, ++index, argument))
    else if (argument === '--timeout-ms') options.timeoutMs = Number(requiredValue(args, ++index, argument))
    else if (argument === '--allow-http') options.allowHttp = true
    else if (argument === '--confirm-write') options.confirmWrite = true
    else if (argument === '--help' || argument === '-h') options.help = true
    else throw new Error(`알 수 없는 옵션입니다: ${argument}`)
  }
  return options
}

async function findSafeSlot({
  publicBranches,
  adminBranches,
  requestedDate,
  partySize,
  searchDays,
  now,
  request,
}) {
  for (const branch of publicBranches) {
    if (partySize > branch.maxPartySize) continue
    const policy = adminBranches.find((candidate) => candidate?.id === branch.id)
    if (!policy) continue
    const cutoffMinutes = Number(policy.changeCutoffMinutes)
    if (!Number.isInteger(cutoffMinutes) || cutoffMinutes < 0) {
      throw new Error('관리자 지점 응답의 changeCutoffMinutes가 올바르지 않습니다.')
    }

    const localToday = localDate(now, branch.timezone)
    const dates = requestedDate
      ? [requestedDate]
      : Array.from(
          { length: Math.min(searchDays, branch.bookingHorizonDays) },
          (_, index) => addDays(localToday, index + 1),
        )

    for (const candidateDate of dates) {
      const availability = await request(
        `/api/public/branches/${encodeURIComponent(branch.id)}/availability?date=${encodeURIComponent(candidateDate)}&partySize=${partySize}`,
      )
      if (!Array.isArray(availability?.slots)) throw new Error('가용성 응답의 slots가 배열이 아닙니다.')
      const safeAfter = now.getTime() + (cutoffMinutes * 60_000) + 60_000
      const slot = availability.slots.find((candidate) => {
        const startsAt = Date.parse(candidate?.startsAt)
        return Number.isFinite(startsAt) && startsAt > safeAfter
      })
      if (slot) return { branch, policy, date: candidateDate, slot }
    }
  }
  throw new Error('검색 범위에 생성 후 고객 취소가 가능한 빈 슬롯이 없습니다. 지점·날짜·검색 일수를 조정하세요.')
}

async function cancelCreatedReservation({ created, managed, request }) {
  const cancellation = await request(
    `/api/public/reservations/${encodeURIComponent(created.reservation.reservationCode)}/cancellation`,
    {
      method: 'POST',
      headers: { 'X-Reservation-Token': created.manageToken },
      json: {
        reason: 'TableFlow staging UAT automatic cleanup',
        expectedVersion: managed.version,
      },
    },
  )
  if (cancellation?.status !== 'CANCELLED') throw new Error('취소 응답 상태가 CANCELLED가 아닙니다.')
  return cancellation
}

async function adminCleanupReservation({ adminToken, branchId, date, guestName, startsAt, request }) {
  for (let page = 0; page < 20; page += 1) {
    const reservations = await request(
      `/api/admin/branches/${encodeURIComponent(branchId)}/reservations?date=${encodeURIComponent(date)}&status=PENDING&page=${page}&size=100`,
      { token: adminToken },
    )
    if (!Array.isArray(reservations?.items)) throw new Error('관리자 예약 목록의 items가 배열이 아닙니다.')
    const match = reservations.items.find((reservation) => (
      reservation?.guestName === guestName && reservation?.startsAt === startsAt
    ))
    if (match) {
      const cancelled = await request(
        `/api/admin/reservations/${encodeURIComponent(match.id)}/transitions`,
        {
          method: 'POST',
          token: adminToken,
          json: {
            targetStatus: 'CANCELLED',
            expectedVersion: match.version,
            reason: 'TableFlow staging UAT fallback cleanup',
          },
        },
      )
      if (cancelled?.status !== 'CANCELLED') throw new Error('관리자 정리 전이 결과가 CANCELLED가 아닙니다.')
      return true
    }
    if (reservations.last === true || page + 1 >= Number(reservations.totalPages ?? 0)) break
  }
  return false
}

function validateInputs({
  adminToken,
  nonAdminToken,
  guestName,
  guestPhone,
  branchId,
  date,
  partySize,
  searchDays,
  timeoutMs,
  confirmWrite,
  fetchImpl,
}) {
  if (!confirmWrite) throw new Error('예약 데이터 쓰기를 승인하려면 --confirm-write가 필요합니다.')
  if (typeof fetchImpl !== 'function') throw new Error('Fetch API를 사용할 수 있는 Node.js 24 이상이 필요합니다.')
  if (!adminToken) throw new Error('TABLEFLOW_UAT_ADMIN_TOKEN이 필요합니다.')
  if (/^Bearer\s/iu.test(adminToken) || /\s/u.test(adminToken)) {
    throw new Error('TABLEFLOW_UAT_ADMIN_TOKEN에는 Bearer 접두사 없이 token 값만 넣으세요.')
  }
  if (nonAdminToken && (/^Bearer\s/iu.test(nonAdminToken) || /\s/u.test(nonAdminToken))) {
    throw new Error('TABLEFLOW_UAT_NON_ADMIN_TOKEN에는 Bearer 접두사 없이 token 값만 넣으세요.')
  }
  if (!guestName?.trim() || guestName.length > 90) {
    throw new Error('TABLEFLOW_UAT_GUEST_NAME에는 승인된 1~90자 합성 이름이 필요합니다.')
  }
  if (!guestPhone || guestPhone.length < 8 || guestPhone.length > 20) {
    throw new Error('TABLEFLOW_UAT_GUEST_PHONE에는 승인된 8~20자 테스트 전화번호가 필요합니다.')
  }
  if (branchId && !isUuid(branchId)) throw new Error('--branch-id는 UUID여야 합니다.')
  if (date && !/^\d{4}-\d{2}-\d{2}$/u.test(date)) throw new Error('--date는 YYYY-MM-DD여야 합니다.')
  if (!Number.isInteger(partySize) || partySize < 1 || partySize > 100) {
    throw new Error('--party-size는 1~100 사이의 정수여야 합니다.')
  }
  if (!Number.isInteger(searchDays) || searchDays < 1 || searchDays > 31) {
    throw new Error('--search-days는 1~31 사이의 정수여야 합니다.')
  }
  if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 120_000) {
    throw new Error('--timeout-ms는 100~120000 사이의 정수여야 합니다.')
  }
}

function validateBaseUrl(value, allowHttp) {
  if (!value) throw new Error('--base-url 또는 TABLEFLOW_BASE_URL이 필요합니다.')
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error('base URL이 올바른 URL이 아닙니다.')
  }
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('base URL은 http 또는 https여야 합니다.')
  if (url.username || url.password || url.search || url.hash
      || (url.pathname !== '/' && url.pathname !== '')) {
    throw new Error('base URL에는 자격정보, 경로, query, fragment를 넣지 마세요.')
  }
  const isLocal = ['localhost', '127.0.0.1', '::1'].includes(url.hostname)
  if (url.protocol !== 'https:' && !isLocal && !allowHttp) {
    throw new Error('원격 UAT에는 HTTPS가 필요합니다. 의도한 HTTP 환경만 --allow-http를 사용하세요.')
  }
  url.pathname = '/'
  return url
}

function assertBookingContext(context) {
  if (typeof context?.privacyPolicyVersion !== 'string' || context.privacyPolicyVersion === '') {
    throw new Error('booking context의 privacyPolicyVersion이 비어 있습니다.')
  }
  if (!Array.isArray(context?.branches)) throw new Error('booking context의 branches가 배열이 아닙니다.')
  for (const branch of context.branches) {
    if (!isUuid(branch?.id)
        || typeof branch?.timezone !== 'string'
        || !Number.isInteger(branch?.bookingHorizonDays)
        || !Number.isInteger(branch?.maxPartySize)) {
      throw new Error('booking context의 지점 계약이 올바르지 않습니다.')
    }
  }
}

function assertCreatedReservation(created, expectedBranchId) {
  if (typeof created?.manageToken !== 'string' || created.manageToken.length < 43) {
    throw new Error('예약 생성 응답의 관리 token 계약이 올바르지 않습니다.')
  }
  const reservation = created?.reservation
  if (typeof reservation?.reservationCode !== 'string'
      || reservation.branchId !== expectedBranchId
      || reservation.status !== 'PENDING'
      || !Number.isInteger(reservation.version)) {
    throw new Error('예약 생성 응답 계약이 올바르지 않습니다.')
  }
}

function assertManagedReservation(reservation, expectedCode) {
  if (reservation?.reservationCode !== expectedCode
      || reservation.status !== 'PENDING'
      || !Number.isInteger(reservation.version)) {
    throw new Error('고객 관리 조회 응답 계약이 올바르지 않습니다.')
  }
}

function localDate(date, timezone) {
  let parts
  try {
    parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(date)
  } catch {
    throw new Error('지점 timezone이 지원되는 IANA 시간대가 아닙니다.')
  }
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${value.year}-${value.month}-${value.day}`
}

function addDays(date, days) {
  const [year, month, day] = date.split('-').map(Number)
  const next = new Date(Date.UTC(year, month - 1, day + days))
  return next.toISOString().slice(0, 10)
}

function isUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(value)
}

function safePath(path) {
  return path
    .split('?')[0]
    .replace(/\/[A-HJ-NP-Z2-9]{10}(?=\/|$)/gu, '/:reservationCode')
}

function requiredValue(args, index, option) {
  const value = args[index]
  if (!value || value.startsWith('--')) throw new Error(`${option} 값이 필요합니다.`)
  return value
}

function printHelp() {
  console.log(`TableFlow 스테이징 쓰기 UAT

사용법:
  node scripts/staging-uat.mjs --base-url https://staging.example.com --confirm-write

필수 환경 변수:
  TABLEFLOW_UAT_ADMIN_TOKEN   관리자 access token, Bearer 접두사 없이 입력
  TABLEFLOW_UAT_GUEST_NAME    승인된 합성 테스트 이름
  TABLEFLOW_UAT_GUEST_PHONE   승인된 테스트 전용 전화번호

선택 환경 변수:
  TABLEFLOW_UAT_NON_ADMIN_TOKEN  비관리자 역할 403 검사용 token
  TABLEFLOW_UAT_BRANCH_ID        특정 공개 지점 UUID
  TABLEFLOW_UAT_DATE             특정 지점 현지 날짜 YYYY-MM-DD
  TABLEFLOW_BASE_URL             --base-url 대체

옵션:
  --branch-id <uuid>  검사할 공개 지점
  --date <date>       검사할 지점 현지 날짜
  --party-size <n>    예약 인원, 기본 1
  --search-days <n>   빈 슬롯 검색 일수, 기본 14, 최대 31
  --timeout-ms <ms>   요청별 제한 시간, 기본 10000
  --allow-http        localhost 외 HTTP를 명시적으로 허용
  --confirm-write     테스트 예약 생성과 자동 취소를 명시적으로 승인
  -h, --help          도움말

이 명령은 실제 예약을 생성합니다. 고객 관리 token은 출력하지 않으며 종료 전에 취소를 시도합니다.`)
}

async function main() {
  try {
    const options = parseOptions(process.argv.slice(2))
    if (options.help) return printHelp()
    const result = await runStagingUat(options)
    for (const check of result.checks) console.log(`PASS  ${check}`)
    for (const warning of result.warnings) console.warn(`WARN  ${warning}`)
    console.log(`PASS  run ${result.runId.slice(0, 8)}, ${result.date}, 자동 정리 ${result.cleanup}`)
    console.log(`INFO  request IDs: ${result.requestIds.join(', ')}`)
  } catch (error) {
    console.error(`FAIL  ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}

const entryPoint = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (import.meta.url === entryPoint) await main()
