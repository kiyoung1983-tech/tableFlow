import { api } from './client'
import type {
  BookingBlock,
  Branch,
  BusinessHours,
  CreateBookingBlockRequest,
  CreateBranchRequest,
  CreateDiningTableRequest,
  CreateRestaurantRequest,
  DiningTable,
  ReplaceBusinessHoursRequest,
  Restaurant,
  UpdateBranchRequest,
  UpdateDiningTableRequest,
  UpdateRestaurantRequest,
} from './types'

export function createRestaurant(
  request: CreateRestaurantRequest,
  authorization?: string,
): Promise<Restaurant> {
  return api('/api/admin/restaurants', {
    method: 'POST',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function updateRestaurant(
  restaurantId: string,
  request: UpdateRestaurantRequest,
  authorization?: string,
): Promise<Restaurant> {
  return api(`/api/admin/restaurants/${encodeURIComponent(restaurantId)}`, {
    method: 'PATCH',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function createBranch(
  restaurantId: string,
  request: CreateBranchRequest,
  authorization?: string,
): Promise<Branch> {
  return api(`/api/admin/restaurants/${encodeURIComponent(restaurantId)}/branches`, {
    method: 'POST',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function updateBranch(
  branchId: string,
  request: UpdateBranchRequest,
  authorization?: string,
): Promise<Branch> {
  return api(`/api/admin/branches/${encodeURIComponent(branchId)}`, {
    method: 'PATCH',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function listDiningTables(
  branchId: string,
  authorization?: string,
): Promise<DiningTable[]> {
  return api(`/api/admin/branches/${encodeURIComponent(branchId)}/tables`, {
    headers: adminHeaders(authorization),
  })
}

export function createDiningTable(
  branchId: string,
  request: CreateDiningTableRequest,
  authorization?: string,
): Promise<DiningTable> {
  return api(`/api/admin/branches/${encodeURIComponent(branchId)}/tables`, {
    method: 'POST',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function updateDiningTable(
  tableId: string,
  request: UpdateDiningTableRequest,
  authorization?: string,
): Promise<DiningTable> {
  return api(`/api/admin/tables/${encodeURIComponent(tableId)}`, {
    method: 'PATCH',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function getBusinessHours(
  branchId: string,
  authorization?: string,
): Promise<BusinessHours> {
  return api(`/api/admin/branches/${encodeURIComponent(branchId)}/business-hours`, {
    headers: adminHeaders(authorization),
  })
}

export function replaceBusinessHours(
  branchId: string,
  request: ReplaceBusinessHoursRequest,
  authorization?: string,
): Promise<BusinessHours> {
  return api(`/api/admin/branches/${encodeURIComponent(branchId)}/business-hours`, {
    method: 'PUT',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function listBookingBlocks(
  branchId: string,
  startsAt: string,
  endsAt: string,
  authorization?: string,
): Promise<BookingBlock[]> {
  const search = new URLSearchParams({ startsAt, endsAt })
  return api(`/api/admin/branches/${encodeURIComponent(branchId)}/booking-blocks?${search}`, {
    headers: adminHeaders(authorization),
  })
}

export function createBookingBlock(
  branchId: string,
  request: CreateBookingBlockRequest,
  authorization?: string,
): Promise<BookingBlock> {
  return api(`/api/admin/branches/${encodeURIComponent(branchId)}/booking-blocks`, {
    method: 'POST',
    headers: adminHeaders(authorization),
    json: request,
  })
}

export function deleteBookingBlock(
  bookingBlockId: string,
  authorization?: string,
): Promise<void> {
  return api(`/api/admin/booking-blocks/${encodeURIComponent(bookingBlockId)}`, {
    method: 'DELETE',
    headers: adminHeaders(authorization),
  })
}

function adminHeaders(authorization?: string) {
  return authorization ? { Authorization: authorization } : undefined
}
