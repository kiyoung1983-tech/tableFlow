import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { type FormEvent, useState } from 'react'
import { ApiError } from '../api/client'
import {
  cancelReservation,
  changeReservation,
  getReservation,
} from '../api/reservationApi'
import type { PublicReservation } from '../api/types'

type Credentials = { code: string; token: string }

const STATUS_LABELS: Record<PublicReservation['status'], string> = {
  PENDING: '예약 대기',
  CONFIRMED: '예약 확정',
  SEATED: '착석',
  COMPLETED: '이용 완료',
  CANCELLED: '예약 취소',
  NO_SHOW: '노쇼',
}

export function ReservationManagePage() {
  const queryClient = useQueryClient()
  const [codeInput, setCodeInput] = useState('')
  const [tokenInput, setTokenInput] = useState('')
  const [credentials, setCredentials] = useState<Credentials>()
  const [lookupAttempt, setLookupAttempt] = useState(0)
  const [startsAt, setStartsAt] = useState('')
  const [partySize, setPartySize] = useState('')
  const [changeError, setChangeError] = useState('')
  const [cancelReason, setCancelReason] = useState('')
  const [cancelArmed, setCancelArmed] = useState(false)
  const reservationQueryKey = ['reservation-management', lookupAttempt] as const

  const reservationQuery = useQuery({
    queryKey: reservationQueryKey,
    queryFn: () => getReservation(credentials!.code, credentials!.token),
    enabled: credentials !== undefined,
    retry: false,
  })

  const changeMutation = useMutation({
    mutationFn: (reservation: PublicReservation) => {
      const trimmedStart = startsAt.trim()
      if (!trimmedStart && !partySize) {
        throw new Error('변경할 시작 시각 또는 인원을 입력해 주세요.')
      }
      return changeReservation(credentials!.code, credentials!.token, {
        ...(trimmedStart ? { startsAt: trimmedStart } : {}),
        ...(partySize ? { partySize: Number(partySize) } : {}),
        expectedVersion: reservation.version,
      })
    },
    onSuccess: (reservation) => {
      queryClient.setQueryData(reservationQueryKey, reservation)
      setStartsAt('')
      setPartySize('')
      setChangeError('')
    },
    onError: (error) => setChangeError(errorMessage(error)),
  })

  const cancelMutation = useMutation({
    mutationFn: (reservation: PublicReservation) => cancelReservation(
      credentials!.code,
      credentials!.token,
      {
        ...(cancelReason.trim() ? { reason: cancelReason.trim() } : {}),
        expectedVersion: reservation.version,
      },
    ),
    onSuccess: (reservation) => {
      queryClient.setQueryData(reservationQueryKey, reservation)
      setCancelArmed(false)
    },
  })

  function submitLookup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setCredentials({
      code: codeInput.trim().toUpperCase(),
      token: tokenInput.trim(),
    })
    setLookupAttempt((attempt) => attempt + 1)
    setCancelArmed(false)
    setChangeError('')
    changeMutation.reset()
    cancelMutation.reset()
  }

  const reservation = reservationQuery.data
  const canSelfManage = reservation?.status === 'PENDING'
    || reservation?.status === 'CONFIRMED'

  return (
    <main className="page manage-page">
      <p className="eyebrow">MY RESERVATION</p>
      <h1>예약 확인·변경·취소</h1>
      <p className="lead">
        예약 완료 때 받은 예약 번호와 관리 토큰을 입력하세요. 관리 토큰은 이 화면의
        메모리에만 머물며 URL이나 브라우저 저장소에 보관하지 않습니다.
      </p>

      <form className="credential-card" onSubmit={submitLookup}>
        <label>
          예약 번호
          <input
            autoComplete="off"
            maxLength={10}
            onChange={(event) => setCodeInput(event.target.value)}
            placeholder="ABCD234567"
            required
            value={codeInput}
          />
        </label>
        <label>
          관리 토큰
          <input
            autoComplete="off"
            onChange={(event) => setTokenInput(event.target.value)}
            placeholder="예약 완료 시 발급된 토큰"
            required
            type="password"
            value={tokenInput}
          />
        </label>
        <button disabled={reservationQuery.isFetching} type="submit">
          {reservationQuery.isFetching ? '확인 중…' : '예약 확인'}
        </button>
      </form>

      {reservationQuery.isError && (
        <p className="status error" role="alert">{errorMessage(reservationQuery.error)}</p>
      )}

      {reservation && (
        <section className="reservation-panel" aria-label="예약 상세">
          <div className="reservation-summary">
            <div>
              <p className="eyebrow">{reservation.reservationCode}</p>
              <h2>{reservation.branchName}</h2>
              <p className={`reservation-status status-${reservation.status.toLowerCase()}`}>
                {STATUS_LABELS[reservation.status]}
              </p>
            </div>
            <dl>
              <div><dt>예약 시각</dt><dd>{formatDateTime(reservation)}</dd></div>
              <div><dt>인원</dt><dd>{reservation.partySize}명</dd></div>
              <div><dt>테이블</dt><dd>{reservation.tableName}</dd></div>
              <div><dt>지점 시간대</dt><dd>{reservation.timezone}</dd></div>
            </dl>
          </div>

          {canSelfManage ? (
            <div className="manage-actions">
              <form
                className="action-card"
                onSubmit={(event) => {
                  event.preventDefault()
                  changeMutation.mutate(reservation)
                }}
              >
                <p className="action-kicker">CHANGE</p>
                <h3>예약 변경</h3>
                <label>
                  새 시작 시각
                  <input
                    onChange={(event) => setStartsAt(event.target.value)}
                    placeholder="2026-08-18T13:00:00+09:00"
                    type="text"
                    value={startsAt}
                  />
                </label>
                <p className="field-hint">지점 시간대 오프셋을 포함한 ISO 8601 형식</p>
                <label>
                  새 인원
                  <input
                    min="1"
                    onChange={(event) => setPartySize(event.target.value)}
                    placeholder={`${reservation.partySize}`}
                    type="number"
                    value={partySize}
                  />
                </label>
                <button disabled={changeMutation.isPending} type="submit">
                  {changeMutation.isPending ? '변경 중…' : '변경 저장'}
                </button>
                {changeMutation.isSuccess && (
                  <p className="inline-success" role="status">예약을 변경했습니다.</p>
                )}
                {changeError && <p className="inline-error" role="alert">{changeError}</p>}
              </form>

              <div className="action-card danger-card">
                <p className="action-kicker">CANCEL</p>
                <h3>예약 취소</h3>
                <label>
                  취소 사유 <span>(선택)</span>
                  <textarea
                    maxLength={500}
                    onChange={(event) => setCancelReason(event.target.value)}
                    placeholder="식당 운영에 도움이 되도록 알려주세요."
                    value={cancelReason}
                  />
                </label>
                {!cancelArmed ? (
                  <button className="secondary-button" onClick={() => setCancelArmed(true)} type="button">
                    예약 취소 검토
                  </button>
                ) : (
                  <div className="confirmation-row" role="group" aria-label="예약 취소 확인">
                    <button
                      className="danger-button"
                      disabled={cancelMutation.isPending}
                      onClick={() => cancelMutation.mutate(reservation)}
                      type="button"
                    >
                      {cancelMutation.isPending ? '취소 중…' : '취소 확정'}
                    </button>
                    <button className="text-button" onClick={() => setCancelArmed(false)} type="button">
                      돌아가기
                    </button>
                  </div>
                )}
                {cancelMutation.isError && (
                  <p className="inline-error" role="alert">{errorMessage(cancelMutation.error)}</p>
                )}
              </div>
            </div>
          ) : (
            <p className="terminal-note">
              이 예약은 현재 상태에서 고객이 직접 변경하거나 취소할 수 없습니다.
            </p>
          )}
        </section>
      )}
    </main>
  )
}

function formatDateTime(reservation: PublicReservation) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'long',
    timeStyle: 'short',
    timeZone: reservation.timezone,
  }).format(new Date(reservation.startsAt))
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return error.problem.detail
  if (error instanceof Error) return error.message
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
