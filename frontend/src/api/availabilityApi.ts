import { api } from './client'
import type { AvailabilityResponse, BookingContext } from './types'

export function getBookingContext(): Promise<BookingContext> {
  return api('/api/public/booking-context')
}

export function getBranchAvailability(
  branchId: string,
  date: string,
  partySize: number,
): Promise<AvailabilityResponse> {
  const query = new URLSearchParams({ date, partySize: String(partySize) })
  return api(`/api/public/branches/${encodeURIComponent(branchId)}/availability?${query}`)
}
