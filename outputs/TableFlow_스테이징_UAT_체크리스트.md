# TableFlow 스테이징 UAT 체크리스트

> 용도: 실제 IdP와 승인된 테스트 데이터가 연결된 스테이징 배포의 인수 기록
> 원칙: 비밀번호, access/refresh token, 암호화 키, 고객명·전화번호, 예약 관리 토큰을 이 문서에 기록하지 않는다.

## 1. 실행 정보

- [ ] 스테이징 서비스 URL:
- [ ] 애플리케이션 버전 또는 Git commit:
- [ ] 실행 일시와 시간대:
- [ ] 실행자:
- [ ] 배포 작업 또는 변경 요청 링크:
- [ ] DB 백업 식별자와 복구 훈련 증적 링크:
- [ ] 개인정보 정책 버전과 보존 기간 승인 링크:
- [ ] 테스트 계정·연락처 사용 승인 링크:

체크리스트에는 안전한 증적 링크만 남긴다. 외부 증적에도 인증정보와 개인정보 원문이 포함되지 않았는지 확인한다.

## 2. 배포 전 자동 게이트

- [ ] `node scripts/deployment-preflight.mjs --env-file .env`가 통과했다.
- [ ] `node scripts/deployment-preflight.mjs --env-file .env --check-oidc`가 통과했다.
- [ ] `docker compose --env-file .env -f compose.prod.yaml config --quiet`가 통과했다.
- [ ] `powershell -ExecutionPolicy Bypass -File scripts/verify.ps1` 또는 동일 CI가 통과했다.
- [ ] 배포 직전 백업과 격리 복구 검사가 성공했다.
- [ ] Secret Manager 주입값과 배포 로그에 비밀값이 출력되지 않았다.

자동 게이트 증적 링크:

## 3. 배포와 읽기 전용 smoke

- [ ] 모든 컨테이너 또는 workload가 healthy/ready 상태다.
- [ ] Flyway migration과 JPA schema validation이 성공했다.
- [ ] `node scripts/staging-smoke.mjs --base-url <HTTPS_ORIGIN>`이 통과했다.
- [ ] smoke request ID로 프런트와 백엔드 로그를 연결할 수 있다.
- [ ] `/actuator/prometheus`와 그 밖의 비공개 Actuator endpoint가 외부에 노출되지 않는다.

Smoke request ID와 비민감 증적 링크:

승인된 테스트 token과 합성 연락처를 프로세스 환경에 주입한 뒤 쓰기 UAT를 수행한다.

- [ ] `node scripts/staging-uat.mjs --base-url <HTTPS_ORIGIN> --confirm-write`가 통과했다.
- [ ] 결과의 `cleanup`이 `cancelled`이며 자동 정리 경고가 있으면 관리자 보드에서 실제 취소 상태를 재확인했다.
- [ ] 출력된 UAT request ID로 권한·생성·조회·취소 요청을 개인정보 없이 추적할 수 있다.
- [ ] 실행 뒤 `TABLEFLOW_UAT_*` 프로세스 환경 변수를 제거하고 단기 token을 회수했다.

UAT run 앞 8자리, request ID와 비민감 증적 링크:

## 4. OIDC와 관리자 권한

- [ ] 비로그인 요청으로 `/api/admin/restaurants`에 접근하면 401이다.
- [ ] 관리자 역할이 없는 승인된 테스트 계정은 같은 API에서 403이다.
- [ ] 관리자 역할이 있는 승인된 테스트 계정은 같은 API에서 200이다.
- [ ] `/admin/reservations` 로그인에서 Authorization Code + PKCE 왕복과 `/auth/callback` 처리가 성공한다.
- [ ] 새로고침 후 같은 탭의 관리자 세션이 유지된다.
- [ ] 별도 탭이나 브라우저 프로필에 기존 탭의 token이 자동 공유되지 않는다.
- [ ] 로그아웃 callback 뒤 관리자 token과 PKCE 상태가 해당 탭에서 제거된다.
- [ ] callback URI, post-logout URI, audience, scope와 역할 claim이 승인된 IdP 등록값과 일치한다.

권한별 결과와 비민감 증적 링크:

## 5. 신규 환경 매장 구성

이미 구성된 환경이면 변경하지 말고 기존 설정의 검토 결과만 기록한다.

