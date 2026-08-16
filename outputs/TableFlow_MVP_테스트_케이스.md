# TableFlow MVP 테스트 케이스

작성 기준일: 2026-08-17  
대상 브랜치: `feature/tableflow-mvp`  
관련 문서: `TableFlow_MVP_설계.md`, `TableFlow_프로젝트_현황_요약.md`, `TableFlow_스테이징_UAT_체크리스트.md`

## 1. 목적과 판정 기준

이 문서는 TableFlow MVP 요구사항을 단위·통합·프런트·운영 자동화와 스테이징 수동 UAT에 연결한다.

- `P0`: 중복 예약, 개인정보 노출, 권한 우회, 데이터 손상을 막는 릴리스 차단 항목
- `P1`: 주요 고객·관리자 흐름과 정책 경계
- `P2`: 표시, 사용성과 운영 편의 회귀
- `자동화`: 저장소 테스트가 직접 검증
- `부분 자동화`: 로직은 자동화됐지만 실제 IdP·브라우저·인프라 확인 필요
- `수동 UAT`: 승인된 스테이징 환경에서 체크리스트로 수행

P0 자동화나 필수 수동 UAT가 하나라도 실패하면 릴리스 후보로 판정하지 않는다.

## 2. 공통 환경과 데이터 원칙

- 백엔드 통합 테스트는 Testcontainers PostgreSQL과 `test` 프로필을 사용한다.
- 시간 경계 테스트는 주입한 고정 `Clock`을 사용한다.
- 대표 기준 시각은 `2026-08-17T00:00:00Z`, 지점 시간대는 `Asia/Seoul`이다.
- 대표 지점은 활성 식당, 화요일 영업시간과 2인 테이블 `T1`, `T2`를 가진다.
- 실제 고객 정보 대신 테스트 전용 합성 이름과 전화번호만 사용한다.
- 예약 번호, 관리 토큰, access token과 전체 전화번호를 문서·스크린샷·로그 증적에 남기지 않는다.
- 동시성 테스트는 동시에 요청을 시작하고 허용된 응답 조합과 최종 DB 불변식을 함께 검증한다.

## 3. 예약 컨텍스트와 가용성

### TF-AV-001 · 활성 지점과 개인정보 정책 노출

- 우선순위: P1
- 절차: 활성·비활성 식당이 함께 있는 상태에서 `GET /api/public/booking-context`를 호출한다.
- 기대: 활성 식당의 활성 지점만 반환하고 정책 버전, 보존 기간, 시간대, 예약 기간과 최대 인원을 포함한다.
- 자동화: `AdminSettingsAndAvailabilityIntegrationTests.publicBookingContextListsOnlyActiveBranchesWithPrivacyPolicy`

### TF-AV-002 · 영업시간·인원·차단 반영

- 우선순위: P0
- 절차: 수용 인원, 영업시간과 반열림 차단 구간을 다르게 구성해 가용성을 조회한다.
- 기대: 이용 구간 전체가 영업시간 안이고 수용 가능한 활성 테이블이 있으며 차단과 겹치지 않는 슬롯만 노출한다.
- 자동화: `AdminSettingsAndAvailabilityIntegrationTests.availabilityUsesCapacityBusinessHoursAndHalfOpenBlocks`

### TF-AV-003 · 예약 기간과 비활성 식당 경계

- 우선순위: P1
- 절차: 최대 예약 가능일 다음 날짜와 비활성 식당의 알려진 지점 ID를 직접 조회한다.
- 기대: 둘 다 `422 BOOKING_POLICY_VIOLATION`이며 비활성 지점은 booking context에서도 보이지 않는다.
- 자동화: `availabilityRejectsDateOutsideBranchHorizon`, `inactiveRestaurantRejectsDirectAvailabilityRequests`

### TF-AV-004 · 조회와 생성 사이 좌석 경합

- 우선순위: P0
- 절차: 마지막 테이블이 가용하다는 조회 뒤 서로 다른 고객이 같은 슬롯을 동시에 생성한다.
- 기대: 조회 결과는 예약 보장이 아니며 생성은 한 건만 성공하고 다른 요청은 409다.
- 자동화: `ReservationCreationIntegrationTests.concurrentRequestsForLastTableAllowOnlyOneReservation`

