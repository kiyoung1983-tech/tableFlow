import { useMutation, useQuery } from '@tanstack/react-query'
import { type FormEvent, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { getBookingContext, getBranchAvailability } from '../api/availabilityApi'
import { ApiError } from '../api/client'
import { createReservation } from '../api/reservationApi'
import type {
  CreateReservationRequest,
  PublicBranch,
  ReservationCreatedResponse,
} from '../api/types'

type CreationIntent = {
  fingerprint: string
  idempotencyKey: string
}

export function ReservationBookingPage() {
  const [branchId, setBranchId] = useState('')
  const [date, setDate] = useState('')
  const [partySize, setPartySize] = useState('2')
  const [selectedStart, setSelectedStart] = useState('')
  const [guestName, setGuestName] = useState('')
  const [guestPhone, setGuestPhone] = useState('')
  const [privacyAgreed, setPrivacyAgreed] = useState(false)
  const [copyStatus, setCopyStatus] = useState('')
  const creationIntent = useRef<CreationIntent | undefined>(undefined)

  const contextQuery = useQuery({
    queryKey: ['booking-context'],
    queryFn: getBookingContext,
    retry: false,
    staleTime: 5 * 60_000,
  })
  const selectedBranch = contextQuery.data?.branches.find((branch) => branch.id === branchId)
  const numericPartySize = Number(partySize)
  const canLoadAvailability = selectedBranch !== undefined
    && date !== ''
    && Number.isInteger(numericPartySize)
    && numericPartySize >= 1
    && numericPartySize <= selectedBranch.maxPartySize

  const availabilityQuery = useQuery({
    queryKey: ['availability', branchId, date, numericPartySize],
    queryFn: () => getBranchAvailability(branchId, date, numericPartySize),
    enabled: canLoadAvailability,
    retry: false,
  })

  const createMutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: {
      request: CreateReservationRequest
      idempotencyKey: string
    }) => createReservation(request, idempotencyKey),
    onSuccess: () => {
      setGuestName('')
      setGuestPhone('')
      setPrivacyAgreed(false)
      setCopyStatus('')
    },
    onError: (error) => {
      if (error instanceof ApiError
          && ['NO_TABLE_AVAILABLE', 'RESERVATION_CONFLICT'].includes(error.problem.code)) {
        setSelectedStart('')
        void availabilityQuery.refetch()
      }
      if (error instanceof ApiError
          && error.problem.code === 'PRIVACY_POLICY_VERSION_MISMATCH') {
        setPrivacyAgreed(false)
        void contextQuery.refetch()
      }
    },
  })

  function selectBranch(nextBranchId: string) {
    const branch = contextQuery.data?.branches.find((item) => item.id === nextBranchId)
    setBranchId(nextBranchId)
    setDate(branch ? dateInTimezone(new Date(), branch.timezone) : '')
    setPartySize(branch ? String(Math.min(2, branch.maxPartySize)) : '2')
    setSelectedStart('')
    createMutation.reset()
    creationIntent.current = undefined
  }

  function submitReservation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedBranch || !selectedStart || !contextQuery.data || !privacyAgreed) return
    const request: CreateReservationRequest = {
      branchId: selectedBranch.id,
      startsAt: selectedStart,
      partySize: numericPartySize,
      guestName: guestName.trim(),
      guestPhone: guestPhone.trim(),
      privacyAgreement: {
        agreed: true,
        policyVersion: contextQuery.data.privacyPolicyVersion,
      },
    }
    const fingerprint = JSON.stringify(request)
    if (creationIntent.current?.fingerprint !== fingerprint) {
      creationIntent.current = {
        fingerprint,
        idempotencyKey: globalThis.crypto.randomUUID(),
      }
    }
    createMutation.mutate({
      request,
      idempotencyKey: creationIntent.current.idempotencyKey,
    })
  }

  function resetBooking() {
    setSelectedStart('')
    setCopyStatus('')
    creationIntent.current = undefined
    createMutation.reset()
    void availabilityQuery.refetch()
  }

  const created = createMutation.data

  return (
    <main className="page booking-page">
      <p className="eyebrow">BOOK A TABLE</p>
      <h1>새 예약</h1>
      <p className="lead">
        지점과 시간을 고르면 예약 가능한 테이블을 다시 확인하고 예약 요청을 접수합니다.
      </p>

      {contextQuery.isPending && <p className="status">예약 지점을 불러오는 중…</p>}
      {contextQuery.isError && (
        <p className="status error" role="alert">{errorMessage(contextQuery.error)}</p>
      )}

      {created ? (
        <ReservationCredentialPanel
          created={created}
          copyStatus={copyStatus}
          onCopyStatus={setCopyStatus}
          onReset={resetBooking}
        />
      ) : contextQuery.data && (
        <>
          {contextQuery.data.branches.length === 0 ? (
            <p className="empty-state">현재 온라인 예약을 받는 지점이 없습니다.</p>
          ) : (
            <>
              <section className="booking-selector" aria-label="예약 조건 선택">
                <label>
                  지점
                  <select onChange={(event) => selectBranch(event.target.value)} value={branchId}>
                    <option value="">지점 선택</option>
                    {contextQuery.data.branches.map((branch) => (
                      <option key={branch.id} value={branch.id}>
                        {branch.restaurantName} · {branch.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  예약 날짜
                  <input
                    disabled={!selectedBranch}
                    max={selectedBranch && date
                      ? addDays(dateInTimezone(new Date(), selectedBranch.timezone), selectedBranch.bookingHorizonDays)
                      : undefined}
                    min={selectedBranch ? dateInTimezone(new Date(), selectedBranch.timezone) : undefined}
                    onChange={(event) => {
                      setDate(event.target.value)
                      setSelectedStart('')
                      createMutation.reset()
                    }}
                    required
                    type="date"
                    value={date}
                  />
                </label>
                <label>
                  인원
                  <input
                    disabled={!selectedBranch}
                    max={selectedBranch?.maxPartySize}
                    min="1"
                    onChange={(event) => {
                      setPartySize(event.target.value)
                      setSelectedStart('')
                      createMutation.reset()
                    }}
                    required
                    type="number"
                    value={partySize}
                  />
                </label>
              </section>

              {selectedBranch && (
                <p className="branch-note">
                  <strong>{selectedBranch.name}</strong> · {selectedBranch.address} · 최대 {selectedBranch.maxPartySize}명
                </p>
              )}

              <AvailabilitySlots
                branch={selectedBranch}
                error={availabilityQuery.error}
                isFetching={availabilityQuery.isFetching}
                onSelect={setSelectedStart}
                selectedStart={selectedStart}
                slots={availabilityQuery.data?.slots}
              />

              {selectedStart && selectedBranch && (
                <form className="booking-form" onSubmit={submitReservation}>
                  <div className="booking-form-heading">
                    <p className="action-kicker">GUEST</p>
                    <h2>예약자 정보</h2>
                    <p>{formatDateTime(selectedStart, selectedBranch.timezone)} · {numericPartySize}명</p>
                  </div>
                  <div className="booking-fields">
                    <label>
                      예약자 이름
                      <input
                        autoComplete="name"
                        maxLength={100}
                        onChange={(event) => setGuestName(event.target.value)}
                        required
                        value={guestName}
                      />
                    </label>
                    <label>
                      휴대전화 번호
                      <input
                        autoComplete="tel"
                        inputMode="tel"
                        maxLength={20}
                        minLength={8}
                        onChange={(event) => setGuestPhone(event.target.value)}
                        placeholder="010-1234-5678"
                        required
                        type="tel"
                        value={guestPhone}
                      />
                    </label>
                  </div>
                  <label className="privacy-check">
                    <input
                      checked={privacyAgreed}
                      onChange={(event) => setPrivacyAgreed(event.target.checked)}
                      required
                      type="checkbox"
                    />
                    <span>
                      예약 처리를 위한 개인정보 수집·이용에 동의합니다.{' '}
                      <Link target="_blank" to="/privacy">내용 보기</Link>
                      {' '}({contextQuery.data.privacyPolicyVersion})
                    </span>
                  </label>
                  <button disabled={createMutation.isPending} type="submit">
                    {createMutation.isPending ? '예약 접수 중…' : '예약 요청하기'}
                  </button>
                  {createMutation.isError && (
                    <p className="inline-error" role="alert">{errorMessage(createMutation.error)}</p>
                  )}
                </form>
              )}
            </>
          )}
        </>
      )}
    </main>
  )
}

function AvailabilitySlots({
  branch,
  slots,
  selectedStart,
  isFetching,
  error,
  onSelect,
}: {
  branch?: PublicBranch
  slots?: Array<{ startsAt: string; endsAt: string; availableTableCount: number }>
  selectedStart: string
  isFetching: boolean
  error: unknown
  onSelect: (startsAt: string) => void
}) {
  if (!branch) return <p className="booking-prompt">먼저 예약할 지점을 선택해 주세요.</p>
  if (isFetching) return <p className="status">예약 가능 시간을 확인하는 중…</p>
  if (error) return <p className="status error" role="alert">{errorMessage(error)}</p>
  if (!slots) return null
  if (slots.length === 0) {
    return <p className="empty-state">선택한 조건에 예약 가능한 시간이 없습니다.</p>
  }
  return (
    <section className="slot-section" aria-label="예약 가능 시간">
      <div className="section-heading">
        <div><p className="action-kicker">AVAILABLE</p><h2>시간 선택</h2></div>
        <p>실시간 조회 결과</p>
      </div>
      <div className="slot-grid">
        {slots.map((slot) => (
          <button
            aria-pressed={selectedStart === slot.startsAt}
            className={`slot-button${selectedStart === slot.startsAt ? ' selected' : ''}`}
            key={slot.startsAt}
            onClick={() => onSelect(slot.startsAt)}
            type="button"
          >
            <strong>{formatTimeRange(slot.startsAt, slot.endsAt, branch.timezone)}</strong>
            <span>{slot.availableTableCount}개 테이블 가능</span>
          </button>
        ))}
      </div>
    </section>
  )
}

function ReservationCredentialPanel({
  created,
  copyStatus,
  onCopyStatus,
  onReset,
}: {
  created: ReservationCreatedResponse
  copyStatus: string
  onCopyStatus: (status: string) => void
  onReset: () => void
}) {
  async function copyCredentials() {
    try {
      await navigator.clipboard.writeText(
        `예약 번호: ${created.reservation.reservationCode}\n관리 토큰: ${created.manageToken}`,
      )
      onCopyStatus('예약 번호와 관리 토큰을 복사했습니다.')
    } catch {
      onCopyStatus('복사하지 못했습니다. 아래 정보를 직접 안전하게 보관해 주세요.')
    }
  }

  return (
    <section className="booking-complete" aria-label="예약 완료">
      <p className="eyebrow">REQUEST RECEIVED</p>
      <h2>예약 요청을 접수했습니다.</h2>
      <p className="lead">
        관리 토큰은 지금 한 번만 표시됩니다. 잃어버리면 다시 조회할 수 없으므로
        예약 번호와 함께 안전하게 보관해 주세요.
      </p>
      <dl className="created-summary">
        <div><dt>지점</dt><dd>{created.reservation.branchName}</dd></div>
        <div><dt>예약 시각</dt><dd>{formatDateTime(created.reservation.startsAt, created.reservation.timezone)}</dd></div>
        <div><dt>인원</dt><dd>{created.reservation.partySize}명</dd></div>
        <div><dt>상태</dt><dd>예약 대기</dd></div>
      </dl>
      <div className="credential-vault">
        <div>
          <span>예약 번호</span>
          <code>{created.reservation.reservationCode}</code>
        </div>
        <div>
          <span>관리 토큰</span>
          <code>{created.manageToken}</code>
        </div>
      </div>
      <div className="completion-actions">
        <button onClick={() => void copyCredentials()} type="button">전체 정보 복사</button>
        <Link className="secondary-link" to="/reservations/manage">예약 확인 화면 열기</Link>
        <button className="text-button" onClick={onReset} type="button">다른 예약 만들기</button>
      </div>
      {copyStatus && <p className="inline-success" role="status">{copyStatus}</p>}
    </section>
  )
}

function dateInTimezone(date: Date, timezone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    day: '2-digit',
    month: '2-digit',
    timeZone: timezone,
    year: 'numeric',
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

function addDays(date: string, days: number) {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

function formatTimeRange(startsAt: string, endsAt: string, timezone: string) {
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: timezone,
  })
  return `${formatter.format(new Date(startsAt))}–${formatter.format(new Date(endsAt))}`
}

function formatDateTime(value: string, timezone: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'long',
    timeStyle: 'short',
    timeZone: timezone,
  }).format(new Date(value))
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return error.problem.detail
  if (error instanceof Error) return error.message
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
