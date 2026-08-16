# Spring 풀스택 개발 신입 가이드

기준일: 2026-08-16  
프로젝트: `C:\Codex\dev\tableFlow`

> 문서 상태: 이 문서는 TableFlow 전환 전 Todo 기반 공통 구조를 설명하는 **역사적 학습 자료**다. 현재 구현과 실행 절차는 [TableFlow 프로젝트 시작 안내](TableFlow_프로젝트_시작안내.md), 도메인 결정은 [TableFlow MVP 설계](TableFlow_MVP_설계.md)를 기준으로 한다.

이 문서는 Java·Spring·React 경험이 많지 않은 신입 개발자가 초기 Todo 기준선의 요청 흐름을 추적하며 공통 구조를 학습할 수 있도록 설명한다. 명령을 외우는 것보다 “어느 프로세스가 어떤 파일을 읽고 다음 계층에 무엇을 전달하는가”를 이해하는 것이 목표다.

## 1. 가장 먼저 알아야 할 전체 그림

이 저장소는 백엔드와 프론트엔드를 하나의 Git 저장소에서 관리하는 모노레포다.

```text
Browser :5173
  -> Vite development server
  -> /api, /actuator proxy
  -> Spring Boot :8080
  -> RequestTraceFilter
  -> Spring Security
  -> TodoController
  -> TodoRepository / JPA
  -> PostgreSQL :5432
```

Redis는 선택형 Compose profile로 준비돼 있지만 현재 코드에서는 사용하지 않는다. 캐시나 분산 세션 요구사항이 생겼을 때만 `--profile redis`로 실행하고 Spring Data Redis 의존성·정책·테스트를 같이 추가한다.

### 포트별 담당자

| 포트 | 프로세스 | 역할 |
|---:|---|---|
| 5173 | Vite | React 개발 화면, HMR, API proxy |
| 8080 | Spring Boot | API, 보안, 검증, JPA, Actuator |
| 5432 | PostgreSQL | Todo 영구 저장 |
| 6379 | Redis | `redis` profile 선택 시에만 실행, 현재 미사용 |

브라우저는 PostgreSQL에 직접 연결하지 않는다. 화면은 HTTP API만 호출하고, DB 접근은 백엔드만 담당한다.

## 2. 저장소 구조

```text
spring-fullstack-starter/
├─ backend/
│  ├─ build.gradle.kts
│  └─ src/
│     ├─ main/java/com/example/fullstack/
│     │  ├─ common/
│     │  │  ├─ error/
│     │  │  ├─ persistence/
│     │  │  └─ web/
│     │  ├─ config/
│     │  └─ todo/
│     ├─ main/resources/
│     │  ├─ application.properties
│     │  ├─ application-local.properties
│     │  ├─ application-prod.properties
│     │  └─ db/migration/
│     └─ test/java/
│  └─ Dockerfile
├─ frontend/
│  ├─ package.json
│  ├─ Dockerfile / nginx.conf.template
│  └─ src/
│     ├─ api/
│     ├─ app/
│     ├─ components/
│     └─ pages/
├─ openapi/openapi.yaml
├─ compose.yaml / compose.prod.yaml
├─ .env.example / .editorconfig
├─ scripts/verify.ps1
├─ .github/workflows/ci.yml / dependabot.yml
└─ outputs/
```

### 폴더를 나눈 기준

- `common/error`: 도메인에 상관없이 반복되는 HTTP 오류 변환
- `common/web`: 요청 추적, 페이징처럼 HTTP 계층에서 공통으로 쓰는 기능
- `common/persistence`: Entity 생성·수정 시간 같은 영속성 정책
- `todo`: Todo에만 해당하는 Entity, Repository, Controller, 도메인 예외
- `frontend/src/api`: 화면과 네트워크 호출을 분리하는 프론트 API 계층
- `frontend/src/app`: Router, TanStack Query처럼 앱 전체에 한 번 적용하는 구성
- `frontend/src/pages`: URL 단위 화면, `components`는 여러 화면이 재사용하는 UI
- `openapi`: 백엔드와 프론트가 공유하는 API 계약

`CommonUtil` 하나에 문자열, 날짜, 보안, DB 함수를 모두 넣지 않는다. 공통 코드는 “여러 곳에서 쓴다”뿐 아니라 “하나의 분명한 책임을 가진다”는 조건을 만족해야 한다.

## 3. 기술 구성과 선택 이유