## 4. 예약 생성, 멱등성과 중복

### TF-RC-001 · 정상 생성과 개인정보 암호화

- 우선순위: P0
- 절차: 유효한 동의, UUID 멱등 키와 예약 본문을 전송한다.
- 기대: 201, `PENDING`, 예약 번호와 관리 토큰을 반환한다. DB에는 이름·전화번호·토큰 원문이 없고 최초 상태 이력 한 건이 있다.
- 자동화: `ReservationCreationIntegrationTests.publicCreationEncryptsPersonalDataAndWritesInitialHistory`

### TF-RC-002 · 같은 멱등 키와 같은 본문

- 우선순위: P0
- 절차: 같은 키와 본문을 순차 또는 동시에 두 번 전송한다.
- 기대: 같은 예약 번호와 토큰을 재현하고 DB 예약은 한 건이다.
- 자동화: `sameIdempotencyKeyAndBodyReplaysSameReservationAndToken`, `concurrentRetriesWithSameKeyReturnOneReservationAndSameResponse`

### TF-RC-003 · 같은 멱등 키와 다른 본문

- 우선순위: P0
- 절차: 첫 성공 뒤 같은 키로 인원이나 고객 정보를 바꿔 전송한다.
- 기대: `409 IDEMPOTENCY_KEY_REUSED`이며 기존 예약은 바뀌지 않는다.
- 자동화: `ReservationCreationIntegrationTests.sameIdempotencyKeyWithDifferentBodyIsRejected`

### TF-RC-004 · 같은 고객의 겹치는 이용 구간

- 우선순위: P0
- 사전조건: 같은 지점에 다른 가용 테이블이 있다.
- 절차: 전화번호 표기만 달리해 첫 예약과 겹치는 이용 구간을 생성한다.
- 기대: 정규화된 지문이 같으므로 `409 DUPLICATE_CUSTOMER_RESERVATION`이다.
- 자동화: `overlappingReservationForSamePhoneIsRejectedEvenWhenAnotherTableExists`

### TF-RC-005 · 이용 종료와 정리 시간 경계

- 우선순위: P0
- 사전조건: 테이블 두 개와 첫 예약 종료 뒤 15분 정리 시간이 있다.
- 절차: 같은 고객이 첫 예약 `endsAt`과 정확히 같은 시각에 다시 예약한다.
- 기대: 고객 이용 구간은 겹치지 않아 성공한다. 첫 테이블은 정리 중이므로 두 번째 테이블을 배정한다.
- 자동화: `sameCustomerCanBookAtServiceEndWhileCleanupStillOccupiesTheFirstTable`

### TF-RC-006 · 마지막 테이블 동시 생성

- 우선순위: P0
- 절차: 서로 다른 고객이 마지막 한 테이블의 같은 구간을 동시에 생성한다.
- 기대: 201 한 건과 409 한 건이며 활성 예약은 하나뿐이다.
- 자동화: `concurrentRequestsForLastTableAllowOnlyOneReservation`

### TF-RC-007 · 취소와 동일 시간 재예약 경합

- 우선순위: P0
- 절차: 기존 예약 취소와 같은 고객·같은 시간의 신규 예약을 동시에 실행한다.
- 기대: 취소는 성공한다. 신규 요청은 커밋 순서에 따라 201 또는 중복 409일 수 있지만 최종 활성 중복은 0건 또는 1건이다.
- 자동화: `ReservationManagementIntegrationTests.concurrentCancellationAndReplacementNeverLeaveOverlappingActiveReservations`

### TF-RC-008 · 비활성 식당 생성 차단

- 우선순위: P0
- 절차: 비활성 식당의 알려진 지점 ID로 예약을 생성한다.
- 기대: `422 BOOKING_POLICY_VIOLATION`이며 예약과 이력은 생성되지 않는다.
- 자동화: `ReservationCreationIntegrationTests.inactiveRestaurantRejectsNewReservationsForKnownBranchId`

