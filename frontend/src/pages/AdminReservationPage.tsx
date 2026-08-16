import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  getAdminReservationDashboard,
  listAdminBranches,
  listAdminReservations,
  listAdminRestaurants,
  transitionAdminReservation,
} from '../api/adminReservationApi'
import { ApiError } from '../api/client'
import {
  AdminLoginPanel,
  AdminSessionHeader,
} from '../auth/AdminAccess'
import { useAdminAuth } from '../auth/adminAuthContext'
import { useAdminSession } from '../auth/adminSession'
import type {
  AdminReservation,
  ReservationStatus,
  TransitionReservationRequest,
} from '../api/types'

type TransitionTarget = TransitionReservationRequest['targetStatus']

const STATUS_LABELS: Record<ReservationStatus, string> = {
  PENDING: '대기',
  CONFIRMED: '확정',
  SEATED: '착석',
  COMPLETED: '완료',
  CANCELLED: '취소',
  NO_SHOW: '노쇼',
}

const TRANSITIONS: Partial<Record<ReservationStatus, TransitionTarget[]>> = {
  PENDING: ['CONFIRMED', 'CANCELLED'],
  CONFIRMED: ['SEATED', 'NO_SHOW', 'CANCELLED'],
  SEATED: ['COMPLETED'],
}

const ACTION_LABELS: Record<TransitionTarget, string> = {
  CONFIRMED: '예약 확정',
  SEATED: '착석 처리',
  COMPLETED: '이용 완료',
  CANCELLED: '예약 취소',
  NO_SHOW: '노쇼 처리',
}

