# TableFlow 운영 런북

> 상태: MVP M11 릴리스 리뷰 보완 운영 기준선
> 기준일: 2026-08-17
> 대상: `compose.prod.yaml` 또는 같은 환경 변수를 사용하는 배포 환경

## 1. 배포 판정 기준

운영 배포 전 다음 조건을 모두 만족해야 한다.

- PostgreSQL 백업이 완료되고 격리된 DB에서 최근 복구 훈련이 성공했다.
- OIDC issuer와 API audience가 실제 운영 값이며, 관리자 토큰의 역할 claim에 설정된 관리자 역할이 들어 있다.
- IdP에 public SPA client, PKCE, 로그인·로그아웃 callback URI와 프런트 origin CORS가 등록되어 있다.
- DB, 예약 AES 키, 예약 HMAC 키는 서로 다른 난수이며 Secret Manager에서 주입한다.
- `CORS_ALLOWED_ORIGIN`은 실제 HTTPS 프런트 origin 한 개로 제한한다.
- 개인정보 보존 기간은 사업 책임자와 개인정보 담당자가 승인한 값이다. 코드 기본값 365일은 승인 전 가정값이다.
- `/privacy`의 수집 항목·목적·보존·동의 거부 안내와 정책 버전이 승인된 개인정보 처리 기준과 일치한다.
- `/reservations/new`에서 생성된 예약 번호와 관리 토큰이 URL, localStorage, sessionStorage와 애플리케이션 로그에 남지 않는다.
- 인그레스나 WAF에 전역 rate limit이 있다. 애플리케이션의 고정 구간 제한은 인스턴스별 보조 방어선이다.
- `powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1`와 두 Compose 구성 검사가 통과한다.
- `deployment-preflight.mjs`의 로컬 규칙과 OIDC 온라인 discovery 검사가 모두 통과한다.

운영 프런트의 관리자 화면은 Authorization Code + PKCE로 IdP에 리다이렉트하며 access token과 PKCE 상태를 탭의 `sessionStorage`에 보관한다. IdP 등록값과 빌드 설정이 다르거나 discovery·token endpoint가 브라우저 CORS를 허용하지 않으면 운영 UI 공개 배포 판정을 보류한다.

## 2. 필수 설정

`.env.example`을 값 목록으로 사용하되 실제 비밀을 파일이나 저장소에 커밋하지 않는다.

OIDC 설정:

- `OIDC_ISSUER_URI`: 토큰 발급자의 정확한 issuer.
- `OIDC_AUDIENCE`: 이 API를 나타내는 audience. 다른 서비스용 토큰을 거부한다.
- `OIDC_ROLES_CLAIM`: 역할 목록 또는 공백·쉼표 구분 역할 문자열 claim.
- `OIDC_ADMIN_ROLE`: `ROLE_ADMIN`으로 매핑할 IdP 역할.
- `OIDC_PRINCIPAL_CLAIM`: 감사 이력의 관리자 식별자에 사용할 claim. 기본값은 `sub`다.
- `OIDC_SPA_CLIENT_ID`: secret을 갖지 않는 public SPA client ID.
- `OIDC_SPA_SCOPE`: API access token 발급에 필요한 scope를 포함한다. refresh token 정책이 승인된 IdP에서만 `offline_access` 추가를 검토한다.
- `OIDC_SPA_RESOURCE`: IdP가 RFC 8707 resource indicator로 API audience를 선택할 때만 설정한다.
- `APP_PUBLIC_URL`: callback을 받을 HTTPS 서비스 origin. 경로와 마지막 슬래시는 넣지 않는다.
- `OIDC_CSP_CONNECT_SRC`: discovery·token 요청을 허용할 issuer origin 한 개.

IdP client에는 `${APP_PUBLIC_URL}/auth/callback`을 로그인 redirect URI로, `${APP_PUBLIC_URL}/auth/logout-callback`을 post-logout redirect URI로 정확히 등록한다. Authorization Code와 PKCE S256을 허용하고 implicit grant와 client secret은 사용하지 않는다.

예약 보안 설정:

- `RESERVATION_ENCRYPTION_KEY`: AES-256-GCM 현재 키의 Base64 값.
- `RESERVATION_ENCRYPTION_KEY_ID`: 현재 키를 구분하는 영문·숫자·밑줄·하이픈 ID.
- `RESERVATION_PREVIOUS_ENCRYPTION_KEY`와 ID: 키 교체 기간에만 설정한다.
- `RESERVATION_HMAC_KEY`: 전화번호 지문, 멱등 요청 지문, 관리 토큰 검증에 쓰는 별도 32바이트 키.