## 5. DB 시간 구간 제약

### TF-DB-001 · 점유 종료와 다음 시작이 같은 경우

- 우선순위: P0
- 절차: 같은 테이블의 다음 예약을 기존 `occupiedUntil`과 정확히 같은 시각에 저장한다.
- 기대: `[start, end)` 반열림 구간이므로 둘 다 성공한다.
- 자동화: `ReservationSchemaIntegrationTests.adjacentReservationAtOccupiedUntilDoesNotOverlap`

### TF-DB-002 · 정리 시간 점유 겹침

- 우선순위: P0
- 절차: 이용은 끝났지만 `occupiedUntil` 전인 구간에 같은 테이블 예약을 저장한다.
- 기대: `ex_reservation_table_occupancy`가 거부한다.
- 자동화: `tableOccupancyConstraintRejectsBufferOverlap`

### TF-DB-003 · 고객 지문 겹침

- 우선순위: P0
- 절차: 다른 테이블에 같은 지점·연락처 지문·겹치는 이용 구간을 저장한다.
- 기대: `ex_reservation_customer_overlap`이 거부한다.
- 자동화: `customerOverlapConstraintRejectsDifferentTableAtSameBranch`

## 6. 고객 조회, 변경과 취소

### TF-CM-001 · 예약 번호와 토큰 조합 조회

- 우선순위: P0
- 절차: 올바른 조합, 잘못된 토큰, 존재하지 않는 번호를 각각 조회한다.
- 기대: 올바른 조합만 200이다. 나머지는 같은 404 코드와 상세를 사용해 실패 원인을 구분할 수 없다.
- 자동화: `ReservationManagementIntegrationTests.tokenLookupReturnsPublicReservationAndDoesNotRevealWhichCredentialFailed`

### TF-CM-002 · 변경의 원자적 재배정과 버전

- 우선순위: P0
- 절차: 기존 테이블의 새 시간대에 차단을 넣고 고객이 시간·인원을 변경한 뒤 이전 버전으로 재요청한다.
- 기대: 대체 테이블로 원자적 재배정하고 버전이 증가한다. 이전 버전은 `409 RESERVATION_VERSION_CONFLICT`다.
- 자동화: `changeReallocatesAtomicallyAndCancellationReleasesCapacity`

### TF-CM-003 · 고객 변경·취소 마감

- 우선순위: P1
- 절차: 시작 3시간 전 경계와 그 이후에 변경·취소를 요청한다.
- 기대: 정확한 허용 경계는 성공하고 마감 뒤 요청은 정책 오류로 거부한다.
- 자동화: `ReservationTransitionPolicyTests.customerCancellationAllowsExactCutoffButRejectsAfterIt`, `customerCannotChangeOrCancelAfterBranchCutoff`

### TF-CM-004 · 취소 후 좌석 재사용

- 우선순위: P0
- 절차: 고객 취소 뒤 같은 고객이 같은 슬롯을 다시 생성한다.
- 기대: 취소 이력이 한 번 기록되고 새 예약이 성공한다.
- 자동화: `changeReallocatesAtomicallyAndCancellationReleasesCapacity`

### TF-CM-005 · 같은 버전의 동시 변경

- 우선순위: P0
- 절차: 같은 예약을 서로 다른 시간으로 동일 `expectedVersion`을 사용해 동시에 변경한다.
- 기대: 하나만 성공하고 다른 요청은 `409 RESERVATION_VERSION_CONFLICT`다.
- 자동화: `concurrentChangesWithSameVersionAllowOnlyOneWinner`

## 7. 관리자 운영과 상태 전이

### TF-AD-001 · 목록·대시보드와 개인정보 최소화

- 우선순위: P0
- 절차: 날짜·상태·검색·페이징으로 목록과 현황을 조회한다.
- 기대: 건수와 슬롯 현황이 일치하고 전화번호 전체를 노출하지 않는다. 무인증은 401이다.
- 자동화: `AdminReservationOperationsIntegrationTests.listAndDashboardExposePagedOperationsDataWithoutFullPhoneNumber`