export function AdminReservationPage() {
  const queryClient = useQueryClient()
  const adminAuth = useAdminAuth()
  const session = useAdminSession()
  const [restaurantId, setRestaurantId] = useState('')
  const [branchId, setBranchId] = useState('')
  const [date, setDate] = useState(todayInputValue())
  const [status, setStatus] = useState<ReservationStatus | ''>('')
  const [page, setPage] = useState(0)
  const [reasonByReservation, setReasonByReservation] = useState<Record<string, string>>({})
  const restaurantsQuery = useQuery({
    queryKey: ['admin', 'restaurants', session?.id],
    queryFn: () => listAdminRestaurants(session!.authorization),
    enabled: session !== undefined,
    retry: false,
  })
  const branchesQuery = useQuery({
    queryKey: ['admin', 'branches', session?.id, restaurantId],
    queryFn: () => listAdminBranches(restaurantId, session!.authorization),
    enabled: session !== undefined && restaurantId !== '',
    retry: false,
  })
  const reservationsKey = [
    'admin', 'reservations', session?.id, branchId, date, status, page,
  ] as const
  const reservationsQuery = useQuery({
    queryKey: reservationsKey,
    queryFn: () => listAdminReservations(
      branchId,
      date,
      status || undefined,
      page,
      20,
      session!.authorization,
    ),
    enabled: session !== undefined && branchId !== '',
    retry: false,
  })
  const dashboardQuery = useQuery({
    queryKey: ['admin', 'dashboard', session?.id, branchId, date],
    queryFn: () => getAdminReservationDashboard(branchId, date, session!.authorization),
    enabled: session !== undefined && branchId !== '',
    retry: false,
  })

  const transitionMutation = useMutation({
    mutationFn: ({ reservation, target }: {
      reservation: AdminReservation
      target: TransitionTarget
    }) => transitionAdminReservation(
      reservation.id,
      {
        targetStatus: target,
        expectedVersion: reservation.version,
        ...(reasonByReservation[reservation.id]?.trim()
          ? { reason: reasonByReservation[reservation.id].trim() }
          : {}),
      },
      session!.authorization,
    ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin', 'reservations'] }),
        queryClient.invalidateQueries({ queryKey: ['admin', 'dashboard'] }),
      ])
    },
  })

  return (
    <main className="page admin-page">
      <p className="eyebrow">TODAY&apos;S FLOOR</p>
      <h1>예약 운영 보드</h1>
      <p className="lead">
        날짜별 예약과 좌석 흐름을 확인하고 확정·착석·완료·취소·노쇼 상태를 처리합니다.
        {adminAuth.mode === 'basic'
          ? ' 로컬 Basic 인증 정보는 현재 화면 메모리에만 보관됩니다.'
          : ' 운영 OIDC 계정의 관리자 역할을 확인한 뒤 연결합니다.'}
      </p>

      {!session ? (
        <AdminLoginPanel basicButtonLabel="운영 보드 연결" />
      ) : (
        <>
          <AdminSessionHeader session={session} />

          {restaurantsQuery.isError && (
            <p className="status error" role="alert">{errorMessage(restaurantsQuery.error)}</p>
          )}
          {branchesQuery.isError && (
            <p className="status error" role="alert">{errorMessage(branchesQuery.error)}</p>
          )}

          {restaurantsQuery.data && (
            <section className="admin-filters" aria-label="운영 범위 선택">
              <label>
                식당
                <select
                  onChange={(event) => {
                    setRestaurantId(event.target.value)
                    setBranchId('')
                    setPage(0)
                  }}
                  value={restaurantId}
                >
                  <option value="">식당 선택</option>
                  {restaurantsQuery.data.map((restaurant) => (
                    <option key={restaurant.id} value={restaurant.id}>{restaurant.name}</option>
                  ))}
                </select>
              </label>
              <label>
                지점
                <select
                  disabled={!restaurantId || branchesQuery.isFetching}
                  onChange={(event) => {
                    const nextBranchId = event.target.value
                    setBranchId(nextBranchId)
                    const selectedBranch = branchesQuery.data?.find(
                      (branch) => branch.id === nextBranchId,
                    )
                    if (selectedBranch) setDate(todayInputValue(selectedBranch.timezone))
                    setPage(0)
                  }}
                  value={branchId}
                >
                  <option value="">지점 선택</option>
                  {branchesQuery.data?.map((branch) => (
                    <option key={branch.id} value={branch.id}>{branch.name}</option>
                  ))}
                </select>
              </label>
              <label>
                운영 날짜
                <input
                  onChange={(event) => {
                    setDate(event.target.value)
                    setPage(0)
                  }}
                  type="date"
                  value={date}
                />
              </label>
              <label>
                상태
                <select
                  onChange={(event) => {
                    setStatus(event.target.value as ReservationStatus | '')
                    setPage(0)
                  }}
                  value={status}
                >
                  <option value="">전체 상태</option>
                  {(Object.keys(STATUS_LABELS) as ReservationStatus[]).map((item) => (
                    <option key={item} value={item}>{STATUS_LABELS[item]}</option>
                  ))}
                </select>
              </label>
            </section>
          )}

          {branchId && (
            <>
              {dashboardQuery.isError && (
                <p className="status error" role="alert">{errorMessage(dashboardQuery.error)}</p>
              )}
              {dashboardQuery.data && <Dashboard dashboard={dashboardQuery.data} />}

              <section className="reservation-list-section">
                <div className="section-heading">
                  <div>
                    <p className="action-kicker">RESERVATIONS</p>
                    <h2>예약 목록</h2>
                  </div>
                  <button
                    className="secondary-button"
                    onClick={() => {
                      reservationsQuery.refetch()
                      dashboardQuery.refetch()
                    }}
                    type="button"
                  >
                    새로고침
                  </button>
                </div>

                {reservationsQuery.isFetching && <p className="status">예약을 불러오는 중…</p>}
                {reservationsQuery.isError && (
                  <p className="status error" role="alert">{errorMessage(reservationsQuery.error)}</p>
                )}
                {reservationsQuery.data?.items.length === 0 && (
                  <p className="empty-state">선택한 조건의 예약이 없습니다.</p>
                )}
                <div className="admin-reservation-list">
                  {reservationsQuery.data?.items.map((reservation) => (
                    <ReservationCard
                      key={reservation.id}
                      busy={transitionMutation.isPending}
                      error={transitionMutation.isError
                        && transitionMutation.variables.reservation.id === reservation.id
                        ? errorMessage(transitionMutation.error)
                        : undefined}
                      onReasonChange={(reason) => setReasonByReservation((current) => ({
                        ...current,
                        [reservation.id]: reason,
                      }))}
                      onTransition={(target) => transitionMutation.mutate({ reservation, target })}
                      reason={reasonByReservation[reservation.id] ?? ''}
                      reservation={reservation}
                    />
                  ))}
                </div>

                {reservationsQuery.data && reservationsQuery.data.totalPages > 1 && (
                  <div className="pagination" aria-label="예약 목록 페이지">
                    <button
                      className="secondary-button"
                      disabled={reservationsQuery.data.first}
                      onClick={() => setPage((current) => current - 1)}
                      type="button"
                    >이전</button>
                    <span>{reservationsQuery.data.page + 1} / {reservationsQuery.data.totalPages}</span>
                    <button
                      className="secondary-button"
                      disabled={reservationsQuery.data.last}
                      onClick={() => setPage((current) => current + 1)}
                      type="button"
                    >다음</button>
                  </div>
                )}
              </section>
            </>
          )}
        </>
      )}
    </main>
  )
}