| 구성요소 | 버전/선택 | 역할 |
|---|---|---|
| Java | 21 LTS | 백엔드 언어와 운영 기준 |
| Spring Boot | 4.1.0 | 웹 서버와 Spring 자동 구성 |
| Gradle | 9.5.1 Wrapper | 빌드, 테스트, 실행, 의존성 관리 |
| Spring MVC | Servlet 기반 | REST API |
| Spring Security | Basic / OIDC JWT | local·prod 인증 분리 |
| Spring Data JPA | Hibernate | Entity와 PostgreSQL 연결 |
| Flyway | SQL migration | DB 스키마 변경 이력 |
| Spring Modulith | 2.1.0 | 모듈러 모놀리스 기반 |
| React | 19.2 | 브라우저 UI |
| TypeScript | 5.9.3 | 프론트 정적 타입 검사 |
| Vite | 8.2 | 개발 서버, proxy, production build |
| React Router | 7 | URL과 page 연결 |
| TanStack Query | 5 | 서버 상태 조회·캐시·재시도 |
| Vitest | 4 | 프론트 단위·컴포넌트 테스트 |
| PostgreSQL | 17 Alpine | 관계형 데이터 저장 |
| Redis | 8 Alpine | 향후 캐시·세션용 |
| Testcontainers | PostgreSQL 17 | 실제 DB 기반 통합 테스트 |
| OpenAPI | 3.1 | API 요청·응답 계약 |

Gradle은 전역 설치 대신 `gradlew.bat`을 사용한다. 프로젝트가 Gradle 버전을 직접 고정하므로 개발자와 CI가 같은 빌드를 수행한다.

TypeScript는 `openapi-typescript 7.13.0`의 공식 peer dependency 범위와 맞추기 위해 5.9.3을 사용한다. 실제 설치 버전은 `package-lock.json`이 고정한다.

## 4. 처음 실행하기

### 준비 조건

- Java 21
- Node.js 24
- Docker Desktop Linux Engine
- Git
- PowerShell

다음 명령에서 Client와 Server가 모두 표시돼야 Docker Engine이 준비된 것이다.

```powershell
docker version
docker info
```

### 터미널 1: 프로젝트와 인프라

```powershell
cd C:\Codex\dev\spring-fullstack-starter
docker compose up -d
docker compose ps
```

기본 실행에서는 `postgres`가 `healthy`인지 확인한다. Redis가 실제로 필요한 작업에서만 `docker compose --profile redis up -d`를 사용한다. `-d`는 컨테이너를 백그라운드에서 실행한다는 뜻이다.

### 터미널 2: 백엔드

```powershell
cd C:\Codex\dev\spring-fullstack-starter
.\backend\gradlew.bat -p backend bootRun
```

정상 로그의 핵심은 다음 두 문장이다.

```text
Tomcat started on port 8080
Started BackendApplication
```

Gradle이 `:bootRun`에서 끝나지 않는 것은 정상이다. 백엔드 서버가 HTTP 요청을 기다리고 있기 때문이다. 종료할 때 `Ctrl+C`를 누른다.

### 터미널 3: 프론트엔드

```powershell
cd C:\Codex\dev\spring-fullstack-starter
npm.cmd run dev --prefix frontend
```

PowerShell 실행 정책이 `npm.ps1`을 차단할 수 있으므로 정책을 낮추지 않고 `npm.cmd`를 사용한다.

### 브라우저 확인

- `http://localhost:8080/actuator/health`
- `http://localhost:8080/api/todos?page=0&size=20`
- `http://localhost:5173`

React 화면에서 `Spring Boot: UP`이 표시되면 Vite proxy와 백엔드가 연결된 것이다.

### 종료

백엔드와 프론트는 각각 `Ctrl+C`로 종료한다. 컨테이너는 다음 명령으로 제거한다.

```powershell
docker compose down
```

PostgreSQL named volume은 남기 때문에 다음 실행에도 데이터가 유지된다. `docker compose down -v`는 볼륨과 DB 데이터를 삭제하므로 초기화를 의도한 경우에만 사용한다.

## 5. 백엔드가 시작될 때 일어나는 일

`BackendApplication.main()`이 실행되면 다음 순서로 애플리케이션이 준비된다.

1. `application.properties`와 환경 변수를 읽는다.
2. `../compose.yaml`을 찾고 필요한 로컬 인프라 상태를 확인한다.
3. PostgreSQL DataSource와 connection pool을 만든다.
4. Flyway가 적용된 migration 버전을 확인한다.
5. 미적용 migration을 순서대로 실행한다.
6. Hibernate가 Entity와 실제 DB 스키마를 검증한다.
7. JPA Repository, Controller, Security filter chain을 구성한다.
8. Actuator endpoint를 준비한다.
9. Tomcat이 `8080` 포트에서 요청을 기다린다.

