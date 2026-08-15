import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <main className="page narrow-page">
      <p className="eyebrow">404</p>
      <h1>페이지를 찾을 수 없습니다.</h1>
      <Link className="primary-link" to="/">처음으로 돌아가기</Link>
    </main>
  )
}
