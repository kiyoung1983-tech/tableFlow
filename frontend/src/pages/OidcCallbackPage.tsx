import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAdminAuth } from '../auth/adminAuthContext'
import { returnToFromUser } from '../auth/oidcClient'

export function OidcSigninCallbackPage() {
  const { completeSignIn } = useAdminAuth()
  const navigate = useNavigate()
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    void completeSignIn()
      .then((user) => navigate(returnToFromUser(user), { replace: true }))
      .catch(() => setFailed(true))
  }, [completeSignIn, navigate])

  return <CallbackStatus failed={failed} message="관리자 로그인을 확인하는 중입니다…" />
}

export function OidcSignoutCallbackPage() {
  const { completeSignOut } = useAdminAuth()
  const navigate = useNavigate()
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    void completeSignOut()
      .then((returnTo) => navigate(returnTo, { replace: true }))
      .catch(() => setFailed(true))
  }, [completeSignOut, navigate])

  return <CallbackStatus failed={failed} message="관리자 로그아웃을 마무리하는 중입니다…" />
}

function CallbackStatus({ failed, message }: { failed: boolean; message: string }) {
  return (
    <main className="page narrow-page">
      <p className="eyebrow">ADMIN AUTH</p>
      <h1>{failed ? '인증 응답을 처리하지 못했습니다.' : message}</h1>
      {failed && (
        <>
          <p className="status error" role="alert">
            운영 보드로 돌아가 로그인을 다시 시도해 주세요.
          </p>
          <p><Link className="primary-link" to="/admin/reservations">운영 보드로 돌아가기</Link></p>
        </>
      )}
    </main>
  )
}
