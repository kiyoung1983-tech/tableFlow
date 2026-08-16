# TableFlow

Spring Full-stack Starter를 기반으로 만드는 식당 예약 관리 서비스입니다. Java 21/Spring Boot 4.1 백엔드와 React 19/TypeScript 5.9 프런트엔드를 사용하며, 로컬 개발, 계약 기반 API, 통합 테스트, 운영 프로필, 컨테이너 빌드, CI까지 연결되어 있습니다.

현재 TableFlow MVP M1~M11 코드 기준선이 구현되어 화면 기반 매장 설정, 고객 예약 접수·조회·변경·취소, 관리자 당일 운영, 운영 보안과 스테이징 인수 검증을 사용할 수 있습니다. 전환용 Todo는 예약 도메인 공통 계약 테스트가 대체한 뒤 V6 migration으로 제거했습니다.

## 포함된 기본 구성

- PostgreSQL 17, Flyway migration, JPA schema validation, 생성·수정 시각 감사
- RFC 9457 Problem Details, 안정적인 오류 코드, `X-Request-Id` 로그 추적
- `local` HTTP Basic / `prod` OIDC JWT Resource Server 보안 프로필
- OIDC audience·관리자 역할 검증, 공개 예약 API rate limit
- 운영 관리자 SPA의 Authorization Code + PKCE 로그인·로그아웃 콜백
- 예약 개인정보 AES 키 교체 배치와 기간 기반 비식별 파기
- Actuator health·liveness·readiness와 인증된 Prometheus endpoint
- OpenAPI 3.1 계약과 자동 생성 TypeScript 타입
- React Router, TanStack Query, 공통 API client, 오류 경계, 404 화면
- JUnit/Testcontainers/MockMvc 백엔드 테스트와 Vitest/Testing Library 프런트 테스트
- 비루트 Java·Nginx 운영 이미지, 로컬/운영 Docker Compose
- GitHub Actions 품질 게이트와 Dependabot 주간 의존성 갱신

## 로컬 실행

준비물은 Java 21, Node.js 24, Docker Desktop입니다.

```powershell
cd C:\Codex\dev\tableFlow
docker compose up -d
.\backend\gradlew.bat -p backend bootRun
```

다른 PowerShell에서 프런트엔드를 실행합니다.

```powershell
npm.cmd run dev --prefix frontend
```

브라우저에서 `http://localhost:5173`을 엽니다. 기본 `local` 프로필의 쓰기 API 계정은 `developer / change-me-locally`이며 실제 개발에서는 실행 전에 `DEV_USER`, `DEV_PASSWORD` 환경 변수로 바꿉니다.

고객은 `http://localhost:5173/reservations/new`에서 활성 지점, 날짜, 인원과 실시간 가능 시간을 선택해 예약할 수 있습니다. 생성 직후 예약 번호와 관리 토큰이 한 번 표시되므로 둘을 함께 안전하게 보관해야 합니다. 이후 `http://localhost:5173/reservations/manage`에서 두 값으로 조회·변경·취소합니다. 관리 토큰은 다시 발급되지 않으며 화면에서도 URL이나 브라우저 저장소에 보관하지 않습니다.

예약 개인정보 수집·이용 안내는 `http://localhost:5173/privacy`에서 현재 서버 정책 버전과 보존 기간을 반영해 표시합니다. 운영 공개 전 실제 문구와 보존 기간은 개인정보 책임자의 승인을 받아야 합니다.

관리자 당일 운영 보드는 `http://localhost:5173/admin/reservations`에서 접근합니다. 로컬 빌드는 Basic 계정으로 연결하며 비밀번호 입력값은 연결 직후 지우고 인증 정보는 화면을 떠날 때까지 메모리에만 유지합니다. 운영 Compose 빌드는 OIDC Authorization Code + PKCE 리다이렉트로 로그인하고 access token과 PKCE 상태를 탭의 `sessionStorage`에만 보관합니다. 백엔드는 token audience와 관리자 역할을 별도로 검증합니다.

매장 초기 구성과 정책 변경은 `http://localhost:5173/admin/settings`에서 수행합니다. 식당과 지점을 만든 뒤 테이블, 주간 영업시간, 예약 정책을 설정해야 공개 예약 시간이 열립니다. Basic 자격정보와 OIDC 세션은 예약 운영·매장 설정 화면이 공유하며 연결 해제 시 관리자 쿼리 캐시도 함께 제거합니다.

```powershell
$env:DEV_USER='developer'
$env:DEV_PASSWORD='replace-this-value'
.\backend\gradlew.bat -p backend bootRun
```

Redis는 아직 코드에서 사용하지 않으므로 기본 실행에서 제외되어 있습니다. 캐시나 분산 세션 요구가 생긴 뒤 다음처럼 선택적으로 실행합니다.