`spring.jpa.hibernate.ddl-auto=validate`이므로 Hibernate는 스키마를 임의로 변경하지 않는다. DB 구조 변경은 Flyway만 담당한다.

## 6. 초기 Todo 기준 API

### 상태 조회

```http
GET /actuator/health
```

인증 없이 호출할 수 있다.

```json
{
  "status": "UP"
}
```

### Todo 목록

```http
GET /api/todos?page=0&size=20
```

- `page`: 0 이상, 기본값 0
- `size`: 1~100, 기본값 20
- 정렬: `createdAt` 내림차순
- 인증: 필요 없음

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

목록을 단순 배열로 반환하지 않는 이유는 데이터가 많아졌을 때 전체 데이터를 한 번에 읽지 않기 위해서다.

### Todo 단건 조회

```http
GET /api/todos/{id}
```

없는 UUID를 요청하면 `404 TODO_NOT_FOUND` Problem Details를 반환한다.

### Todo 생성

```http
POST /api/todos
Authorization: Basic ...
Content-Type: application/json

{
  "title": "첫 번째 작업"
}
```

- 제목은 공백일 수 없다.
- 제목은 최대 200자다.
- 저장 전 앞뒤 공백을 제거한다.
- 정상 생성은 `201 Created`다.
- 인증이 없거나 틀리면 `401 AUTHENTICATION_REQUIRED`다.

PowerShell 예시:

```powershell
$pair = 'developer:change-me-locally'
$token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))

Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/todos `
  -Headers @{ Authorization = "Basic $token" } `
  -ContentType 'application/json' `
  -Body '{"title":"첫 번째 작업"}'
```

`developer / change-me-locally`는 로컬 개발 기본값이다. 운영 환경에서는 OIDC/OAuth2와 Secret Manager로 교체해야 한다.

## 7. Todo 목록 요청을 코드에서 추적하기

화면이 열리면 다음 흐름이 실행된다.

1. `frontend/src/App.tsx`가 `getHealth()`와 `listTodos()`를 호출한다.
2. `frontend/src/api/todoApi.ts`가 endpoint URL을 만든다.
3. `frontend/src/api/client.ts`의 `api<T>()`가 `fetch`를 실행한다.
4. Vite가 `/api` 요청을 `localhost:8080`으로 전달한다.
5. `RequestTraceFilter`가 요청 ID를 준비한다.
6. Spring Security가 공개 GET 요청을 허용한다.
7. `TodoController.findAll()`이 page와 size를 검증한다.
8. `TodoRepository.findAllByOrderByCreatedAtDesc()`가 JPA 조회를 수행한다.
9. `PageResponse.from()`이 Entity를 외부 DTO와 페이지 메타데이터로 변환한다.
10. 프론트의 `api<T>()`가 생성된 TypeScript 타입으로 JSON을 반환한다.
11. `App.tsx`가 `todoPage.items`를 화면에 표시한다.

프론트에서 `fetch`를 화면마다 직접 호출하지 않는 이유는 JSON 처리, 오류 처리, 요청 ID 읽기를 한 곳에서 일관되게 수행하기 위해서다.

## 8. 공통 오류 응답

Spring MVC의 RFC 9457 Problem Details 형식을 사용한다.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Todo를 찾을 수 없습니다: ...",
  "instance": "/api/todos/...",
  "code": "TODO_NOT_FOUND",
  "traceId": "e9c7..."
}
```

### 필드 의미

- `title`: 오류 종류의 짧은 이름
- `status`: HTTP 상태 코드
- `detail`: 사용자가 이해할 수 있는 상세 메시지
- `instance`: 오류가 발생한 요청 경로
- `code`: 프론트 분기와 모니터링에서 사용하는 안정적인 애플리케이션 코드
- `traceId`: 응답과 서버 로그를 연결하는 요청 식별자

입력 검증 실패에는 필드별 `errors`가 추가된다.

```json
{
  "title": "Validation failed",
  "status": 400,
  "detail": "요청값을 확인해 주세요.",
  "code": "VALIDATION_ERROR",
  "traceId": "...",
  "errors": {
    "title": ["must not be blank"]
  }
}
```

### 백엔드에서 새 오류 추가하기

1. 도메인 패키지에 `ApiException`을 상속한 예외를 만든다.
2. 적절한 `HttpStatus`를 선택한다.
3. 변경되지 않는 대문자 오류 코드를 부여한다.
4. 서비스나 Controller에서 조건에 맞을 때 예외를 던진다.
5. `GlobalExceptionHandler`가 JSON으로 변환하게 둔다.
6. 상태, code, detail, traceId를 통합 테스트로 검증한다.

