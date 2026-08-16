import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getAdminReservationDashboard,
  listAdminBranches,
  listAdminReservations,
  listAdminRestaurants,
  transitionAdminReservation,
} from '../api/adminReservationApi'
import { createQueryClient } from '../app/queryClient'
import type {
  AdminReservation,
  AdminReservationDashboard,
  AdminReservationPage as AdminReservationPageResponse,
  Branch,
  Restaurant,
} from '../api/types'
import { AdminReservationPage } from './AdminReservationPage'

vi.mock('../api/adminReservationApi', () => ({
  getAdminReservationDashboard: vi.fn(),
  listAdminBranches: vi.fn(),
  listAdminReservations: vi.fn(),
  listAdminRestaurants: vi.fn(),
  transitionAdminReservation: vi.fn(),
}))

const useAdminAuthMock = vi.hoisted(() => vi.fn())
vi.mock('../auth/adminAuthContext', () => ({ useAdminAuth: useAdminAuthMock }))

const restaurant: Restaurant = {
  id: '10000000-0000-0000-0000-000000000001',
  name: 'TableFlow',
  status: 'ACTIVE',
  version: 0,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
}

const branch: Branch = {
  id: '20000000-0000-0000-0000-000000000001',
  restaurantId: restaurant.id,
  name: '강남점',
  address: '서울시 강남구',
  timezone: 'Asia/Seoul',
  status: 'ACTIVE',
  slotIntervalMinutes: 30,
  defaultDurationMinutes: 90,
  bufferMinutes: 15,
  minAdvanceMinutes: 60,
  bookingHorizonDays: 30,
  changeCutoffMinutes: 180,
  noShowGraceMinutes: 15,
  maxPartySize: 8,
  maxCapacityGap: 2,
  version: 0,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
}

const pendingReservation: AdminReservation = {
  id: '30000000-0000-0000-0000-000000000001',
  reservationCode: 'ABCD234567',
  branchId: branch.id,
  branchName: branch.name,
  timezone: branch.timezone,
  tableName: 'T1',
  partySize: 2,
  startsAt: '2026-08-18T02:00:00Z',
  endsAt: '2026-08-18T03:30:00Z',
  status: 'PENDING',
  version: 0,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
  guestName: '운영 고객',
  guestPhoneLastFour: '1234',
  history: [{
    fromStatus: null,
    toStatus: 'PENDING',
    actorType: 'CUSTOMER',
    changedAt: '2026-08-16T00:00:00Z',
  }],
}

const dashboard: AdminReservationDashboard = {
  branchId: branch.id,
  date: '2026-08-18',
  timezone: branch.timezone,
  asOf: '2026-08-16T00:00:00Z',
  totalEnabledTables: 2,
  statusCounts: {
    pending: 1,
    confirmed: 0,
    seated: 0,
    completed: 0,
    cancelled: 0,
    noShow: 0,
  },
  capacityTimeline: [{
    startsAt: '2026-08-18T02:00:00Z',
    endsAt: '2026-08-18T02:30:00Z',
    availableTableCount: 1,
    occupiedTableCount: 1,
    blockedTableCount: 0,
  }],
}