```powershell
docker compose --profile redis up -d
```

## 프로필 기준

- `local`(기본): 루트 `compose.yaml`의 PostgreSQL을 사용하고 개발용 HTTP Basic을 활성화합니다.
- `test`: Docker Compose 자동 실행을 끄고 Testcontainers PostgreSQL과 고정 테스트 계정을 사용합니다.
- `prod`: Docker Compose 자동 실행을 끄고 DB, OIDC API·SPA, CORS·CSP origin과 예약 암호화/HMAC 키를 필수로 받습니다. 프런트는 PKCE 로그인을 사용하고 백엔드는 JWT audience와 관리자 역할을 검증합니다.

Basic 인증은 편한 로컬 부트스트랩 수단일 뿐 운영 인증이 아닙니다. 운영에서는 실제 OIDC 공급자에 `OIDC_AUDIENCE`, 역할 claim과 `OIDC_ADMIN_ROLE`을 일치시키고 `OIDC_SPA_CLIENT_ID`를 public PKCE client로 등록해야 합니다.

## 전체 검증

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

개별 명령은 다음과 같습니다.

```powershell
.\backend\gradlew.bat -p backend test
npm.cmd run check --prefix frontend
docker compose config --quiet
docker compose --env-file .env.example -f compose.prod.yaml config --quiet
```

프런트 `check`는 OpenAPI 생성 타입 일치, oxlint, Vitest, TypeScript, Vite production build를 차례로 검사합니다. OpenAPI를 수정했다면 먼저 타입을 갱신합니다.

```powershell
npm.cmd run openapi:generate --prefix frontend
```

## 스테이징 배포 후 검증

Node.js 24 기반의 읽기 전용 smoke test가 프런트·백엔드 health, 고객 예약·개인정보 안내·관리자 설정 SPA 딥링크, 보안 헤더, 공개 booking context 계약, 비공개 Actuator 차단을 검사합니다.

```powershell
node .\scripts\staging-smoke.mjs --base-url https://staging.example.com
```

원격 URL은 기본적으로 HTTPS만 허용합니다. 로컬 리버스 프록시를 확인할 때는 `http://localhost:포트`를 그대로 사용할 수 있습니다. 이 자동 검사는 예약을 만들거나 개인정보를 보내지 않으며, 실제 OIDC 로그인·로그아웃과 관리자 권한, 예약 생성은 운영 런북의 수동 인수 시나리오로 확인합니다.

배포 전에는 환경 파일의 예제값, HTTPS origin 불일치, 약한 DB 비밀번호, 잘못된 Base64 키와 AES/HMAC 키 재사용을 사전검증합니다. `--check-oidc`를 추가하면 issuer metadata와 PKCE S256 지원도 온라인으로 확인합니다. 검사 결과에는 비밀번호나 키를 출력하지 않습니다.

```powershell
node .\scripts\deployment-preflight.mjs --env-file .env
node .\scripts\deployment-preflight.mjs --env-file .env --check-oidc
```

읽기 전용 smoke 뒤에는 승인된 합성 이름·테스트 전화번호와 단기 access token을 안전한 방식으로 프로세스 환경에 주입해 쓰기 UAT를 실행할 수 있습니다. 이 명령은 401/403/200 관리자 권한 경계, 가용성, 예약 생성과 멱등 재시도, 관리 token 조회와 취소를 검사합니다. 실제 예약을 만들기 때문에 `--confirm-write` 없이는 어떤 요청도 보내지 않으며 종료 전에 고객 token, 필요하면 관리자 전이로 예약을 취소합니다. token과 전화번호는 결과에 출력하지 않습니다.

```powershell
node .\scripts\staging-uat.mjs --base-url https://staging.example.com --confirm-write
```

필수 프로세스 환경 변수는 `TABLEFLOW_UAT_ADMIN_TOKEN`, `TABLEFLOW_UAT_GUEST_NAME`, `TABLEFLOW_UAT_GUEST_PHONE`입니다. `TABLEFLOW_UAT_NON_ADMIN_TOKEN`을 추가하면 비관리자 역할의 403도 확인합니다. 실행 뒤 해당 환경 변수는 현재 셸에서 제거합니다. 이 검사는 이미 발급된 token으로 API를 확인하므로 브라우저의 실제 IdP 로그인·로그아웃 왕복은 UAT 체크리스트에서 별도로 수행합니다.

## 운영 Compose 템플릿

`.env.example`을 `.env`로 복사하고 강한 DB 비밀번호, 실제 OIDC issuer, 실제 서비스 origin을 입력한 뒤 실행합니다. 예제 issuer로는 백엔드가 정상 기동하지 않습니다.

