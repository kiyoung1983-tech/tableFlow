import { Link } from 'react-router-dom'

export function HomePage() {
  return (
    <main className="page hero-page">
      <p className="eyebrow">PRODUCTION-MINDED FOUNDATION</p>
      <h1>작게 시작하고,<br />운영 기준으로 확장하세요.</h1>
      <p className="lead">환경 분리, API 계약, 요청 추적, 테스트와 CI가 연결된 Spring 풀스택 기본 골격입니다.</p>
      <Link className="primary-link" to="/todos">Todo API 확인하기</Link>
    </main>
  )
}
