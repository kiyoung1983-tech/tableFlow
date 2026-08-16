import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listAdminBranches, listAdminRestaurants } from '../api/adminReservationApi'
import {
  createBookingBlock,
  createRestaurant,
  deleteBookingBlock,
  getBusinessHours,
  listBookingBlocks,
  listDiningTables,
  replaceBusinessHours,
  updateBranch,
  updateDiningTable,
} from '../api/adminSettingsApi'
import type { Branch, DiningTable, Restaurant } from '../api/types'
import { createQueryClient } from '../app/queryClient'
import { AdminSettingsPage } from './AdminSettingsPage'
import { zonedLocalToInstant } from './adminSettingsTime'

vi.mock('../api/adminReservationApi', () => ({
  listAdminBranches: vi.fn(),
  listAdminRestaurants: vi.fn(),
}))
vi.mock('../api/adminSettingsApi', () => ({
  createBookingBlock: vi.fn(),
  createBranch: vi.fn(),
  createDiningTable: vi.fn(),
  createRestaurant: vi.fn(),
  deleteBookingBlock: vi.fn(),
  getBusinessHours: vi.fn(),
  listBookingBlocks: vi.fn(),
  listDiningTables: vi.fn(),
  replaceBusinessHours: vi.fn(),
  updateBranch: vi.fn(),
  updateDiningTable: vi.fn(),
  updateRestaurant: vi.fn(),
}))

const useAdminAuthMock = vi.hoisted(() => vi.fn())
vi.mock('../auth/adminAuthContext', () => ({ useAdminAuth: useAdminAuthMock }))

const authorization = 'Basic ZGV2ZWxvcGVyOnNlY3JldA=='
const restaurant: Restaurant = {
  id: '10000000-0000-0000-0000-000000000001',
  name: 'TableFlow',
  status: 'ACTIVE',
  version: 3,
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
  version: 5,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
}
const table: DiningTable = {
  id: '30000000-0000-0000-0000-000000000001',
  branchId: branch.id,
  name: 'T1',
  capacity: 2,
  enabled: true,
  version: 2,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
}