### TF-AD-002 · 정상 운영 lifecycle

- 우선순위: P1
- 절차: `PENDING → CONFIRMED → SEATED → COMPLETED`를 정책 시각에 맞춰 수행한다.
- 기대: 각 전이가 성공하고 버전과 상태 이력이 순서대로 증가한다.
- 자동화: `administratorCanCompleteTheOperationalLifecycleAndMarkNoShow`

### TF-AD-003 · 노쇼·착석·완료 시간 경계

- 우선순위: P1
- 절차: 노쇼 유예 전후, 지점 현지 예약일 밖 착석, 이용 종료 전후 완료를 시도한다.
- 기대: 유예 전 노쇼, 예약일 밖 착석과 이른 완료는 거부한다.
- 자동화: `ReservationTransitionPolicyTests`의 `noShowRequiresGracePeriodAndAdminOrSystemActor`, `seatingIsRestrictedToReservationLocalDate`, `completionRequiresServiceEndAndTerminalStatesCannotTransition`

### TF-AD-004 · 마감 후 관리자 취소 사유

- 우선순위: P1
- 절차: 마감 뒤 관리자 취소를 사유 없이, 이어서 사유와 함께 요청한다.
- 기대: 사유 없는 요청은 거부하고 사유가 있는 요청만 성공해 이력에 남는다.
- 자동화: `lateAdminCancellationRequiresReason`, `transitionEnforcesVersionRulesAndLateCancellationReason`

### TF-AD-005 · 잘못된 전이와 오래된 버전

- 우선순위: P0
- 절차: `PENDING → SEATED`, 오래된 버전, 같은 버전의 동시 전이를 요청한다.
- 기대: 잘못된 전이와 버전 충돌을 구분하며 동시 전이는 한 요청만 성공한다.
- 자동화: `transitionEnforcesVersionRulesAndLateCancellationReason`, `concurrentTransitionsWithOneVersionHaveOneWinner`

## 8. 관리자 설정

### TF-ST-001 · 식당부터 차단까지 초기 구성

- 우선순위: P1
- 절차: 식당, 지점, 테이블, 영업시간과 차단을 순서대로 생성한다.
- 기대: 각 자원의 조회 계약과 버전이 일치하고 공개 가용성에 반영된다.
- 자동화: `AdminSettingsAndAvailabilityIntegrationTests.adminCanCreateRestaurantBranchTableHoursAndBlock`

### TF-ST-002 · 영업시간 중복과 전체 교체

- 우선순위: P1
- 절차: 겹치는 구간을 저장하고 이후 같은 시작 시각으로 전체 교체한다.
- 기대: 중복 입력은 422이고 전체 교체는 기존 행 충돌 없이 성공한다.
- 자동화: `overlappingBusinessHoursAreRejected`, `businessHoursCanBeReplacedWithTheSameStartTime`

### TF-ST-003 · 설정 예상 버전과 미래 예약 수용력

- 우선순위: P0
- 절차: 예상 버전을 누락하거나 미래 예약 인원보다 작게 테이블 용량을 낮춘다.
- 기대: 누락은 validation 오류, 용량 축소는 `409 TABLE_CAPACITY_CONFLICT`다.
- 자동화: `updateRequiresExpectedVersion`, `tableCapacityCannotBeReducedBelowFutureReservationPartySize`

### TF-ST-004 · 지점 현지 시각과 DST

- 우선순위: P1
- 절차: 정상 현지 시각, 존재하지 않는 DST 시각, 중복되는 DST 시각으로 차단을 입력한다.
- 기대: 정상 값만 Instant로 변환하고 모호하거나 존재하지 않는 값은 제출 전에 거부한다.
- 자동화: `AdminSettingsPage.test.tsx`의 시간대 변환 테스트

## 9. 공통 API, 인증과 개인정보

### TF-SE-001 · Problem Details와 요청 ID

- 우선순위: P0
- 절차: malformed JSON, validation 실패, not-found와 사용자 지정 요청 ID를 호출한다.
- 기대: RFC 9457 형식, 안정적 코드와 request ID를 사용하고 SQL·스택·개인정보를 노출하지 않는다.
- 자동화: `CommonApiContractTests`

