import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from 'react'
import type { User } from 'oidc-client-ts'
import { configureAccessTokenProvider } from '../api/client'
import {
  AdminAuthContext,
  type AdminAuthContextValue,
  type AdminAuthStatus,
} from './adminAuthContext'
import {
  beginOidcSignin,
  beginOidcSignout,
  completeOidcSignin,
  completeOidcSignout,
  getAdminAuthMode,
  getOidcAccessToken,
  getOidcConfigurationError,
  getOidcManager,
  loadValidOidcUser,
} from './oidcClient'

export function AdminAuthProvider({ children }: PropsWithChildren) {
  const mode = getAdminAuthMode()
  const configurationError = getOidcConfigurationError()
  const [user, setUser] = useState<User>()
  const [status, setStatus] = useState<AdminAuthStatus>(
    mode === 'oidc' ? 'loading' : 'anonymous',
  )
  const [error, setError] = useState<string | undefined>(configurationError)
  const [sessionId, setSessionId] = useState(0)
  const [basicSession, setBasicSession] = useState<{
    authorization: string
    label: string
  }>()

  const updateUser = useCallback((nextUser?: User | null) => {
    setUser(nextUser ?? undefined)
    setStatus(nextUser ? 'authenticated' : 'anonymous')
    setError(undefined)
    setSessionId((current) => current + 1)
  }, [])

  useEffect(() => {
    if (configurationError) {
      setStatus('error')
      configureAccessTokenProvider()
      return
    }
    if (mode === 'basic') {
      configureAccessTokenProvider()
      return
    }

    const oidc = getOidcManager()
    const onUserLoaded = (loadedUser: User) => updateUser(loadedUser)
    const onUserUnloaded = () => updateUser()
    const onAccessTokenExpired = () => {
      void loadValidOidcUser().then(updateUser)
    }
    oidc.events.addUserLoaded(onUserLoaded)
    oidc.events.addUserUnloaded(onUserUnloaded)
    oidc.events.addAccessTokenExpired(onAccessTokenExpired)
    configureAccessTokenProvider(getOidcAccessToken)
    void loadValidOidcUser()
      .then(updateUser)
      .catch((reason: unknown) => {
        setStatus('error')
        setError(authErrorMessage(reason))
      })

    return () => {
      oidc.events.removeUserLoaded(onUserLoaded)
      oidc.events.removeUserUnloaded(onUserUnloaded)
      oidc.events.removeAccessTokenExpired(onAccessTokenExpired)
      configureAccessTokenProvider()
    }
  }, [configurationError, mode, updateUser])

  const signIn = useCallback(async () => {
    setError(undefined)
    try {
      await beginOidcSignin()
    } catch (reason) {
      setStatus('error')
      setError(authErrorMessage(reason))
    }
  }, [])

  const signOut = useCallback(async () => {
    setError(undefined)
    if (mode === 'basic') {
      setBasicSession(undefined)
      setStatus('anonymous')
      setSessionId((current) => current + 1)
      return
    }
    await beginOidcSignout()
  }, [mode])

  const connectBasic = useCallback((username: string, password: string) => {
    if (mode !== 'basic') return
    setBasicSession({
      authorization: basicAuthorization(username, password),
      label: username,
    })
    setError(undefined)
    setStatus('authenticated')
    setSessionId((current) => current + 1)
  }, [mode])

  const finishSignIn = useCallback(async () => {
    try {
      const signedInUser = await completeOidcSignin()
      updateUser(signedInUser)
      return signedInUser
    } catch (reason) {
      setStatus('error')
      setError(authErrorMessage(reason))
      throw reason
    }
  }, [updateUser])

  const finishSignOut = useCallback(async () => {
    try {
      const returnTo = await completeOidcSignout()
      updateUser()
      return returnTo
    } catch (reason) {
      setStatus('error')
      setError(authErrorMessage(reason))
      throw reason
    }
  }, [updateUser])

  const value = useMemo<AdminAuthContextValue>(() => ({
    mode,
    status,
    user,
    authorization: basicSession?.authorization,
    principalLabel: basicSession?.label ?? (user ? oidcUserLabel(user.profile) : undefined),
    error,
    sessionId,
    connectBasic,
    signIn,
    signOut,
    completeSignIn: finishSignIn,
    completeSignOut: finishSignOut,
  }), [
    basicSession,
    connectBasic,
    error,
    finishSignIn,
    finishSignOut,
    mode,
    sessionId,
    signIn,
    signOut,
    status,
    user,
  ])

  return <AdminAuthContext.Provider value={value}>{children}</AdminAuthContext.Provider>
}

function basicAuthorization(username: string, password: string) {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return `Basic ${btoa(binary)}`
}

function oidcUserLabel(profile: Record<string, unknown>) {
  for (const claim of ['name', 'preferred_username', 'email', 'sub']) {
    const value = profile[claim]
    if (typeof value === 'string' && value.trim()) return value
  }
  return 'OIDC 관리자'
}

function authErrorMessage(reason: unknown) {
  if (reason instanceof Error && reason.message.startsWith('OIDC 설정이 누락되었습니다:')) {
    return reason.message
  }
  return '관리자 인증 세션을 처리하지 못했습니다. 다시 로그인해 주세요.'
}
