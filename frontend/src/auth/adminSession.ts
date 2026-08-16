import { useAdminAuth } from './adminAuthContext'

export type AdminSession = {
  authorization?: string
  id: number
  label: string
}

export function useAdminSession(): AdminSession | undefined {
  const auth = useAdminAuth()
  if (auth.status !== 'authenticated' || !auth.principalLabel) return undefined
  return {
    authorization: auth.authorization,
    id: auth.sessionId,
    label: auth.principalLabel,
  }
}
