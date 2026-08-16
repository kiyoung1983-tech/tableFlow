import { afterEach, describe, expect, it, vi } from 'vitest'
import type { User } from 'oidc-client-ts'
import {
  getAdminAuthMode,
  getOidcConfigurationError,
  getOidcManager,
  resetOidcClientForTests,
  returnToFromUser,
} from './oidcClient'

afterEach(() => {
  resetOidcClientForTests()
  window.sessionStorage.clear()
  vi.unstubAllEnvs()
})

describe('OIDC client configuration', () => {
  it('uses local Basic mode when the production auth mode is not configured', () => {
    vi.stubEnv('VITE_ADMIN_AUTH_MODE', '')

    expect(getAdminAuthMode()).toBe('basic')
    expect(getOidcConfigurationError()).toBeUndefined()
  })

  it('requires authority and public SPA client ID in OIDC mode', () => {
    vi.stubEnv('VITE_ADMIN_AUTH_MODE', 'oidc')
    vi.stubEnv('VITE_OIDC_AUTHORITY', '')
    vi.stubEnv('VITE_OIDC_CLIENT_ID', '')

    expect(getOidcConfigurationError()).toContain('VITE_OIDC_AUTHORITY')
    expect(getOidcConfigurationError()).toContain('VITE_OIDC_CLIENT_ID')
  })

  it('fails closed when the authentication mode contains a typo', () => {
    vi.stubEnv('VITE_ADMIN_AUTH_MODE', 'odic')

    expect(getAdminAuthMode()).toBe('oidc')
    expect(getOidcConfigurationError()).toContain('basic 또는 oidc')
  })

  it('configures authorization code flow and session-scoped storage', () => {
    vi.stubEnv('VITE_ADMIN_AUTH_MODE', 'oidc')
    vi.stubEnv('VITE_OIDC_AUTHORITY', 'https://id.example.com/realms/tableflow')
    vi.stubEnv('VITE_OIDC_CLIENT_ID', 'tableflow-spa')

    const settings = getOidcManager().settings

    expect(settings.response_type).toBe('code')
    expect(settings.redirect_uri).toBe('http://localhost:3000/auth/callback')
    expect(settings.automaticSilentRenew).toBe(false)
  })

  it('rejects an external return URL from OIDC state', () => {
    const user = { state: { returnTo: '//evil.example' } } as User

    expect(returnToFromUser(user)).toBe('/admin/reservations')
  })
})
