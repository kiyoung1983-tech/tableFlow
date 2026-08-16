import { QueryClientProvider } from '@tanstack/react-query'
import { useState, type PropsWithChildren } from 'react'
import { createQueryClient } from './queryClient'
import { AdminAuthProvider } from '../auth/AdminAuthProvider'

export function AppProviders({ children }: PropsWithChildren) {
  const [queryClient] = useState(createQueryClient)
  return (
    <QueryClientProvider client={queryClient}>
      <AdminAuthProvider>{children}</AdminAuthProvider>
    </QueryClientProvider>
  )
}
