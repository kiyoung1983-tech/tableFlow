import { api } from './client'
import type {
  CancelReservationRequest,
  ChangeReservationRequest,
  CreateReservationRequest,
  PublicReservation,
  ReservationCreatedResponse,
} from './types'

export function createReservation(
  request: CreateReservationRequest,
  idempotencyKey: string,
): Promise<ReservationCreatedResponse> {
  return api('/api/public/reservations', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    json: request,
  })
}

export function getReservation(
  reservationCode: string,
  manageToken: string,
): Promise<PublicReservation> {
  return api(`/api/public/reservations/${encodeURIComponent(reservationCode)}`, {
    headers: { 'X-Reservation-Token': manageToken },
  })
}

export function changeReservation(
  reservationCode: string,
  manageToken: string,
  request: ChangeReservationRequest,
): Promise<PublicReservation> {
  return api(`/api/public/reservations/${encodeURIComponent(reservationCode)}`, {
    method: 'PATCH',
    headers: { 'X-Reservation-Token': manageToken },
    json: request,
  })
}

export function cancelReservation(
  reservationCode: string,
  manageToken: string,
  request: CancelReservationRequest,
): Promise<PublicReservation> {
  return api(
    `/api/public/reservations/${encodeURIComponent(reservationCode)}/cancellation`,
    {
      method: 'POST',
      headers: { 'X-Reservation-Token': manageToken },
      json: request,
    },
  )
}