describe('AdminSettingsPage', () => {
  beforeEach(() => {
    useAdminAuthMock.mockReturnValue({
      mode: 'basic',
      status: 'authenticated',
      sessionId: 1,
      authorization,
      principalLabel: 'developer',
      connectBasic: vi.fn(),
      signIn: vi.fn(),
      signOut: vi.fn(),
    })
    vi.mocked(listAdminRestaurants).mockResolvedValue([restaurant])
    vi.mocked(listAdminBranches).mockResolvedValue([branch])
    vi.mocked(listDiningTables).mockResolvedValue([table])
    vi.mocked(getBusinessHours).mockResolvedValue({ items: [] })
    vi.mocked(listBookingBlocks).mockResolvedValue([])
  })

  it('creates a restaurant through the shared administrator session', async () => {
    const user = userEvent.setup()
    vi.mocked(createRestaurant).mockResolvedValue({ ...restaurant, id: 'new-restaurant' })
    renderPage()

    await user.type(await screen.findByLabelText('식당 이름'), '새 식당')
    await user.click(screen.getByRole('button', { name: '식당 만들기' }))

    expect(createRestaurant).toHaveBeenCalledWith({ name: '새 식당' }, authorization)
    expect(await screen.findByText('식당을 만들었습니다.')).toBeInTheDocument()
  })

  it('sends the selected branch version with the complete policy update', async () => {
    const user = userEvent.setup()
    vi.mocked(updateBranch).mockResolvedValue({ ...branch, maxPartySize: 10, version: 6 })
    renderPage()
    await selectBranch(user)

    await user.clear(screen.getByLabelText('최대 예약 인원'))
    await user.type(screen.getByLabelText('최대 예약 인원'), '10')
    await user.click(screen.getByRole('button', { name: '지점 정책 저장' }))

    expect(updateBranch).toHaveBeenCalledWith(
      branch.id,
      expect.objectContaining({
        name: branch.name,
        timezone: branch.timezone,
        maxPartySize: 10,
        expectedVersion: 5,
      }),
      authorization,
    )
  })

  it('replaces the weekly schedule with normalized API times', async () => {
    const user = userEvent.setup()
    vi.mocked(replaceBusinessHours).mockResolvedValue({ items: [] })
    renderPage()
    await selectBranch(user)

    await user.click(await screen.findByRole('button', { name: '영업 구간 추가' }))
    await user.click(screen.getByRole('button', { name: '주간표 전체 저장' }))

    expect(replaceBusinessHours).toHaveBeenCalledWith(
      branch.id,
      { items: [{ dayOfWeek: 1, opensAt: '11:00:00', closesAt: '22:00:00' }] },
      authorization,
    )
  })

  it('uses optimistic table versions when changing capacity', async () => {
    const user = userEvent.setup()
    vi.mocked(updateDiningTable).mockResolvedValue({ ...table, capacity: 4, version: 3 })
    renderPage()
    await selectBranch(user)

    const capacityFields = await screen.findAllByLabelText('수용 인원')
    await user.clear(capacityFields[1])
    await user.type(capacityFields[1], '4')
    await user.click(screen.getAllByRole('button', { name: '저장' })[0])

    expect(updateDiningTable).toHaveBeenCalledWith(
      table.id,
      { name: 'T1', capacity: 4, enabled: true, expectedVersion: 2 },
      authorization,
    )
  })

  it('converts branch-local block times to instants before creating a block', async () => {
    const user = userEvent.setup()
    vi.mocked(createBookingBlock).mockResolvedValue({
      id: '40000000-0000-0000-0000-000000000001',
      branchId: branch.id,
      startsAt: '2026-08-16T03:00:00Z',
      endsAt: '2026-08-16T04:00:00Z',
      reason: '점검',
      createdAt: '2026-08-16T00:00:00Z',
    })
    renderPage()
    await selectBranch(user)

    const startsAt = await screen.findByLabelText('시작') as HTMLInputElement
    const localDate = startsAt.value.slice(0, 10)
    await user.type(screen.getByLabelText('사유'), '점검')
    await user.click(screen.getByRole('button', { name: '차단 시간 추가' }))

    expect(createBookingBlock).toHaveBeenCalledWith(
      branch.id,
      {
        startsAt: `${localDate}T03:00:00.000Z`,
        endsAt: `${localDate}T04:00:00.000Z`,
        reason: '점검',
      },
      authorization,
    )
  })

  it('requires a second action before deleting a booking block', async () => {
    const user = userEvent.setup()
    vi.mocked(listBookingBlocks).mockResolvedValue([{
      id: '40000000-0000-0000-0000-000000000001',
      branchId: branch.id,
      diningTableId: table.id,
      startsAt: '2026-08-16T03:00:00Z',
      endsAt: '2026-08-16T04:00:00Z',
      reason: '점검',
      createdAt: '2026-08-16T00:00:00Z',
    }])
    vi.mocked(deleteBookingBlock).mockResolvedValue(undefined)
    renderPage()
    await selectBranch(user)

    await user.click(await screen.findByRole('button', { name: '삭제 검토' }))
    expect(deleteBookingBlock).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '삭제 확정' }))

    expect(deleteBookingBlock).toHaveBeenCalledWith(
      '40000000-0000-0000-0000-000000000001',
      authorization,
    )
  })
})

describe('zonedLocalToInstant', () => {
  it('converts an unambiguous local time with the branch timezone', () => {
    expect(zonedLocalToInstant('2026-07-01T12:00', 'America/New_York'))
      .toBe('2026-07-01T16:00:00.000Z')
  })

  it('rejects nonexistent and ambiguous daylight-saving times', () => {
    expect(() => zonedLocalToInstant('2026-03-08T02:30', 'America/New_York'))
      .toThrow('존재하지 않는')
    expect(() => zonedLocalToInstant('2026-11-01T01:30', 'America/New_York'))
      .toThrow('모호한')
  })
})

async function selectBranch(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('option', { name: /TableFlow/ })
  await user.selectOptions(await screen.findByLabelText('식당'), restaurant.id)
  await screen.findByRole('option', { name: /강남점/ })
  await user.selectOptions(await screen.findByLabelText('지점'), branch.id)
  await screen.findByRole('heading', { name: '지점과 예약 정책' })
}

function renderPage() {
  render(
    <MemoryRouter>
      <QueryClientProvider client={createQueryClient()}>
        <AdminSettingsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}
