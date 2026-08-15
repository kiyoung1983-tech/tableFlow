# TableFlow 프로젝트 시작 안내

## 1. 프로젝트 목표

TableFlow는 고객이 식당의 예약 가능 시간을 조회하고 예약을 생성·변경·취소하며, 식당 관리자가 지점·테이블·영업시간과 당일 예약을 관리하는 서비스다.

첫 번째 목표는 기능을 많이 넣는 것이 아니라 다음 흐름을 안전하게 완성하는 것이다.

```text
예약 가능 시간 조회 → 예약 생성 → 중복 방지 → 예약 확인 → 방문·취소·노쇼 처리
```

## 2. 현재 준비된 상태

- Java 21, Spring Boot 4.1, PostgreSQL, Flyway, Spring Data JPA
- local/test/prod profile과 local Basic·prod OIDC JWT 보안
- RFC 9457 Problem Details, X-Request-Id, 공통 페이징, JPA 감사 시간
- React 19, TypeScript, React Router, TanStack Query, 공통 API client
- OpenAPI 계약 기반 TypeScript 타입 생성
- Testcontainers 백엔드 통합 테스트와 Vitest 프런트 테스트
- Docker Compose, 운영 Dockerfile, Nginx, GitHub Actions, Dependabot

현재 Todo 코드는 위 공통 흐름을 검증하는 기준 예제다. 예약 요구사항과 API를 설계한 뒤 제거하거나 예약 도메인 테스트로 교체한다.

## 3. MVP 범위

### 고객 기능

- 날짜·시간·인원수로 예약 가능 시간 조회
- 고객 이름·전화번호와 동의 정보를 입력해 예약 생성
- 예약 번호와 확인 수단으로 예약 조회
- 허용된 시간 안에서 예약 변경·취소

### 관리자 기능

- 식당과 지점 기본 정보 관리
- 테이블 이름·수용 인원·사용 가능 여부 관리
- 요일별 영업시간·휴무일·임시 차단 시간 관리
- 예약 확정·착석·완료·취소·노쇼 상태 처리
- 날짜별 예약 목록과 좌석 현황 조회

### 첫 MVP에서 제외

- 예약금과 온라인 결제
- SMS·이메일 실제 발송
- 메뉴 사전 주문
- 여러 식당 사업자를 받는 SaaS 과금
- 테이블 조합과 복잡한 자동 최적화

## 4. 핵심 도메인 후보

```text
restaurant
branch
dining-table
business-hours
customer
reservation
reservation-history
```

예약 상태의 초기 후보는 다음과 같다.

```text
PENDING → CONFIRMED → SEATED → COMPLETED
                   ↘ CANCELLED
                   ↘ NO_SHOW
```

상태 전이 규칙은 서비스 계층 한곳에서 관리하고 Controller나 화면에서 임의로 상태를 바꾸지 않는다.

## 5. 반드시 먼저 결정할 규칙

- 예약 기본 이용 시간과 마지막 입장 시간
- 예약 간 정리 시간(buffer time)
- 인원수보다 큰 테이블을 어디까지 배정할 수 있는지
- 동일 고객의 중복 예약 허용 여부
- 당일 예약과 취소 가능 마감 시간
- 관리자가 정원을 초과해 예약할 수 있는지
- 노쇼 판정 시점과 고객 제한 정책

시간 구간은 일반적으로 `[시작 시각, 종료 시각)`으로 처리한다. 종료 시각과 다음 예약 시작 시각이 같으면 겹치지 않는 것으로 본다.

## 6. 구현 순서

1. 요구사항과 용어 사전 확정
2. ERD와 예약 중복 방지 전략 설계
3. OpenAPI 계약 작성
4. 식당·지점·테이블·영업시간 migration과 Entity 구현
5. 예약 가능 시간 조회 구현
6. 예약 생성과 동시성 테스트 구현
7. 고객·관리자 화면 구현
8. 취소·노쇼·이력과 운영 지표 구현

## 7. 새 채팅의 첫 요청

```text
TableFlow 식당 예약 관리 프로젝트 개발을 시작하자.

현재 코드와 outputs/TableFlow_프로젝트_시작안내.md를 먼저 확인해 줘.
기존 Todo는 공통 구조 검증 예제이므로 바로 삭제하지 말고 교체 계획을 세워 줘.

다음 순서로 진행해 줘.
1. MVP 요구사항과 용어 확정
2. 예약 정책과 상태 전이 정의
3. ERD 및 동시성·중복 예약 방지 전략 설계
4. OpenAPI 초안과 구현 마일스톤 작성

설계 내용을 먼저 문서로 정리하고 검토한 뒤 구현을 시작해 줘.
```