### TF-SE-002 · 관리자 401·403·200 분리

- 우선순위: P0
- 절차: 무인증, 비관리자 역할, 관리자 역할로 같은 API를 호출한다.
- 기대: 각각 401, 403, 200이다.
- 자동화: `administratorRoutesDistinguishMissingAuthenticationAndMissingRole`, 스테이징 UAT CLI

### TF-SE-003 · OIDC 역할과 설정 실패 폐쇄

- 우선순위: P0
- 절차: 승인·무관 역할 JWT와 누락·오타 프런트 OIDC 설정을 사용한다.
- 기대: 승인 역할만 관리자 권한을 얻고 잘못된 모드는 Basic으로 조용히 강등되지 않는다.
- 자동화: `SecurityConfigTests`, `frontend/src/auth/oidcClient.test.ts`

### TF-SE-004 · rate limit과 Actuator 경계

- 우선순위: P0
- 절차: 공개 생성·관리 경로 한도를 초과하고 health·Prometheus를 인증별로 호출한다.
- 기대: 경로별 429를 반환하고 health만 공개하며 Prometheus는 인증·내부 경계를 요구한다.
- 자동화: `PublicRateLimitIntegrationTests`, `PublicApiRateLimitFilterTests`, `CommonApiContractTests`, `staging-smoke.test.mjs`

### TF-SE-005 · 개인정보 보존 만료와 키 회전

- 우선순위: P0
- 절차: 보존 기간이 지난 종료 예약을 파기하고 이전 AES 키 암호문을 활성 키로 재암호화한다.
- 기대: 고객 원문·지문·토큰을 복구할 수 없고 새 키로 암호문을 읽을 수 있다.
- 자동화: `ReservationRetentionIntegrationTests`, `ReservationEncryptionRotationIntegrationTests`, `ReservationCryptoServiceTests`

## 10. 프런트엔드 흐름

### TF-UI-001 · 예약 정보 일회성 표시와 비저장

- 우선순위: P0
- 절차: 고객 예약을 만들고 예약 번호·관리 토큰 표시와 브라우저 저장소를 검사한다.
- 기대: 성공 화면 메모리에만 한 번 표시하며 localStorage, sessionStorage와 URL에 남지 않는다.
- 자동화: `ReservationBookingPage.test.tsx`

### TF-UI-002 · 동일 요청 재시도 키 유지

- 우선순위: P0
- 절차: 실패 뒤 입력을 바꾸지 않고 다시 제출한다.
- 기대: 두 API 호출이 같은 멱등 키를 사용한다.
- 자동화: `reuses the idempotency key when the same reservation request is retried`

### TF-UI-003 · 요청 변경 후 새 멱등 키

- 우선순위: P0
- 절차: 실패한 제출 뒤 이름 등 본문을 바꾸고 다시 제출한다.
- 기대: 첫 요청과 다른 새 멱등 키를 사용한다.
- 자동화: `uses a new idempotency key after the failed request payload changes`

### TF-UI-004 · 파괴 작업 2단계 확인

- 우선순위: P1
- 절차: 고객 취소, 관리자 취소·노쇼, 차단 삭제를 한 번과 두 번 실행한다.
- 기대: 첫 동작은 확인 상태만 표시하고 두 번째 확정만 API를 호출한다.
- 자동화: `ReservationManagePage.test.tsx`, `AdminReservationPage.test.tsx`, `AdminSettingsPage.test.tsx`

### TF-UI-005 · OIDC 탭 세션과 안전한 복귀

- 우선순위: P0
- 절차: 로그인·로그아웃 callback, 새로고침과 외부 return URL을 시험한다.
- 기대: 같은 탭 sessionStorage만 사용하고 외부 복귀를 거부하며 로그아웃 뒤 상태를 제거한다.
- 자동화: `AdminAuthProvider.test.tsx`, `OidcCallbackPage.test.tsx`, `oidcClient.test.ts`
- 수동 UAT: 실제 IdP 왕복과 별도 탭·프로필 격리

## 11. 배포와 스테이징

