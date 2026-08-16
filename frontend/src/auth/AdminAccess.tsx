import { useQueryClient } from '@tanstack/react-query'
import { type FormEvent, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { useAdminAuth } from './adminAuthContext'
import type { AdminSession } from './adminSession'

export function AdminLoginPanel({ basicButtonLabel }: { basicButtonLabel: string }) {
  const auth = useAdminAuth()
  const [username, setUsername] = useState('developer')
  const [password, setPassword] = useState('')

  if (auth.mode === 'oidc') {
    return (
      <section className="admin-oidc-login" aria-label="관리자 로그인">
        {auth.status === 'loading' && <p className="status">인증 세션을 확인하는 중…</p>}
        {auth.error && <p className="status error" role="alert">{auth.error}</p>}
        <button
          disabled={auth.status === 'loading' || auth.error?.startsWith('OIDC 설정') === true}
          onClick={() => void auth.signIn()}
          type="button"
        >OIDC로 관리자 로그인</button>
      </section>
    )
  }

  function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    auth.connectBasic(username, password)
    setPassword('')
  }

  return (
    <form className="admin-login" onSubmit={connect}>
      <label>
        관리자 아이디
        <input
          autoComplete="username"
          onChange={(event) => setUsername(event.target.value)}
          required
          value={username}
        />
      </label>
      <label>
        관리자 비밀번호
        <input
          autoComplete="current-password"
          onChange={(event) => setPassword(event.target.value)}
          required
          type="password"
          value={password}
        />
      </label>
      <button type="submit">{basicButtonLabel}</button>
    </form>
  )
}

export function AdminSessionHeader({ session }: { session: AdminSession }) {
  const auth = useAdminAuth()
  const queryClient = useQueryClient()

  async function disconnect() {
    queryClient.removeQueries({ queryKey: ['admin'] })
    await auth.signOut()
  }

  return (
    <>
      <div className="admin-session-bar">
        <p><strong>{session.label}</strong> 계정으로 연결됨</p>
        <button className="text-button" onClick={() => void disconnect()} type="button">
          연결 해제
        </button>
      </div>
      <div className="admin-subnav" aria-label="관리자 메뉴" role="navigation">
        <NavLink to="/admin/reservations">예약 운영</NavLink>
        <NavLink to="/admin/settings">매장 설정</NavLink>
      </div>
    </>
  )
}
