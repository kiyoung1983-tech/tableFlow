import { api } from './client'
import type {
  AdminReservation,
  AdminReservationDashboard,
  AdminReservationPage,
  Branch,
  ReservationStatus,
  Restaurant,
  TransitionReservationRequest,
} from './types'

export function listAdminRestaurants(authorization?: string): Promise<Restaurant[]> {
  return api('/api/admin/restaurants', { headers: adminHeaders(authorization) })
}

export function listAdminBranches(
  restaurantId: string,
  authorization?: string,
): Promise<Branch[]> {
  return api(`/api/admin/restaurants/${encodeURIComponent(restaurantId)}/branches`, {
    headers: adminHeaders(authorization),
  })
}

export function listAdminReservations(
  branchId: string,
  date: string,
  status: ReservationStatus | undefined,
  page: number,
  size: number,
  authorization?: string,
): Promise<AdminReservationPage> {
  const search = new URLSearchParams({ date, page: String(page), size: String(size) })
  if (status) search.set('status', status)
  return api(
    `/api/admin/branches/${encodeURIComponent(branchId)}/reservations?${search}`,
    { headers: adminHeaders(authorization) },
  )
}

export function getAdminReservationDashboard(
  branchId: string,
  date: string,
  authorization?: string,
): Promise<AdminReservationDashboard> {
  const search = new URLSearchParams({ date })
  return api(
    `/api/admin/branches/${encodeURIComponent(branchId)}/reservation-dashboard?${search}`,
    { headers: adminHeaders(authorization) },
  )
}

export function transitionAdminReservation(
  reservationId: string,
  request: TransitionReservationRequest,
  authorization?: string,
): Promise<AdminReservation> {
  return api(
    `/api/admin/reservations/${encodeURIComponent(reservationId)}/transitions`,
    {
      method: 'POST',
      headers: adminHeaders(authorization),
      json: request,
    },
  )
}

function adminHeaders(authorization?: string) {
  return authorization ? { Authorization: authorization } : undefined
}
