import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { OidcSigninCallbackPage, OidcSignoutCallbackPage } from './OidcCallbackPage'

const useAdminAuthMock = vi.hoisted(() => vi.fn())
vi.mock('../auth/adminAuthContext', () => ({ useAdminAuth: useAdminAuthMock }))

describe('OIDC callback pages', () => {
  it('completes sign-in and returns only to the local admin path', async () => {
    const completeSignIn = vi.fn().mockResolvedValue({
      state: { returnTo: '/admin/reservations' },
    })
    useAdminAuthMock.mockReturnValue({ completeSignIn })

    renderRoutes(<OidcSigninCallbackPage />, '/auth/callback')

    expect(await screen.findByText('운영 보드 도착')).toBeInTheDocument()
    expect(completeSignIn).toHaveBeenCalledOnce()
  })

  it('completes provider sign-out before returning to the board', async () => {
    const completeSignOut = vi.fn().mockResolvedValue('/admin/reservations')
    useAdminAuthMock.mockReturnValue({ completeSignOut })

    renderRoutes(<OidcSignoutCallbackPage />, '/auth/logout-callback')

    expect(await screen.findByText('운영 보드 도착')).toBeInTheDocument()
    expect(completeSignOut).toHaveBeenCalledOnce()
  })
})

function renderRoutes(element: ReactNode, initialEntry: string) {
  render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route element={element} path={initialEntry} />
        <Route element={<p>운영 보드 도착</p>} path="/admin/reservations" />
      </Routes>
    </MemoryRouter>,
  )
}