function Dashboard({ dashboard }: {
  dashboard: Awaited<ReturnType<typeof getAdminReservationDashboard>>
}) {
  const counts: Array<[string, number]> = [
    ['대기', dashboard.statusCounts.pending],
    ['확정', dashboard.statusCounts.confirmed],
    ['착석', dashboard.statusCounts.seated],
    ['완료', dashboard.statusCounts.completed],
    ['취소', dashboard.statusCounts.cancelled],
    ['노쇼', dashboard.statusCounts.noShow],
  ]
  return (
    <section className="dashboard-panel" aria-label="예약 현황">
      <div className="metric-grid">
        {counts.map(([label, count]) => (
          <div className="metric-card" key={label}><span>{label}</span><strong>{count}</strong></div>
        ))}
      </div>
      <div className="capacity-panel">
        <div className="section-heading">
          <div><p className="action-kicker">CAPACITY</p><h2>테이블 흐름</h2></div>
          <p>운영 테이블 {dashboard.totalEnabledTables}개</p>
        </div>
        <div className="capacity-timeline">
          {dashboard.capacityTimeline.map((bucket) => (
            <div className="capacity-row" key={bucket.startsAt}>
              <time>{formatTime(bucket.startsAt, dashboard.timezone)}</time>
              <div className="capacity-bar" aria-label={`사용 가능 ${bucket.availableTableCount}개`}>
                <span
                  className="available-bar"
                  style={{ width: percentage(bucket.availableTableCount, dashboard.totalEnabledTables) }}
                />
              </div>
              <span>가능 {bucket.availableTableCount} · 점유 {bucket.occupiedTableCount} · 차단 {bucket.blockedTableCount}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function ReservationCard({
  reservation,
  reason,
  busy,
  error,
  onReasonChange,
  onTransition,
}: {
  reservation: AdminReservation
  reason: string
  busy: boolean
  error?: string
  onReasonChange: (reason: string) => void
  onTransition: (target: TransitionTarget) => void
}) {
  const [armedTarget, setArmedTarget] = useState<TransitionTarget>()
  const transitions = TRANSITIONS[reservation.status] ?? []
  return (
    <article className="admin-reservation-card">
      <header>
        <div>
          <p className="reservation-code">{reservation.reservationCode}</p>
          <h3>{reservation.guestName} <span>{reservation.partySize}명</span></h3>
        </div>
        <span className={`reservation-status status-${reservation.status.toLowerCase()}`}>
          {STATUS_LABELS[reservation.status]}
        </span>
      </header>
      <div className="reservation-facts">
        <span>{formatDateTime(reservation.startsAt, reservation.timezone)}</span>
        <span>{reservation.tableName}</span>
        <span>•••• {reservation.guestPhoneLastFour}</span>
      </div>
      {transitions.length > 0 && (
        <div className="transition-controls">
          {transitions.includes('CANCELLED') && (
            <label>
              처리 사유
              <input
                maxLength={500}
                onChange={(event) => onReasonChange(event.target.value)}
                placeholder="마감 후 취소에는 필수"
                value={reason}
              />
            </label>
          )}
          <div className="transition-buttons">
            {transitions.map((target) => (
              <button
                className={target === 'CANCELLED' || target === 'NO_SHOW'
                  ? 'danger-outline-button'
                  : undefined}
                disabled={busy}
                key={target}
                onClick={() => {
                  const requiresConfirmation = target === 'CANCELLED' || target === 'NO_SHOW'
                  if (requiresConfirmation && armedTarget !== target) {
                    setArmedTarget(target)
                    return
                  }
                  setArmedTarget(undefined)
                  onTransition(target)
                }}
                type="button"
              >
                {armedTarget === target ? `${ACTION_LABELS[target]} 확정` : ACTION_LABELS[target]}
              </button>
            ))}
          </div>
        </div>
      )}
      {error && <p className="inline-error" role="alert">{error}</p>}
      <details>
        <summary>상태 이력 {reservation.history.length}건</summary>
        <ol className="history-list">
          {reservation.history.map((item, index) => (
            <li key={`${item.changedAt}-${index}`}>
              <strong>{STATUS_LABELS[item.toStatus]}</strong>
              <span>{formatDateTime(item.changedAt, reservation.timezone)}</span>
              <span>{item.actorType}{item.actorId ? ` · ${item.actorId}` : ''}</span>
              {item.reason && <span>{item.reason}</span>}
            </li>
          ))}
        </ol>
      </details>
    </article>
  )
}

function todayInputValue(timezone?: string) {
  const now = new Date()
  if (timezone) {
    const parts = new Intl.DateTimeFormat('en-US', {
      day: '2-digit',
      month: '2-digit',
      timeZone: timezone,
      year: 'numeric',
    }).formatToParts(now)
    const value = Object.fromEntries(parts.map((part) => [part.type, part.value]))
    return `${value.year}-${value.month}-${value.day}`
  }
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}

function formatDateTime(value: string, timezone: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: timezone,
  }).format(new Date(value))
}

function formatTime(value: string, timezone: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: timezone,
  }).format(new Date(value))
}

function percentage(value: number, total: number) {
  return total === 0 ? '0%' : `${Math.round(value / total * 100)}%`
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return error.problem.detail
  if (error instanceof Error) return error.message
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
