import { createContext, useContext } from 'react'
import type { User } from 'oidc-client-ts'
import type { AdminAuthMode } from './oidcClient'

export type AdminAuthStatus = 'loading' | 'anonymous' | 'authenticated' | 'error'

export type AdminAuthContextValue = {
  mode: AdminAuthMode
  status: AdminAuthStatus
  user?: User
  authorization?: string
  principalLabel?: string
  error?: string
  sessionId: number
  connectBasic: (username: string, password: string) => void
  signIn: () => Promise<void>
  signOut: () => Promise<void>
  completeSignIn: () => Promise<User>
  completeSignOut: () => Promise<string>
}

export const AdminAuthContext = createContext<AdminAuthContextValue | undefined>(undefined)

export function useAdminAuth() {
  const value = useContext(AdminAuthContext)
  if (!value) throw new Error('AdminAuthProvider 안에서 useAdminAuth를 사용해야 합니다.')
  return value
}