Controller마다 `try-catch`로 오류 JSON을 직접 만들지 않는다.

## 9. X-Request-Id와 로그 추적

모든 응답에는 `X-Request-Id`가 포함된다.

```text
X-Request-Id: client-request-123
```

클라이언트가 안전한 값을 보내면 그대로 사용하고, 없거나 올바르지 않으면 서버가 UUID를 만든다. 같은 값은 요청 attribute, 응답 헤더, Problem Details, SLF4J MDC에 들어간다.

```text
INFO [traceId:client-request-123]
http_request method=GET path=/api/todos status=200 duration_ms=12
```

장애 문의를 받을 때는 다음 순서로 조사한다.

1. 사용자에게 오류 응답의 `traceId` 또는 `X-Request-Id`를 받는다.
2. 서버 로그에서 같은 `traceId`를 검색한다.
3. 해당 요청의 method, path, status, duration을 확인한다.
4. 인접한 최초 ERROR와 `Caused by`를 찾는다.

`RequestTraceFilter`는 `finally`에서 MDC를 제거한다. 서버 스레드는 재사용되므로 이 정리가 없으면 다음 요청 로그에 이전 ID가 섞일 수 있다.

## 10. 데이터베이스와 Flyway

초기 Todo 기준 migration은 세 개였다.

| 버전 | 파일 | 역할 |
|---|---|---|
| V1 | `V1__create_todo.sql` | todos 테이블과 생성일 인덱스 |
| V2 | `V2__create_event_publication.sql` | Spring Modulith 이벤트 발행 기록 |
| V3 | `V3__add_todo_updated_at.sql` | Todo 수정 시각 추가와 기존 데이터 보정 |

### migration 규칙

- 공유된 migration은 수정하지 않는다.
- 변경이 필요하면 다음 버전 파일을 추가한다.
- 파일명은 `V숫자__설명.sql` 형식이며 밑줄은 두 개다.
- 기존 데이터가 있는 상태도 고려한다.
- Testcontainers와 로컬 DB에서 검증한다.
- `ddl-auto=update`로 Flyway를 우회하지 않는다.

V3는 기존 Todo가 있어도 동작하도록 다음 순서를 사용한다.

1. nullable `updated_at` 컬럼을 추가한다.
2. 기존 행의 `created_at`을 `updated_at`에 복사한다.
3. 마지막에 NOT NULL 제약을 적용한다.

## 11. JPA Entity와 감사 시간

`Todo`는 `BaseTimeEntity`를 상속한다.

- `@CreatedDate`: 최초 저장 시 `createdAt` 입력
- `@LastModifiedDate`: 저장·수정 시 `updatedAt` 입력
- `@EnableJpaAuditing`: `PersistenceConfig`에서 감사 기능 활성화

Entity 생성자에서 `Instant.now()`를 반복하지 않고 Spring Data JPA에 시간 기록 책임을 둔다. 새 Entity가 동일한 생성·수정 시간 정책을 쓸 때만 `BaseTimeEntity`를 상속한다.

Entity를 API에 그대로 반환하지 않고 `TodoResponse` record로 변환한다. DB 내부 구조와 외부 API 계약을 분리하기 위해서다.

## 12. 보안 설정

초기 기준 정책은 다음과 같다.

| 요청 | 정책 |
|---|---|
| `GET /actuator/health` | 공개 |
| `GET /api/**` | 공개 |
| `POST /api/**` | 인증 필요 |
| 그 밖의 요청 | 인증 필요 |

보안 구현은 Spring profile에 따라 갈린다.

- `local`(기본): 개발자가 바로 호출할 수 있도록 HTTP Basic을 사용한다.
- `test`: Testcontainers와 고정 테스트 계정을 사용하고 외부 OIDC에 접속하지 않는다.
- `prod`: HTTP Basic 사용자를 만들지 않고 OIDC 발급자의 JWT 서명을 검증하는 Resource Server로 동작한다.

HTTP Basic 계정은 환경 변수로 바꿀 수 있다.

```text
DEV_USER
DEV_PASSWORD
```

Spring Security에서 발생하는 401과 403은 ControllerAdvice까지 도달하지 않는다. 따라서 `SecurityConfig`의 `AuthenticationEntryPoint`와 `AccessDeniedHandler`가 Problem Details를 직접 작성한다.

초기 JSON API 개발에서는 `/api/**`의 CSRF를 예외 처리했다. 브라우저 쿠키 기반 인증을 도입하면 CSRF 정책을 다시 설계해야 한다.

`/actuator/health/**`는 공개하지만 `/actuator/prometheus`를 포함한 나머지 Actuator endpoint는 인증이 필요하다. 운영 Nginx는 Prometheus endpoint를 외부에 proxy하지 않는다. 실제 OIDC 공급자를 선택한 뒤에는 issuer뿐 아니라 토큰 `audience`, `scope`, `role`을 어떤 권한으로 바꿀지도 정해야 한다.

## 13. 주요 환경 변수

| 변수 | 로컬 기본값 | 의미 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/app` | PostgreSQL 주소 |
| `DB_USER` | `app` | DB 사용자 |
| `DB_PASSWORD` | `app-local-password` | DB 비밀번호 |
| `DEV_USER` | `developer` | 로컬 Basic 사용자 |
| `DEV_PASSWORD` | `change-me-locally` | 로컬 Basic 비밀번호 |
| `CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | 허용 프론트 origin |
| `OIDC_ISSUER_URI` | 없음, prod 필수 | 운영 JWT 발급자 discovery 주소 |
| `APP_VERSION` | `local` | Actuator info에 표시할 배포 버전 |
| `APP_PORT` | `80` | 운영 Compose의 외부 프론트 포트 |

`${DB_USER:app}`은 `DB_USER` 환경 변수가 있으면 그 값을 사용하고, 없으면 `app`을 사용한다는 뜻이다.

`.env.example`은 변수 이름과 가짜 값만 제공한다. 운영 비밀값을 `.env`나 Git에 커밋하지 않고 배포 플랫폼의 Secret Manager를 사용한다. `prod` profile은 DB 접속값, 실제 OIDC issuer, 실제 CORS origin이 없으면 기동하지 않도록 구성되어 있다.

## 14. OpenAPI와 TypeScript 타입 생성

`openapi/openapi.yaml`이 백엔드와 프론트 사이의 계약이다.

포함된 endpoint:

- `GET /actuator/health`
- `GET /api/todos`
- `POST /api/todos`
- `GET /api/todos/{id}`

포함된 주요 schema:

- `HealthResponse`
- `CreateTodoRequest`
- `TodoResponse`
- `TodoPageResponse`
- `ProblemDetail`
- `ValidationProblemDetail`

API를 바꿀 때는 다음 순서를 따른다.

1. 백엔드 endpoint와 DTO 변경을 설계한다.
2. 같은 작업에서 `openapi/openapi.yaml`을 수정한다.
3. 프론트 타입을 생성한다.

```powershell
npm.cmd run openapi:generate --prefix frontend
```

4. 생성된 `frontend/src/api/schema.d.ts`를 직접 수정하지 않는다.
5. `types.ts`와 도메인 API 함수에서 생성 타입을 사용한다.
6. 전체 프론트 검증을 실행한다.

```powershell
npm.cmd run check --prefix frontend
```

`check-openapi.mjs`는 YAML에서 타입을 메모리로 다시 생성해 저장된 `schema.d.ts`와 비교한다. API 계약을 바꾸고 타입 생성을 잊으면 로컬 검증과 CI가 실패한다.

## 15. 프론트 API 계층

### `client.ts`

공통 `api<T>()`는 다음을 처리한다.

- `fetch` 실행
- JSON body 직렬화
- `Content-Type` 설정
- 성공 JSON 파싱
- `X-Request-Id` 읽기
- 요청마다 `X-Request-Id` 생성
- `VITE_API_BASE_URL` 기반 API 주소 선택
- 당시에는 OIDC SDK를 나중에 연결할 수 있도록 둔 access token provider
- 2xx가 아닌 응답을 `ApiError`로 변환
- JSON 오류 본문이 없는 경우 `HTTP_ERROR` 기본 오류 생성

### `todoApi.ts`

endpoint별 함수를 제공한다.

- `getHealth()`
- `listTodos(page, size)`
- `getTodo(id)`
- `createTodo(request, authorization)`

화면 컴포넌트는 URL 문자열과 JSON 파싱을 직접 반복하지 않는다. 새로운 도메인을 추가할 때는 `userApi.ts`, `orderApi.ts`처럼 도메인 API 모듈을 분리한다.

### Router와 서버 상태

- `app/router.tsx`: `/`, `/todos`, 없는 URL의 page를 연결한다.
- `app/AppProviders.tsx`: 앱별 `QueryClient`를 한 번 제공한다.
- `app/queryClient.ts`: 캐시 시간, 4xx 재시도 금지, 5xx 최대 재시도 기준을 둔다.
- `pages/TodoPage.tsx`: `useQuery`로 health와 Todo 목록을 가져와 loading·empty·error·success 상태를 나눈다.
- `components/AppErrorBoundary.tsx`: 렌더링 중 예상하지 못한 오류에 복구 화면을 제공한다.

로그인 제품을 정하면 OIDC SDK가 발급한 access token을 `configureAccessTokenProvider`에 연결한다. 토큰을 `localStorage`에 임의 저장하는 방식은 기본 구현에 포함하지 않았다.

## 16. 테스트

백엔드 전체 테스트:

```powershell
.\backend\gradlew.bat -p backend test
```

초기 Todo 기준 백엔드 테스트는 총 12개였으며 다음을 검증했다.

- 전체 Spring ApplicationContext 기동
- PostgreSQL 17 Testcontainer 실행
- Flyway V1~V3 적용
- Hibernate schema validation
- 공개 Todo 페이지 조회
- 인증 없는 생성 차단
- 인증된 Todo 생성과 감사 시간
- 공백 제목 검증
- 깨진 JSON의 공통 Problem Details
- 실제 페이징 개수와 메타데이터
- 없는 Todo의 도메인 오류
- 최대 페이지 크기 제한
- 클라이언트 요청 ID 전달
- CORS preflight 허용 origin
- health 공개와 Prometheus 인증 보호

초기 프런트는 Vitest/Testing Library 테스트 3개를 실행했다. API client의 Bearer token·요청 ID·Problem Details 변환과 Todo page의 서버 상태 렌더링을 검증했다.

Testcontainers DB는 개발 DB와 다르다.

| 구분 | 개발 DB | Testcontainers DB |
|---|---|---|
| 수명 | 개발자가 유지 | 테스트 동안만 존재 |
| 데이터 | 누적될 수 있음 | 빈 상태에서 시작 |
| 포트 | 5432 | 빈 포트 자동 할당 |
| 목적 | 수동 개발 | 자동 재현 검증 |

## 17. 전체 품질 검증

프론트 검증:

```powershell
npm.cmd run check --prefix frontend
```

실행 순서:

```text
OpenAPI 생성 타입 일치
  -> oxlint
  -> Vitest
  -> TypeScript project build
  -> Vite production build
```

전체 검증:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

`verify.ps1`은 Gradle과 npm의 종료 코드를 확인한다. 하나라도 실패하면 즉시 실패 코드로 종료한다. Gradle, npm, TypeScript 임시 파일은 Git에서 제외된 `.verify-cache` 아래에 둔다.

## 18. GitHub Actions CI

push 또는 Pull Request가 생성되면 `.github/workflows/ci.yml`이 실행된다.

백엔드 job:

```text
checkout
  -> Temurin Java 21
  -> Gradle cache
  -> backend test
```

프론트엔드 job:

```text
checkout
  -> Node 24
  -> npm ci
  -> OpenAPI 타입 일치
  -> lint
  -> Vitest
  -> production build
```

백엔드와 프론트 job은 병렬로 실행된다. 둘 다 성공하면 세 번째 container job이 Java와 Nginx 운영 이미지를 실제로 빌드한다. 하나라도 실패하면 CI가 실패한다. 같은 브랜치에 새 변경이 올라오면 이전 실행을 취소하고, 각 job에는 무한 대기를 막는 timeout이 있다. `npm ci`는 `package-lock.json`을 그대로 재현하기 때문에 잠금 파일도 소스와 함께 커밋해야 한다. Dependabot은 Gradle, npm, GitHub Actions 갱신을 매주 제안한다.

## 19. 새 기능을 추가하는 권장 절차

예를 들어 “Todo 완료 처리”를 추가한다고 가정한다.

1. 요구사항을 HTTP 동작과 상태 코드로 정의한다.
2. DB 변경이 필요하면 새 Flyway migration을 작성한다.
3. Entity에 필요한 상태 변경 메서드를 추가한다.
4. Repository 조회가 필요하면 도메인 의도가 드러나는 메서드를 추가한다.
5. 도메인 예외와 오류 코드를 정의한다.
6. Controller 또는 별도 Service에 트랜잭션 경계를 둔다.
7. 성공, 인증, 검증, 미존재 조건의 통합 테스트를 먼저 추가하거나 함께 작성한다.
8. `openapi.yaml`에 endpoint와 schema를 반영한다.
9. `npm run openapi:generate`로 프론트 타입을 갱신한다.
10. `todoApi.ts`에 호출 함수를 추가한다.
11. React 화면에서 API 함수를 사용한다.
12. 전체 검증 스크립트를 실행한다.
13. 변경된 migration, OpenAPI, 생성 타입, 코드와 테스트를 하나의 논리적인 커밋에 포함한다.

기능이 작더라도 DB, API 계약, 테스트, 프론트 타입 중 한쪽만 바뀌지 않았는지 확인한다.

## 20. 자주 만나는 오류

### Docker Engine pipe가 없음

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

Docker CLI는 설치됐지만 Docker Desktop Linux Engine이 실행되지 않은 상태다.

```powershell
docker info
```

### `npm.ps1` 실행 차단

시스템 실행 정책을 변경하지 말고 `npm.cmd`를 사용한다.

### `No Docker Compose file found`

명령 실행 위치와 `spring.docker.compose.file=../compose.yaml`을 확인한다.

### 포트 사용 중

- `8080`: 다른 Spring Boot 프로세스 확인
- `5173`: 다른 Vite 프로세스 확인
- `5432`: 다른 PostgreSQL 또는 컨테이너 확인
- `6379`: 다른 Redis 확인

### migration 실패

로그의 실패한 migration 버전과 SQL을 확인한다. 이미 공유된 migration을 고쳐서 해결하지 말고, 적용 상태와 새 보정 migration 필요 여부를 판단한다.

### OpenAPI 생성 타입 불일치

```powershell
npm.cmd run openapi:generate --prefix frontend
npm.cmd run check --prefix frontend
```

### 401 응답

POST 요청인지, Authorization header가 있는지, `DEV_USER`와 `DEV_PASSWORD`가 실행 환경에서 바뀌지 않았는지 확인한다.

### 400 VALIDATION_ERROR

Problem Details의 `errors`에서 필드 이름과 메시지를 확인한다. Todo title은 공백 금지, 최대 200자다.

## 21. 로그 읽는 순서

1. 마지막 `FAILURE`에서 실패한 task를 확인한다.
2. 위쪽으로 이동해 최초 `ERROR` 또는 `Caused by`를 찾는다.
3. `connection refused`, `access denied`, `missing table` 같은 구체적인 문장을 기록한다.
4. 응답 오류라면 `traceId`로 요청 로그를 찾는다.
5. 코드를 바꾸기 전에 Docker, 포트, 현재 폴더, 환경 변수를 확인한다.
6. 수정 후 같은 명령을 다시 실행해 원인이 사라졌는지 검증한다.

상태 확인 명령:

```powershell
docker info
docker compose ps
.\backend\gradlew.bat -p backend test
npm.cmd run check --prefix frontend
Invoke-RestMethod http://localhost:8080/actuator/health
```

## 22. 신입 개발자 첫날 실습

### 실습 1: 전체 실행

Docker, 백엔드, 프론트를 순서대로 실행하고 5173, 8080, 5432, 6379의 담당자를 설명한다.

### 실습 2: 조회 흐름 추적

`pages/TodoPage.tsx`에서 시작해 TanStack Query, `todoApi.ts`, `client.ts`, Vite proxy, `RequestTraceFilter`, Security, Controller, Repository, DB까지 파일을 찾아간다.

### 실습 3: 인증 생성

PowerShell에서 Basic 인증으로 Todo를 만들고 응답의 `createdAt`, `updatedAt`, `X-Request-Id`를 확인한다.

### 실습 4: 오류 추적

존재하지 않는 UUID를 조회하고 응답 `traceId`와 서버 로그의 `traceId`를 연결한다.

### 실습 5: 검증 오류

공백 title과 size 101 요청을 보내고 `VALIDATION_ERROR`의 `errors` 구조를 비교한다.

### 실습 6: migration 읽기

V1~V3 SQL을 읽고 V3가 컬럼 추가, 데이터 보정, NOT NULL 순서를 사용하는 이유를 설명한다.

### 실습 7: 테스트 격리

개발 PostgreSQL 데이터와 Testcontainers DB가 서로 영향을 주지 않는 이유를 설명한다.

### 실습 8: OpenAPI

TodoResponse에 새 필드를 추가한다고 가정하고 백엔드, YAML, 생성 타입, 프론트, 테스트 변경 순서를 적는다.

## 23. 완료 체크리스트

- [ ] Docker CLI와 Docker Engine의 차이를 설명할 수 있다.
- [ ] 5173, 8080, 5432, 6379의 담당 프로세스를 말할 수 있다.
- [ ] Vite proxy를 거쳐 요청이 백엔드로 이동하는 과정을 설명할 수 있다.
- [ ] Flyway와 Hibernate `ddl-auto=validate`의 역할 차이를 설명할 수 있다.
- [ ] Testcontainers DB가 개발 DB와 분리되는 이유를 설명할 수 있다.
- [ ] GET은 공개이고 POST는 인증이 필요함을 확인했다.
- [ ] Problem Details의 `code`와 `traceId`를 설명할 수 있다.
- [ ] `PageResponse`의 각 필드 의미를 설명할 수 있다.
- [ ] `BaseTimeEntity`가 생성·수정 시각을 기록하는 방식을 이해했다.
- [ ] OpenAPI에서 TypeScript 타입을 생성하는 이유를 이해했다.
- [ ] local Basic과 prod OIDC profile의 차이를 설명할 수 있다.
- [ ] Router page, TanStack Query, API client의 책임을 구분할 수 있다.
- [ ] 운영 Compose에서 frontend, backend, PostgreSQL health 순서를 설명할 수 있다.
- [ ] 전체 검증 스크립트를 실행하고 실패 로그를 읽을 수 있다.

