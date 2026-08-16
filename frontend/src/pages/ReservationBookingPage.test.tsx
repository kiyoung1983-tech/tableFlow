import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { getBookingContext, getBranchAvailability } from '../api/availabilityApi'
import { createReservation } from '../api/reservationApi'
import type {
  AvailabilityResponse,
  BookingContext,
  ReservationCreatedResponse,
} from '../api/types'
import { createQueryClient } from '../app/queryClient'
import { ReservationBookingPage } from './ReservationBookingPage'

vi.mock('../api/availabilityApi', () => ({
  getBookingContext: vi.fn(),
  getBranchAvailability: vi.fn(),
}))

vi.mock('../api/reservationApi', () => ({
  createReservation: vi.fn(),
}))

const branchId = '20000000-0000-0000-0000-000000000001'
const bookingContext: BookingContext = {
  privacyPolicyVersion: '2026-08-16',
  privacyRetentionDays: 365,
  branches: [{
    id: branchId,
    restaurantName: 'TableFlow',
    name: '강남점',
    address: '서울시 강남구',
    timezone: 'Asia/Seoul',
    bookingHorizonDays: 30,
    maxPartySize: 8,
  }],
}
const availability: AvailabilityResponse = {
  branchId,
  date: '2026-08-18',
  timezone: 'Asia/Seoul',
  partySize: 2,
  asOf: '2026-08-16T00:00:00Z',
  slots: [{
    startsAt: '2026-08-18T02:00:00Z',
    endsAt: '2026-08-18T03:30:00Z',
    availableTableCount: 1,
  }],
}
const createdReservation: ReservationCreatedResponse = {
  reservation: {
    reservationCode: 'ABCD234567',
    branchId,
    branchName: '강남점',
    timezone: 'Asia/Seoul',
    tableName: 'T1',
    partySize: 2,
    startsAt: '2026-08-18T02:00:00Z',
    endsAt: '2026-08-18T03:30:00Z',
    status: 'PENDING',
    version: 0,
    createdAt: '2026-08-16T00:00:00Z',
    updatedAt: '2026-08-16T00:00:00Z',
  },
  manageToken: 'manage-token-shown-once',
}

describe('ReservationBookingPage', () => {
  it('creates a reservation and only renders its one-time credentials in memory', async () => {
    const user = userEvent.setup()
    vi.mocked(getBookingContext).mockResolvedValue(bookingContext)
    vi.mocked(getBranchAvailability).mockResolvedValue(availability)
    vi.mocked(createReservation).mockResolvedValue(createdReservation)
    renderPage()

    await fillReservation(user)
    await user.click(screen.getByRole('button', { name: '예약 요청하기' }))

    expect(await screen.findByRole('heading', { name: '예약 요청을 접수했습니다.' }))
      .toBeInTheDocument()
    expect(screen.getByText('ABCD234567')).toBeInTheDocument()
    expect(screen.getByText('manage-token-shown-once')).toBeInTheDocument()
    expect(createReservation).toHaveBeenCalledWith(
      {
        branchId,
        startsAt: '2026-08-18T02:00:00Z',
        partySize: 2,
        guestName: '홍길동',
        guestPhone: '010-1234-5678',
        privacyAgreement: { agreed: true, policyVersion: '2026-08-16' },
      },
      expect.any(String),
    )
    expect(allStorageValues()).not.toContain('ABCD234567')
    expect(allStorageValues()).not.toContain('manage-token-shown-once')
  })

  it('reuses the idempotency key when the same reservation request is retried', async () => {
    const user = userEvent.setup()
    vi.mocked(getBookingContext).mockResolvedValue(bookingContext)
    vi.mocked(getBranchAvailability).mockResolvedValue(availability)
    vi.mocked(createReservation)
      .mockRejectedValueOnce(new Error('일시적으로 접수하지 못했습니다.'))
      .mockResolvedValueOnce(createdReservation)
    renderPage()

    await fillReservation(user)
    await user.click(screen.getByRole('button', { name: '예약 요청하기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('일시적으로 접수하지 못했습니다.')

    await user.click(screen.getByRole('button', { name: '예약 요청하기' }))
    await screen.findByRole('heading', { name: '예약 요청을 접수했습니다.' })

    expect(createReservation).toHaveBeenCalledTimes(2)
    expect(vi.mocked(createReservation).mock.calls[0][1])
      .toBe(vi.mocked(createReservation).mock.calls[1][1])
  })

  it('shows an empty state when the selected conditions have no available slots', async () => {
    const user = userEvent.setup()
    vi.mocked(getBookingContext).mockResolvedValue(bookingContext)
    vi.mocked(getBranchAvailability).mockResolvedValue({ ...availability, slots: [] })
    renderPage()

    await user.selectOptions(await screen.findByLabelText('지점'), branchId)

    expect(await screen.findByText('선택한 조건에 예약 가능한 시간이 없습니다.'))
      .toBeInTheDocument()
  })
})

async function fillReservation(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(await screen.findByLabelText('지점'), branchId)
  await user.clear(screen.getByLabelText('예약 날짜'))
  await user.type(screen.getByLabelText('예약 날짜'), '2026-08-18')
  await user.click(await screen.findByRole('button', { name: /1개 테이블 가능/ }))
  await user.type(screen.getByLabelText('예약자 이름'), '홍길동')
  await user.type(screen.getByLabelText('휴대전화 번호'), '010-1234-5678')
  await user.click(screen.getByRole('checkbox', { name: /개인정보 수집·이용에 동의/ }))
  await waitFor(() => expect(getBranchAvailability).toHaveBeenCalledWith(branchId, '2026-08-18', 2))
}

function renderPage() {
  render(
    <MemoryRouter>
      <QueryClientProvider client={createQueryClient()}>
        <ReservationBookingPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

function allStorageValues() {
  return [localStorage, sessionStorage]
    .flatMap((storage) => Array.from(
      { length: storage.length },
      (_, index) => `${storage.key(index)}=${storage.getItem(storage.key(index) ?? '')}`,
    ))
    .join('\n')
}