- [ ] 식당과 지점이 활성 상태이며 지점 IANA 시간대가 실제 영업 지역과 일치한다.
- [ ] 활성 테이블 이름과 수용 인원이 실제 테스트 정책과 일치한다.
- [ ] 주간 영업시간에 중복 또는 의도하지 않은 공백이 없다.
- [ ] 예약 간격, 이용·정리 시간, 사전예약·변경·취소 마감과 최대 인원이 승인값과 일치한다.
- [ ] 휴무·점검 차단 시간이 지점 현지 시각 기준으로 정확하다.
- [ ] 공개 booking context에는 활성 지점만 노출된다.

설정 검토자와 승인 링크:

## 6. 고객 예약 lifecycle

승인된 합성 이름과 테스트 전용 전화번호만 사용한다. 예약 관리 토큰은 테스트 중 필요한 화면에만 입력하고 문서, URL, 로그, 브라우저 저장소에 복사하지 않는다.

쓰기 UAT CLI가 API lifecycle을 통과해도 아래 브라우저 표시·저장소·사용성 항목은 직접 확인한다.

- [ ] 지점·날짜·인원별 예약 가능 시간이 영업시간, 수용 인원과 차단 정책을 반영한다.
- [ ] 개인정보 안내 버전과 보존 기간을 확인하고 명시적으로 동의한 뒤 예약을 생성한다.
- [ ] 생성 직후 예약 번호와 관리 토큰이 한 번만 표시된다.
- [ ] 새로고침 또는 화면 이탈 뒤 관리 토큰이 다시 표시되지 않는다.
- [ ] 예약 번호와 관리 토큰으로 예약을 조회한다.
- [ ] 허용된 마감 전 시간 또는 인원 변경이 성공하고 최신 버전이 표시된다.
- [ ] 같은 슬롯 경합이나 이미 점유된 슬롯은 409로 거부되고 대체 가용성을 다시 조회한다.
- [ ] 마감 전 고객 취소가 성공하고 다시 조회한 상태가 `CANCELLED`다.
- [ ] 테스트 중 생성한 모든 예약의 정리 상태를 확인했다.

테스트 예약은 전체 번호나 token 대신 승인된 증적 시스템의 비민감 실행 ID로 연결한다.

## 7. 관리자 운영 lifecycle

고객 취소 시나리오와 별도의 승인된 테스트 예약을 사용한다.

- [ ] 운영 보드의 날짜·상태 필터, 상태 건수와 슬롯 현황이 테스트 예약을 반영한다.
- [ ] 예약 상세는 고객명과 전화번호 끝 네 자리만 필요한 범위로 표시하고 상태 이력을 제공한다.
- [ ] `REQUESTED → CONFIRMED → SEATED → COMPLETED` 전이가 정책대로 성공한다.
- [ ] 전이마다 행위자, 이전·다음 상태, 사유와 시각이 이력에 남는다.
- [ ] 오래된 `expectedVersion` 변경은 409이며 새 데이터를 조회한 뒤에만 재시도한다.
- [ ] 허용되지 않은 역방향 전이와 이른 `NO_SHOW` 처리는 거부된다.

운영 lifecycle 증적 링크:

## 8. 개인정보·보안·관측

- [ ] 예약 번호와 관리 토큰이 URL, localStorage, sessionStorage에 남지 않는다.
- [ ] 애플리케이션·프록시·관측 로그에서 테스트 이름, 전화번호, token과 암호문 원문이 검색되지 않는다.
- [ ] CSP, 클릭재킹, MIME, Referrer 보안 헤더가 smoke 결과와 일치한다.
- [ ] 401, 403, 409, 429와 5xx를 구분해 경보·대시보드에서 관측할 수 있다.
- [ ] 인그레스 또는 WAF의 전역 rate limit이 활성화되어 있다.
- [ ] DB 자동 백업, 보존, 암호화와 복구 경보가 활성화되어 있다.
- [ ] 예약 AES/HMAC 키가 DB 자격증명과 분리된 Secret Manager 경계에 있다.

보안·관측 증적 링크:

## 9. 판정과 정리

- [ ] 테스트 데이터 정리를 완료했다.
- [ ] 발견된 결함에 심각도, 담당자와 처리 기한을 지정했다.
- [ ] 롤백 조건과 최종 판단 시각을 확인했다.
- [ ] 제품 책임자 승인:
- [ ] 운영 책임자 승인:
- [ ] 보안·개인정보 책임자 승인:

최종 판정: `GO / CONDITIONAL GO / NO-GO`

판정 사유와 미해결 항목:
