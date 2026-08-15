# Spring Full-stack Starter

Java 21/Spring Boot 4.1 백엔드와 React 19/TypeScript 5.9 프런트엔드를 한 저장소에서 시작하기 위한 범용 모노레포입니다. 로컬 개발, 계약 기반 API, 통합 테스트, 운영 프로필, 컨테이너 빌드, CI까지 연결되어 있습니다.

## 포함된 기본 구성

- PostgreSQL 17, Flyway migration, JPA schema validation, 생성·수정 시각 감사
- RFC 9457 Problem Details, 안정적인 오류 코드, `X-Request-Id` 로그 추적
- `local` HTTP Basic / `prod` OIDC JWT Resource Server 보안 프로필
- Actuator health·liveness·readiness와 인증된 Prometheus endpoint
- OpenAPI 3.1 계약과 자동 생성 TypeScript 타입
- React Router, TanStack Query, 공통 API client, 오류 경계, 404 화면
- JUnit/Testcontainers/MockMvc 백엔드 테스트와 Vitest/Testing Library 프런트 테스트
- 비루트 Java·Nginx 운영 이미지, 로컬/운영 Docker Compose
- GitHub Actions 품질 게이트와 Dependabot 주간 의존성 갱신

## 로컬 실행

준비물은 Java 21, Node.js 24, Docker Desktop입니다.

```powershell
cd C:\Codex\dev\spring-fullstack-starter
docker compose up -d
.\backend\gradlew.bat -p backend bootRun
```

다른 PowerShell에서 프런트엔드를 실행합니다.

```powershell
npm.cmd run dev --prefix frontend
```

브라우저에서 `http://localhost:5173`을 엽니다. 기본 `local` 프로필의 쓰기 API 계정은 `developer / change-me-locally`이며 실제 개발에서는 실행 전에 `DEV_USER`, `DEV_PASSWORD` 환경 변수로 바꿉니다.

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
- `prod`: Docker Compose 자동 실행을 끄고 `DB_*`, `OIDC_ISSUER_URI`, `CORS_ALLOWED_ORIGIN`을 필수로 받으며 JWT를 검증합니다.

Basic 인증은 편한 로컬 부트스트랩 수단일 뿐 운영 인증이 아닙니다. 운영에서는 실제 OIDC 공급자, 토큰 audience/scope, 역할 매핑 정책을 프로젝트 요구에 맞게 확정해야 합니다.

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

## 운영 Compose 템플릿

`.env.example`을 `.env`로 복사하고 강한 DB 비밀번호, 실제 OIDC issuer, 실제 서비스 origin을 입력한 뒤 실행합니다. 예제 issuer로는 백엔드가 정상 기동하지 않습니다.

```powershell
Copy-Item .env.example .env
docker compose -f compose.prod.yaml up --build -d
```

프런트 Nginx가 `/api/**`와 공개 health만 내부 백엔드로 전달합니다. `/actuator/prometheus`는 외부 프런트 포트로 전달하지 않으며, 내부 모니터링 시스템도 인증 후 접근해야 합니다. 실제 배포에서는 Compose 대신 선택한 클라우드/오케스트레이터의 Secret Manager, TLS, 백업, 롤백 정책을 연결합니다.

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

- [상세 신입 개발 가이드](outputs/Spring_풀스택_개발_신입_가이드.md)
- [최신 Word 신입 개발 가이드](outputs/Spring_풀스택_개발환경_신입_가이드_최신.docx)
- [공통 기능 구축 기록](outputs/공통기능_구축_상세기록.md)
- [전체 환경 구축 진행 기록](outputs/개발환경_구축_진행기록.md)

Git 저장소는 `main` 브랜치로 초기화되어 있지만 아직 최초 커밋은 만들지 않았습니다. 검토 후 팀의 저장소와 커밋 규칙에 맞춰 첫 커밋을 생성하세요.