## 24. 용어 사전

- **API**: 프로그램끼리 약속된 형식으로 요청하고 응답하는 접점
- **Container**: 애플리케이션과 실행 환경을 격리한 프로세스 단위
- **Image**: 컨테이너를 만들기 위한 읽기 전용 설계도
- **Volume**: 컨테이너가 없어져도 데이터를 보존하는 저장 공간
- **Migration**: DB 스키마 변경을 순서와 이력으로 관리하는 SQL
- **Entity**: DB 테이블과 연결되는 Java 객체
- **Repository**: Entity 저장·조회 코드를 추상화한 계층
- **DTO**: 계층 또는 API 사이에서 전달하는 데이터 모양
- **CORS**: 브라우저의 다른 origin 요청에 적용되는 접근 정책
- **Actuator**: 상태와 운영 지표를 제공하는 Spring 기능
- **Problem Details**: HTTP API 오류를 일정한 JSON 필드로 표현하는 표준
- **traceId**: 하나의 요청, 응답, 로그를 연결하는 식별자
- **OpenAPI**: endpoint와 요청·응답 형식을 기술하는 API 계약
- **JPA Auditing**: Entity 생성·수정 시각 등을 자동 기록하는 기능
- **CI**: 코드 변경마다 자동으로 빌드와 테스트를 실행하는 절차
- **OIDC**: 외부 인증 공급자를 통해 로그인 신원을 전달하는 표준

## 25. 운영 이미지와 Compose 템플릿

`backend/Dockerfile`은 깨끗한 Java 21 빌드 stage에서 Boot JAR을 만들고, 더 작은 JRE stage에 결과만 복사한다. 런타임은 `spring` 비루트 사용자이며 컨테이너 메모리 기준 최대 heap 비율을 지정한다.

`frontend/Dockerfile`은 Node 24 stage에서 TypeScript와 Vite build를 실행하고 정적 결과만 비루트 Nginx 이미지로 복사한다. Nginx는 다음 일을 담당한다.

- 8080 내부 포트에서 정적 React 파일 제공
- 새로고침한 SPA URL을 `index.html`로 돌리는 fallback
- `/api/**`와 공개 health를 내부 backend로 reverse proxy
- CSP, frame 차단, content type, referrer 보안 헤더
- 외부에 Prometheus endpoint를 노출하지 않음

운영 Compose 문법을 확인하는 명령:

```powershell
docker compose --env-file .env.example -f compose.prod.yaml config --quiet
```

실제로 실행할 때는 `.env.example`을 `.env`로 복사한 뒤 모든 예제값을 실제 환경값으로 바꾼다. 예제 OIDC issuer는 동작하지 않는다.

```powershell
Copy-Item .env.example .env
docker compose -f compose.prod.yaml up --build -d
```

Compose는 단일 서버용 실행 템플릿이다. 실제 운영 플랫폼을 고르면 TLS/DNS, Secret Manager, DB 관리형 서비스와 백업, 이미지 registry, 배포 승인, rollback을 그 플랫폼 방식으로 바꿔야 한다.

## 26. 다음 개발 단계

환경과 공통 기반 다음에는 실제 제품 요구사항에 따라 다음을 결정한다.

- 도메인 모델과 도메인별 오류 코드
- 실제 OIDC 공급자와 audience/scope/role 권한 매핑
- 로그인 UX와 OIDC SDK의 access token provider 연결
- UI 컴포넌트와 접근성 기준
- OpenTelemetry exporter와 로그·메트릭·트레이스 저장소 및 경보
- SBOM, 이미지 취약점 스캔, 서명
- Secret Manager, 배포 환경, 롤백 절차
- Spring Modulith 모듈 경계와 ArchUnit 검증

처음부터 모든 기술 이름을 외우려 하지 말고 하나의 브라우저 요청을 파일과 로그에서 끝까지 추적하는 연습을 반복한다. 요청 흐름과 책임 경계를 이해하면 도구 버전이 바뀌어도 문제를 해결할 수 있다.
