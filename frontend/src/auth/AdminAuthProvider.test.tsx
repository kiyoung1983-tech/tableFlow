import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { User } from 'oidc-client-ts'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminAuthProvider } from './AdminAuthProvider'
import { AdminLoginPanel } from './AdminAccess'
import { useAdminAuth } from './adminAuthContext'

const oidcMocks = vi.hoisted(() => ({
  beginSignin: vi.fn(),
  beginSignout: vi.fn(),
  completeSignin: vi.fn(),
  completeSignout: vi.fn(),
  configurationError: vi.fn(),
  getAccessToken: vi.fn(),
  loadUser: vi.fn(),
  manager: {
    events: {
      addAccessTokenExpired: vi.fn(),
      addUserLoaded: vi.fn(),
      addUserUnloaded: vi.fn(),
      removeAccessTokenExpired: vi.fn(),
      removeUserLoaded: vi.fn(),
      removeUserUnloaded: vi.fn(),
    },
  },
  mode: vi.fn(),
}))

vi.mock('./oidcClient', () => ({
  beginOidcSignin: oidcMocks.beginSignin,
  beginOidcSignout: oidcMocks.beginSignout,
  completeOidcSignin: oidcMocks.completeSignin,
  completeOidcSignout: oidcMocks.completeSignout,
  getAdminAuthMode: oidcMocks.mode,
  getOidcAccessToken: oidcMocks.getAccessToken,
  getOidcConfigurationError: oidcMocks.configurationError,
  getOidcManager: () => oidcMocks.manager,
  loadValidOidcUser: oidcMocks.loadUser,
}))

describe('AdminAuthProvider', () => {
  beforeEach(() => {
    oidcMocks.mode.mockReturnValue('oidc')
    oidcMocks.configurationError.mockReturnValue(undefined)
    oidcMocks.loadUser.mockResolvedValue({
      access_token: 'token',
      profile: { sub: 'admin-1', name: '운영자' },
    } as User)
    oidcMocks.beginSignin.mockResolvedValue(undefined)
  })

  it('restores the tab-scoped OIDC user and exposes an authenticated session', async () => {
    render(<AdminAuthProvider><AuthProbe /></AdminAuthProvider>)

    expect(await screen.findByText('authenticated:운영자')).toBeInTheDocument()
    expect(oidcMocks.manager.events.addUserLoaded).toHaveBeenCalledOnce()
    expect(oidcMocks.manager.events.addAccessTokenExpired).toHaveBeenCalledOnce()
  })

  it('starts a provider redirect from the shared auth context', async () => {
    const user = userEvent.setup()
    oidcMocks.loadUser.mockResolvedValue(null)
    render(<AdminAuthProvider><AuthProbe /></AdminAuthProvider>)
    await screen.findByText('anonymous:none')

    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(oidcMocks.beginSignin).toHaveBeenCalledOnce()
  })

  it('keeps Basic credentials only in the shared in-memory administrator session', async () => {
    const user = userEvent.setup()
    oidcMocks.mode.mockReturnValue('basic')
    render(
      <AdminAuthProvider>
        <AdminLoginPanel basicButtonLabel="연결" />
        <AuthProbe />
      </AdminAuthProvider>,
    )

    await user.type(screen.getByLabelText('관리자 비밀번호'), 'secret')
    await user.click(screen.getByRole('button', { name: '연결' }))

    expect(await screen.findByText('authenticated:developer')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('secret')).not.toBeInTheDocument()
    expect(screen.getByTestId('authorization')).toHaveTextContent(/^Basic /)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})

function AuthProbe() {
  const auth = useAdminAuth()
  return (
    <div>
      <span>{auth.status}:{String(auth.principalLabel ?? 'none')}</span>
      <span data-testid="authorization">{auth.authorization ?? 'none'}</span>
      <button onClick={() => void auth.signIn()} type="button">로그인</button>
    </div>
  )
}