IdP의 SPA client에는 `${APP_PUBLIC_URL}/auth/callback`과 `${APP_PUBLIC_URL}/auth/logout-callback`을 허용 URI로 등록합니다. discovery·token endpoint는 브라우저 origin의 CORS 요청을 허용해야 하며 `OIDC_CSP_CONNECT_SRC`에는 issuer의 origin만 지정합니다.

예약 개인정보용 `RESERVATION_ENCRYPTION_KEY`와 HMAC용 `RESERVATION_HMAC_KEY`에는 서로 다른 32바이트 난수를 Base64로 인코딩해 설정합니다. 예제의 placeholder로는 운영 백엔드가 기동하지 않습니다. 키는 저장소에 커밋하지 말고 Secret Manager에서 주입해야 합니다.

백업·복구, 보존 기간 승인, AES 키 교체와 배포 판정 절차는 [TableFlow 운영 런북](outputs/TableFlow_운영_런북.md)을 따릅니다. 실제 인수 결과는 [TableFlow 스테이징 UAT 체크리스트](outputs/TableFlow_스테이징_UAT_체크리스트.md)에 기록합니다. 애플리케이션 rate limit은 인스턴스별 보조 방어이므로 운영 인그레스나 WAF에도 전역 제한을 설정합니다.

```powershell
Copy-Item .env.example .env
docker compose -f compose.prod.yaml up --build -d
```

프런트 Nginx가 `/api/**`와 정확한 `/actuator/health`만 내부 백엔드로 전달합니다. `/actuator/prometheus`를 포함한 나머지 `/actuator/**`는 외부에서 404를 반환하며, 내부 모니터링 시스템도 인증 후 백엔드 네트워크로 접근해야 합니다. 실제 배포에서는 Compose 대신 선택한 클라우드/오케스트레이터의 Secret Manager, TLS, 백업, 롤백 정책을 연결합니다.

## 새 기능을 추가하는 기본 순서

1. 요구사항과 권한·오류 상태를 정합니다.
2. DB 변경이면 기존 SQL을 고치지 않고 새 Flyway migration을 만듭니다.
3. 백엔드 도메인 코드와 통합 테스트를 추가합니다.
4. `openapi/openapi.yaml`을 같은 작업에서 갱신합니다.
5. TypeScript 타입을 재생성하고 도메인 API 함수를 추가합니다.
6. Router page와 TanStack Query query/mutation을 연결합니다.
7. 전체 검증 후 논리적으로 하나인 변경을 함께 커밋합니다.

## 모든 프로젝트에 그대로 쓰는 부분과 바꿀 부분

오류 형식, 요청 추적, migration 원칙, 테스트 격리, 계약 생성, 라우팅·서버 상태 구조, 프로필 분리, 컨테이너·CI 골격은 대부분의 업무형 웹 서비스에서 그대로 재사용할 수 있습니다.

다음은 프로젝트마다 반드시 결정해야 합니다.

- 실제 도메인과 모듈 경계, 트랜잭션 규칙
- OIDC 공급자, audience/scope/role과 개인정보·감사 정책
- 캐시·메시징·파일 저장소의 필요 여부
- 클라우드, TLS/DNS, Secret Manager, DB 백업·복구, 무중단/롤백 방식
- SLO와 로그·메트릭·트레이스 저장소, 경보 기준
- 부하 특성에 따른 성능·인덱스·rate limit 정책
- 접근성, 디자인 시스템, 브라우저 지원 범위

즉, 이 저장소는 “어떤 제품이든 완성되는 정답”이 아니라 안전하고 반복 가능한 출발점입니다. 사용하지 않는 Redis나 메시징 같은 기술은 요구가 생기기 전까지 코드 의존성으로 넣지 않습니다.

## 문서

- [TableFlow 프로젝트 시작 안내](outputs/TableFlow_프로젝트_시작안내.md)
- [TableFlow MVP 설계](outputs/TableFlow_MVP_설계.md)
- [TableFlow 운영 런북](outputs/TableFlow_운영_런북.md)
- [TableFlow 스테이징 UAT 체크리스트](outputs/TableFlow_스테이징_UAT_체크리스트.md)
- [상세 신입 개발 가이드](outputs/Spring_풀스택_개발_신입_가이드.md)
- [최신 Word 신입 개발 가이드](outputs/Spring_풀스택_개발환경_신입_가이드_최신.docx)
- [공통 기능 구축 기록](outputs/공통기능_구축_상세기록.md)
- [전체 환경 구축 진행 기록](outputs/개발환경_구축_진행기록.md)

Git 저장소는 `main` 브랜치로 초기화되어 있지만 아직 최초 커밋은 만들지 않았습니다. 검토 후 팀의 저장소와 커밋 규칙에 맞춰 첫 커밋을 생성하세요.
