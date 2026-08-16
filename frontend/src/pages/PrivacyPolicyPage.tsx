import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getBookingContext } from '../api/availabilityApi'

export function PrivacyPolicyPage() {
  const context = useQuery({
    queryKey: ['booking-context'],
    queryFn: getBookingContext,
    retry: false,
    staleTime: 5 * 60_000,
  })
  const retentionDays = context.data?.privacyRetentionDays
  return (
    <main className="page narrow-page privacy-page">
      <p className="eyebrow">PRIVACY</p>
      <h1>예약 개인정보 수집·이용 안내</h1>
      <section className="privacy-copy">
        <h2>수집 항목</h2>
        <p>예약자 이름, 휴대전화 번호, 예약 지점·일시·인원 정보를 수집합니다.</p>
        <h2>이용 목적</h2>
        <p>예약 접수와 본인 예약 확인·변경·취소, 식당의 예약 운영을 위해 사용합니다.</p>
        <h2>보유 기간</h2>
        <p>
          예약 종료 후 완료·취소·노쇼 등 종료 상태가 된 뒤{' '}
          {retentionDays ? `최대 ${retentionDays}일` : '정해진 보존 기간'} 동안 보관하고
          개인정보를 비식별 처리합니다.
        </p>
        <h2>동의 거부</h2>
        <p>필수 개인정보 수집에 동의하지 않을 수 있지만 온라인 예약을 접수할 수 없습니다.</p>
        <h2>관리 토큰</h2>
        <p>관리 토큰은 예약 조회 권한이므로 타인에게 공개하지 말고 안전하게 보관해야 합니다.</p>
        {context.data && <p className="policy-version">정책 버전 {context.data.privacyPolicyVersion}</p>}
      </section>
      <Link className="primary-link" to="/reservations/new">예약 화면으로 돌아가기</Link>
    </main>
  )
}