요청 제한 기본값은 예약 생성 10회/분, 토큰 기반 조회·변경·취소 30회/분이다. 이 값은 소스 IP와 API 그룹별이며 각 애플리케이션 인스턴스 메모리에서 계산된다. 프런트 Nginx는 직접 연결된 클라이언트 주소로 `X-Forwarded-For`를 덮어쓰며, 그 앞에 별도 인그레스가 있다면 신뢰할 프록시와 실제 클라이언트 IP 전달 규칙을 명시적으로 구성한다. 다중 인스턴스 전체 한도와 공격 트래픽 차단은 인그레스, WAF 또는 분산 rate limiter가 담당해야 한다.

## 3. 표준 배포

구성을 먼저 검증한다.

```powershell
node .\scripts\deployment-preflight.mjs --env-file .env
node .\scripts\deployment-preflight.mjs --env-file .env --check-oidc
docker compose --env-file .env -f compose.prod.yaml config --quiet
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

사전검증은 셸 환경 변수를 `.env`보다 우선해 Compose와 같은 주입 순서를 사용한다. 예제 도메인과 placeholder, HTTPS origin 불일치, 16자 미만 DB 비밀번호, 형식이 잘못된 32바이트 Base64 키, AES/HMAC 키 재사용, 잘못된 보존·batch·rate limit 범위와 키 교체 조건을 실패 처리한다. 결과에는 비밀번호와 키를 출력하지 않는다. 온라인 검사는 issuer가 정확히 일치하고 authorization/token/JWKS endpoint가 HTTPS이며 PKCE S256을 지원하는지 확인한다.

배포 직전 백업을 만든 뒤 이미지를 빌드하고 기동한다.

```powershell
docker compose --env-file .env -f compose.prod.yaml up --build -d
docker compose --env-file .env -f compose.prod.yaml ps
node .\scripts\staging-smoke.mjs --base-url $env:APP_PUBLIC_URL
```

자동 smoke test는 다음을 확인한다.

- `/healthz`가 프런트 컨테이너 상태를 반환한다.
- `/actuator/health`가 백엔드 상태를 반환한다.
- `/reservations/new`, `/privacy`, `/admin/settings` 딥링크가 SPA와 필수 CSP·클릭재킹·MIME·Referrer 보안 헤더를 반환한다.
- 공개 booking context가 정책 버전·보존 기간·활성 지점 계약을 만족하고 `X-Request-Id`를 전파한다.
- `/actuator/prometheus`를 포함한 공개하지 않은 `/actuator/**`가 404이며 지표 본문을 노출하지 않는다.

자동 검사는 데이터 변경과 개인정보 전송을 피하기 위해 예약을 생성하지 않는다. 이어서 다음 수동 인수 시나리오를 확인한다.

승인된 테스트 계정과 합성 연락처를 준비한 환경에서는 쓰기 API 인수를 먼저 자동화할 수 있다. access token과 연락처는 명령행 인자로 전달하거나 파일에 기록하지 않고 단기 프로세스 환경으로 주입한다.

```powershell
node .\scripts\staging-uat.mjs --base-url $env:APP_PUBLIC_URL --confirm-write
```

필수값은 `TABLEFLOW_UAT_ADMIN_TOKEN`, `TABLEFLOW_UAT_GUEST_NAME`, `TABLEFLOW_UAT_GUEST_PHONE`이며 선택값 `TABLEFLOW_UAT_NON_ADMIN_TOKEN`으로 403 역할 경계도 확인한다. 특정 지점과 날짜는 `TABLEFLOW_UAT_BRANCH_ID`, `TABLEFLOW_UAT_DATE` 또는 CLI 옵션으로 제한할 수 있다. 명령은 취소 마감보다 충분히 먼 슬롯만 선택하고 같은 멱등 요청의 동일 응답, 관리 token 조회, 고객 취소와 취소 상태를 확인한다. 중간 실패 시 고객 token으로 정리하고, 생성 응답을 읽지 못하면 고유 run 표식과 관리자 권한으로 PENDING 예약을 찾아 취소한다. 모든 자동 정리가 실패하면 run 앞 8자리만 출력하므로 관리자 보드에서 즉시 찾아 정리해야 한다.

쓰기 UAT 결과에는 token, 전화번호와 전체 예약 번호를 출력하지 않는다. 실행 완료 후 현재 셸의 `TABLEFLOW_UAT_*` 변수를 제거하고 단기 token을 IdP에서 회수한다. API UAT는 이미 발급된 token을 사용하므로 실제 브라우저 PKCE 로그인·로그아웃은 아래 수동 시나리오에서 계속 확인한다.

- 유효한 관리자 토큰은 `/api/admin/restaurants`에서 200, 관리자 역할 없는 토큰은 403, 토큰이 없으면 401을 받는다.
- 운영 보드 로그인에서 IdP 왕복 후 `/auth/callback`이 처리되고, 새로고침 시 같은 탭의 세션이 유지되며 로그아웃 callback 뒤 token이 제거된다.
- 승인된 테스트 연락처로 공개 가용성 조회와 예약 생성·관리·취소 시나리오가 성공하고 생성한 테스트 예약은 운영 절차에 따라 정리한다.
- 애플리케이션 로그에 `Flyway` migration 성공과 JPA schema validation 성공이 있고 개인정보 원문은 없다.

수동 결과는 `outputs/TableFlow_스테이징_UAT_체크리스트.md`의 사본에 기록한다. 완료 기록에는 토큰, 비밀번호, 고객 연락처, 암호화 키를 붙이지 않고 승인된 증적 시스템의 링크나 비민감 식별자만 남긴다.

V6는 전환용 `todos` 테이블을 삭제한다. 배포 중 구버전 인스턴스의 핵심 예약 API에는 영향이 없지만 Todo API는 즉시 사용할 수 없어진다. M6 이전 애플리케이션으로 되돌려야 한다면 배포 전 백업을 사용하거나 V1과 V3 정의로 빈 호환 테이블을 먼저 복구하고 Flyway 호환성을 확인해야 한다.

### 3.1 신규 환경 매장 구성

빈 데이터베이스에 배포한 뒤 `/admin/settings`에서 다음 순서로 구성한다.

1. 관리자 인증 후 식당을 만들고 운영 상태를 확인한다.
2. 지점 이름·주소·IANA 시간대를 등록한다. 시간대 변경은 예약이 없는 초기 구성 단계에서 확정하는 것을 권장한다.
3. 최대 예약 인원보다 충분한 수용 인원의 활성 테이블을 등록한다.
4. 주간 영업 구간을 추가하고 예약 간격·이용시간·정리시간·사전예약·변경 마감 정책을 검토한다.
5. 휴무나 시설 점검이 있으면 지점 전체 또는 테이블별 예약 차단을 등록한다.
6. 공개 `/api/public/booking-context`에 활성 지점이 보이는지 확인한 뒤 예약 가능 시간과 승인된 테스트 예약 lifecycle을 검증한다.

식당이나 지점을 중지하면 새 고객 화면의 지점 목록에서 제외된다. 기존 예약 기록과 운영 처리는 유지되므로 중지 전에 향후 예약을 조회하고 고객 대응 계획을 확정한다.

## 4. 백업과 복구

초기 운영 목표는 매일 한 번 암호화된 논리 백업, 최근 7개 일간본과 4개 주간본 보관, 월 1회 복구 훈련이다. 실제 RPO·RTO와 보관 위치는 운영 책임자가 승인해야 한다.

Compose PostgreSQL의 예시 백업 절차:

```powershell
New-Item -ItemType Directory -Force .\backups
docker compose --env-file .env -f compose.prod.yaml exec -T postgres sh -c 'pg_dump --format=custom --no-owner --file=/tmp/tableflow.dump "$POSTGRES_DB"'
docker compose --env-file .env -f compose.prod.yaml cp postgres:/tmp/tableflow.dump .\backups\tableflow.dump
```

백업 파일은 애플리케이션 호스트와 분리된 암호화 저장소로 옮기고 접근 및 만료 정책을 적용한다. 예약 암호화 키와 HMAC 키는 DB 백업과 다른 권한 경계에서 백업해야 한다. DB만 복구하고 키를 잃으면 고객 정보를 복호화하거나 기존 관리 토큰을 검증할 수 없다.

복구 훈련은 운영 DB를 덮어쓰지 않고 격리된 DB에서 수행한다.

```powershell
docker compose --env-file .env -f compose.prod.yaml cp .\backups\tableflow.dump postgres:/tmp/tableflow-restore.dump
docker compose --env-file .env -f compose.prod.yaml exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" tableflow_restore_check'
docker compose --env-file .env -f compose.prod.yaml exec -T postgres sh -c 'pg_restore --no-owner -U "$POSTGRES_USER" -d tableflow_restore_check /tmp/tableflow-restore.dump'
```

복구 뒤 migration 이력, 핵심 테이블 수, 최근 예약 수, 배타 제약과 암호문 접두사를 확인한다. 테스트가 끝나면 격리 DB와 컨테이너 임시 파일만 명시적으로 삭제하고 훈련 일시, 백업 시각, 실제 RPO·복구 소요 시간, 검증 결과를 기록한다.

## 5. 개인정보 보존과 파기

매일 UTC 03:20의 보존 배치는 `COMPLETED`, `CANCELLED`, `NO_SHOW` 상태이며 종료 후 보존 기간이 지난 예약을 최대 설정 batch 크기만큼 처리한다.

파기 결과:

- 이름과 전화번호 암호문은 비식별 placeholder 암호문으로 교체한다.
- 전화번호 지문, 관리 토큰 해시, 요청 지문은 예약별 비가역 파기 해시로 교체한다.
- 끝 네 자리는 `0000`, `personal_data_erased_at`은 파기 시각이 된다.
- 예약 시간, 테이블, 상태와 상태 이력은 운영 통계 및 감사 레코드로 남는다.
- 기존 관리 토큰은 더 이상 유효하지 않다.

로그의 `reservation_personal_data_erased count=...`와 파기 대상 잔여 건수를 감시한다. 실패하면 키 접근, DB 잠금, 잘못된 보존 기간을 확인하고 원인을 해결한 뒤 배치를 다시 실행한다. 이 처리는 이미 파기된 행을 건너뛰므로 재시도할 수 있다.

## 6. AES 암호화 키 교체

키 교체 중에는 새 키와 직전 키 두 개만 동시에 읽는다.

1. Secret Manager에서 새 32바이트 AES 키와 새 고유 키 ID를 만든다.
2. 새 키를 현재 키로, 기존 키를 이전 키로 설정한다.
3. `RESERVATION_ENCRYPTION_ROTATION_ENABLED=true`로 한정 배포한다.
4. 신규 암호문의 `v2.<새 키 ID>.` 접두사와 기존 예약 조회가 모두 정상인지 확인한다.
5. UTC 03:40 배치의 `reservation_personal_data_reencrypted count=...` 로그를 감시한다. 여러 인스턴스는 `FOR UPDATE SKIP LOCKED`로 batch를 나눈다.
6. 이름과 전화 암호문이 새 접두사가 아닌 예약 건수가 0인지 DB에서 확인한다. 암호문 원문 자체는 출력하지 않는다.
7. 백업과 복구 smoke test 후 이전 키를 비우고 rotation을 끈다.

완료 확인 쿼리의 `<active-prefix>`에는 예를 들어 `v2.2026-09-primary.`를 넣는다.

```sql
select count(*)
from reservations
where left(guest_name_ciphertext, char_length('<active-prefix>')) <> '<active-prefix>'
   or left(guest_phone_ciphertext, char_length('<active-prefix>')) <> '<active-prefix>';
```

배치 도중 문제가 생기면 두 키를 모두 유지한 채 rotation만 끈다. 새 키로 생성된 예약이 있으므로 곧바로 구 키 하나만 남기면 안 된다. 애플리케이션을 되돌릴 때도 구 키를 현재 키, 새 키를 이전 키로 제공해 양쪽 암호문을 읽을 수 있어야 한다.

HMAC 키는 온라인 이중 검증을 지원하지 않는다. 이 값을 단순 교체하면 기존 관리 토큰 검증, 고객 중복 판정과 멱등 재시도 의미가 바뀐다. 유출 사고가 아닌 정기 교체에서는 변경하지 않으며, 사고 시에는 별도 데이터 migration, 기존 관리 토큰 폐기와 고객 안내를 포함한 대응 계획을 먼저 승인한다.

## 7. 장애 대응과 관측

우선 확인 순서:

1. 프런트 `/healthz`, 백엔드 readiness, PostgreSQL health를 확인한다.
2. 실패 응답의 `X-Request-Id`와 Problem Details `traceId`로 개인정보 없이 로그를 연결한다.
3. 401 증가는 issuer·audience·서명키 시각 동기화를, 403 증가는 역할 claim과 관리자 역할 값을 확인한다.
4. 409 증가는 정상 예약 경합인지 DB 잠금·배타 제약 이상인지 구분한다.
5. 429 증가는 공격 또는 클라이언트 재시도 폭주인지 확인하고 엣지 제한을 조정한다.
6. 파기·키 교체 batch 실패는 새 배포를 중단하고 양쪽 AES 키를 보존한다.

Prometheus endpoint는 외부 Nginx에 공개하지 않는다. 내부 모니터링 경로에서도 인증을 적용하고, 5xx 비율, 지연 시간, DB pool, JVM, 401/403/409/429 추세, 파기 및 키 교체 로그에 경보를 연결한다.

## 8. 배포 완료 기록

각 배포에는 애플리케이션 버전, Git commit, migration 버전, 배포 시각, 수행자, 백업 식별자, 자동 smoke request ID와 결과, 수동 인수 결과, 롤백 판단 시각을 남긴다. 비밀값, 토큰, 고객명, 전화번호와 암호문은 기록하지 않는다.