describe('AdminReservationPage', () => {
  beforeEach(() => {
    useAdminAuthMock.mockReturnValue({
      mode: 'basic',
      status: 'authenticated',
      sessionId: 1,
      authorization: 'Basic ZGV2ZWxvcGVyOnNlY3JldA==',
      principalLabel: 'developer',
      connectBasic: vi.fn(),
      signIn: vi.fn(),
      signOut: vi.fn(),
    })
  })

  it('uses the shared in-memory session and renders the selected branch board', async () => {
    const user = userEvent.setup()
    mockBoardQueries(pageWith(pendingReservation))
    renderPage()

    await user.selectOptions(await screen.findByLabelText('식당'), restaurant.id)
    await user.selectOptions(await screen.findByLabelText('지점'), branch.id)

    expect(await screen.findByText('운영 고객')).toBeInTheDocument()
    expect(screen.getByText('•••• 1234')).toBeInTheDocument()
    expect(screen.getByText('운영 테이블 2개')).toBeInTheDocument()
    expect(listAdminReservations).toHaveBeenCalledWith(
      branch.id,
      expect.any(String),
      undefined,
      0,
      20,
      'Basic ZGV2ZWxvcGVyOnNlY3JldA==',
    )
  })

  it('uses the displayed reservation version when applying a state transition', async () => {
    const user = userEvent.setup()
    const confirmed = { ...pendingReservation, status: 'CONFIRMED' as const, version: 1 }
    vi.mocked(listAdminRestaurants).mockResolvedValue([restaurant])
    vi.mocked(listAdminBranches).mockResolvedValue([branch])
    vi.mocked(listAdminReservations)
      .mockResolvedValueOnce(pageWith(pendingReservation))
      .mockResolvedValue(pageWith(confirmed))
    vi.mocked(getAdminReservationDashboard).mockResolvedValue(dashboard)
    vi.mocked(transitionAdminReservation).mockResolvedValue(confirmed)
    renderPage()

    await user.selectOptions(await screen.findByLabelText('식당'), restaurant.id)
    await user.selectOptions(await screen.findByLabelText('지점'), branch.id)
    await screen.findByText('운영 고객')
    await user.click(screen.getByRole('button', { name: '예약 확정' }))

    expect(transitionAdminReservation).toHaveBeenCalledWith(
      pendingReservation.id,
      { targetStatus: 'CONFIRMED', expectedVersion: 0 },
      'Basic ZGV2ZWxvcGVyOnNlY3JldA==',
    )
    expect(await screen.findByText('확정', { selector: '.reservation-status' }))
      .toBeInTheDocument()
  })

  it('requires a second click for destructive administrator transitions', async () => {
    const user = userEvent.setup()
    const cancelled = { ...pendingReservation, status: 'CANCELLED' as const, version: 1 }
    mockBoardQueries(pageWith(pendingReservation))
    vi.mocked(transitionAdminReservation).mockResolvedValue(cancelled)
    renderPage()

    await user.selectOptions(await screen.findByLabelText('식당'), restaurant.id)
    await user.selectOptions(await screen.findByLabelText('지점'), branch.id)
    await screen.findByText('운영 고객')

    await user.click(screen.getByRole('button', { name: '예약 취소' }))
    expect(transitionAdminReservation).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '예약 취소 확정' }))

    expect(transitionAdminReservation).toHaveBeenCalledWith(
      pendingReservation.id,
      { targetStatus: 'CANCELLED', expectedVersion: 0 },
      'Basic ZGV2ZWxvcGVyOnNlY3JldA==',
    )
  })

  it('starts the OIDC redirect instead of showing the Basic credential form', async () => {
    const user = userEvent.setup()
    const signIn = vi.fn().mockResolvedValue(undefined)
    useAdminAuthMock.mockReturnValue({
      mode: 'oidc',
      status: 'anonymous',
      sessionId: 0,
      connectBasic: vi.fn(),
      signIn,
      signOut: vi.fn(),
    })
    renderPage()

    expect(screen.queryByLabelText('관리자 비밀번호')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'OIDC로 관리자 로그인' }))

    expect(signIn).toHaveBeenCalledOnce()
  })
})

function mockBoardQueries(page: AdminReservationPageResponse) {
  vi.mocked(listAdminRestaurants).mockResolvedValue([restaurant])
  vi.mocked(listAdminBranches).mockResolvedValue([branch])
  vi.mocked(listAdminReservations).mockResolvedValue(page)
  vi.mocked(getAdminReservationDashboard).mockResolvedValue(dashboard)
}

function pageWith(reservation: AdminReservation): AdminReservationPageResponse {
  return {
    items: [reservation],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  }
}

function renderPage() {
  render(
    <MemoryRouter>
      <QueryClientProvider client={createQueryClient()}>
        <AdminReservationPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}