### TF-OP-001 · 운영 환경 사전검증

- 우선순위: P0
- 절차: 정상 구성, 예제값, origin 불일치, 재사용 키와 잘못된 rotation을 검사한다.
- 기대: 안전한 구성만 통과하고 비밀값을 출력하지 않는다.
- 자동화: `deployment-preflight.test.mjs`

### TF-OP-002 · 읽기 전용 smoke

- 우선순위: P0
- 절차: 프런트, SPA 딥링크, health, 보안 헤더, booking context, request ID와 Actuator 차단을 확인한다.
- 기대: 데이터 생성 없이 통과하고 실패한 게이트를 정확히 출력한다.
- 자동화: `staging-smoke.test.mjs`

### TF-OP-003 · 쓰기 승인 없는 UAT 차단

- 우선순위: P0
- 절차: `--confirm-write` 없이 UAT CLI를 실행한다.
- 기대: 네트워크 요청을 한 건도 보내지 않는다.
- 자동화: `staging-uat.test.mjs`

### TF-OP-004 · 합성 예약 lifecycle과 정리

- 우선순위: P0
- 절차: 승인 토큰과 합성 고객으로 권한, 가용성, 생성, 동일 재시도, 관리 조회와 취소를 실행한다.
- 기대: `cleanup=cancelled`이며 출력에 비밀값과 전화번호가 없다. 중간 실패 시 관리자 보조 정리 또는 비민감 수동 정리 표식을 남긴다.
- 자동화: `staging-uat.test.mjs`
- 실제 수행: `TableFlow_스테이징_UAT_체크리스트.md`

## 12. 스테이징 수동 UAT

다음 항목은 로컬 mock이나 API 테스트만으로 릴리스 판정을 완료하지 않는다.

- 실제 IdP Authorization Code + PKCE 로그인·로그아웃 왕복
- 관리자·비관리자 계정의 실제 200·403 권한 분리
- 별도 탭과 브라우저 프로필의 token 비공유
- 실제 영업 정책과 지점 시간대·DST 검토
- 예약 번호와 관리 토큰의 URL·저장소·로그 비노출
- 관리자 보드의 실제 날짜·상태 집계와 마스킹
- 애플리케이션·프록시·관측 로그의 개인정보·token 비노출 검색
- 인그레스/WAF 전역 rate limit, 경보와 대시보드
- DB 백업, 격리 복구와 키 회전 훈련
- 테스트 예약과 단기 token의 실행 후 정리·회수

수행자는 `TableFlow_스테이징_UAT_체크리스트.md`에 환경, 실행 ID, request ID, 비민감 증적 링크와 판정을 기록한다.

## 13. 실행 명령

전체 로컬 게이트:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

개별 게이트:

```powershell
.\backend\gradlew.bat -p backend test
npm.cmd run check --prefix frontend
node --test .\scripts\staging-smoke.test.mjs
node --test .\scripts\deployment-preflight.test.mjs
node --test .\scripts\staging-uat.test.mjs
```

스테이징 검증:

```powershell
node .\scripts\staging-smoke.mjs --base-url https://staging.example.com
node .\scripts\staging-uat.mjs --base-url https://staging.example.com --confirm-write
```

## 14. 릴리스 판정

- P0 자동화가 모두 통과한다.
- OpenAPI 생성 타입과 저장소 파일이 일치한다.
- 백엔드·프런트·운영 컨테이너 빌드가 통과한다.
- 사전검증과 읽기 전용 smoke가 통과한다.
- 쓰기 UAT 예약이 취소되거나 승인된 절차로 정리된다.
- 실제 IdP·권한·브라우저·로그·백업 수동 UAT가 통과한다.
- 미해결 결함은 책임자, 기한과 완화책을 기록한다.

2026-08-17 로컬 전체 게이트 기준으로 백엔드 54개, 프런트 30개, 운영 스크립트 15개 테스트와 OpenAPI·lint·TypeScript·Vite 빌드가 통과했다. 운영 컨테이너와 실제 스테이징 수동 UAT는 배포 환경에서 별도 증적을 남긴다.
