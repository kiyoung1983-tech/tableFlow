import { Link } from 'react-router-dom'

export function HomePage() {
  return (
    <main className="page hero-page">
      <p className="eyebrow">RESTAURANT RESERVATION</p>
      <h1>좋은 식사의 시작을,<br />더 간편한 예약으로.</h1>
      <p className="lead">TableFlow는 식당의 좌석과 시간을 연결하는 예약 관리 서비스입니다.</p>
      <Link className="primary-link" to="/todos">기준 API 확인하기</Link>
    </main>
  )
}
