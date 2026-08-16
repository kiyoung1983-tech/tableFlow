import {
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from 'oidc-client-ts'

export type AdminAuthMode = 'basic' | 'oidc'

type ReturnState = { returnTo?: string }

let manager: UserManager | undefined
let renewal: Promise<User | null> | undefined

export function getAdminAuthMode(): AdminAuthMode {
  const configured = import.meta.env.VITE_ADMIN_AUTH_MODE?.trim() || 'basic'
  return configured === 'basic' ? 'basic' : 'oidc'
}

export function getOidcConfigurationError(): string | undefined {
  const configuredMode = import.meta.env.VITE_ADMIN_AUTH_MODE?.trim()
  if (configuredMode && configuredMode !== 'basic' && configuredMode !== 'oidc') {
    return 'OIDC 설정이 올바르지 않습니다: VITE_ADMIN_AUTH_MODE는 basic 또는 oidc여야 합니다.'
  }
  if (getAdminAuthMode() !== 'oidc') return undefined
  const missing = [
    ['VITE_OIDC_AUTHORITY', import.meta.env.VITE_OIDC_AUTHORITY],
    ['VITE_OIDC_CLIENT_ID', import.meta.env.VITE_OIDC_CLIENT_ID],
  ].filter(([, value]) => !value?.trim()).map(([name]) => name)
  return missing.length === 0
    ? undefined
    : `OIDC 설정이 누락되었습니다: ${missing.join(', ')}`
}

export function getOidcManager(): UserManager {
  const configurationError = getOidcConfigurationError()
  if (configurationError) throw new Error(configurationError)
  if (manager) return manager

  const origin = window.location.origin
  const settings: UserManagerSettings = {
    authority: import.meta.env.VITE_OIDC_AUTHORITY!,
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID!,
    redirect_uri: import.meta.env.VITE_OIDC_REDIRECT_URI || `${origin}/auth/callback`,
    post_logout_redirect_uri:
      import.meta.env.VITE_OIDC_POST_LOGOUT_REDIRECT_URI || `${origin}/auth/logout-callback`,
    response_type: 'code',
    scope: import.meta.env.VITE_OIDC_SCOPE || 'openid profile',
    resource: import.meta.env.VITE_OIDC_RESOURCE || undefined,
    automaticSilentRenew: false,
    monitorSession: false,
    stateStore: new WebStorageStateStore({
      prefix: 'tableflow.oidc.state.',
      store: window.sessionStorage,
    }),
    userStore: new WebStorageStateStore({
      prefix: 'tableflow.oidc.user.',
      store: window.sessionStorage,
    }),
  }
  manager = new UserManager(settings)
  return manager
}

export async function loadValidOidcUser(): Promise<User | null> {
  const current = await getOidcManager().getUser()
  if (!current) return null
  if (!current.expired) return current
  if (!current.refresh_token) {
    await getOidcManager().removeUser()
    return null
  }
  renewal ??= getOidcManager().signinSilent()
    .catch(async () => {
      await getOidcManager().removeUser()
      return null
    })
    .finally(() => {
      renewal = undefined
    })
  return renewal
}

export async function getOidcAccessToken(): Promise<string | undefined> {
  return (await loadValidOidcUser())?.access_token
}

export function beginOidcSignin(returnTo = '/admin/reservations') {
  return getOidcManager().signinRedirect({ state: { returnTo } satisfies ReturnState })
}

export function completeOidcSignin() {
  return getOidcManager().signinRedirectCallback()
}

export async function beginOidcSignout(returnTo = '/admin/reservations') {
  try {
    await getOidcManager().signoutRedirect({ state: { returnTo } satisfies ReturnState })
  } catch {
    await getOidcManager().removeUser()
    window.location.assign(safeReturnTo({ returnTo }))
  }
}

export async function completeOidcSignout(): Promise<string> {
  const response = await getOidcManager().signoutRedirectCallback()
  await getOidcManager().removeUser()
  return safeReturnTo(response.userState)
}

export function returnToFromUser(user: User): string {
  return safeReturnTo(user.state)
}

function safeReturnTo(state: unknown): string {
  const candidate = isReturnState(state) ? state.returnTo : undefined
  return candidate?.startsWith('/') && !candidate.startsWith('//')
    ? candidate
    : '/admin/reservations'
}

function isReturnState(value: unknown): value is ReturnState {
  if (typeof value !== 'object' || value === null || !('returnTo' in value)) return false
  const returnTo = (value as { returnTo?: unknown }).returnTo
  return returnTo === undefined || typeof returnTo === 'string'
}

export function resetOidcClientForTests() {
  manager = undefined
  renewal = undefined
}
