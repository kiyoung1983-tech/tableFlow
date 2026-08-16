import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import {
  cancelReservation,
  changeReservation,
  getReservation,
} from '../api/reservationApi'
import type { PublicReservation } from '../api/types'
import { ReservationManagePage } from './ReservationManagePage'

vi.mock('../api/reservationApi', () => ({
  cancelReservation: vi.fn(),
  changeReservation: vi.fn(),
  getReservation: vi.fn(),
}))

const pendingReservation: PublicReservation = {
  reservationCode: 'ABCD234567',
  branchId: '10000000-0000-0000-0000-000000000001',
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
}

describe('ReservationManagePage', () => {
  it('looks up a reservation and sends its latest version when changing it', async () => {
    const user = userEvent.setup()
    vi.mocked(getReservation).mockResolvedValue(pendingReservation)
    vi.mocked(changeReservation).mockResolvedValue({
      ...pendingReservation,
      partySize: 3,
      tableName: 'T2',
      version: 1,
    })
    renderPage()

    await user.type(screen.getByLabelText('예약 번호'), 'abcd234567')
    await user.type(screen.getByLabelText('관리 토큰'), 'secret-token')
    await user.click(screen.getByRole('button', { name: '예약 확인' }))

    expect(await screen.findByText('강남점')).toBeInTheDocument()
    expect(getReservation).toHaveBeenCalledWith('ABCD234567', 'secret-token')

    await user.type(screen.getByLabelText('새 인원'), '3')
    await user.click(screen.getByRole('button', { name: '변경 저장' }))

    expect(changeReservation).toHaveBeenCalledWith(
      'ABCD234567',
      'secret-token',
      { partySize: 3, expectedVersion: 0 },
    )
    expect(await screen.findByText('3명')).toBeInTheDocument()
    expect(await screen.findByText('예약을 변경했습니다.')).toBeInTheDocument()
  })

  it('requires a second confirmation before cancelling and renders the terminal state', async () => {
    const user = userEvent.setup()
    vi.mocked(getReservation).mockResolvedValue(pendingReservation)
    vi.mocked(cancelReservation).mockResolvedValue({
      ...pendingReservation,
      status: 'CANCELLED',
      version: 1,
    })
    renderPage()

    await user.type(screen.getByLabelText('예약 번호'), 'ABCD234567')
    await user.type(screen.getByLabelText('관리 토큰'), 'secret-token')
    await user.click(screen.getByRole('button', { name: '예약 확인' }))
    await screen.findByText('강남점')

    expect(screen.queryByRole('button', { name: '취소 확정' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '예약 취소 검토' }))
    await user.click(screen.getByRole('button', { name: '취소 확정' }))

    expect(cancelReservation).toHaveBeenCalledWith(
      'ABCD234567',
      'secret-token',
      { expectedVersion: 0 },
    )
    expect(await screen.findByText('예약 취소')).toBeInTheDocument()
    expect(screen.getByText(/현재 상태에서 고객이 직접 변경하거나 취소할 수 없습니다/))
      .toBeInTheDocument()
  })
})

function renderPage() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ReservationManagePage />
    </QueryClientProvider>,
  )
}
