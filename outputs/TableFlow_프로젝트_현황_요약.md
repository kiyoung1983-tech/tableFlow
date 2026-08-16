# TableFlow 프로젝트 현황 요약

작성 기준일: 2026-08-17  
기준 브랜치: `feature/tableflow-mvp`  
원격 저장소: `https://github.com/kiyoung1983-tech/tableFlow`

## 1. 현재 결론

TableFlow는 식당 예약 관리 MVP의 설계 단계부터 고객 예약, 관리자 운영, 운영 보안과 배포 전 검증까지 M0~M11 기준선이 구현된 상태다. 초기 공통 구조 검증용 Todo는 예약 도메인 회귀 테스트가 같은 책임을 대체한 뒤 새 Flyway migration으로 제거했다.

현재 구현은 다음 흐름을 제공한다.

- 고객: 활성 지점 조회 → 날짜·인원별 가용 시간 조회 → 개인정보 동의 → 예약 생성
- 고객: 예약 번호와 일회성 관리 토큰으로 조회 → 마감 전 변경 또는 취소
- 관리자: 식당·지점·테이블·영업시간·차단·예약 정책 설정
- 관리자: 날짜별 운영 보드 → 확정 → 착석 → 완료 또는 취소·노쇼 처리
- 운영자: 사전설정 검증 → 읽기 전용 smoke → 승인된 합성 데이터 쓰기 UAT

결제, 대기열, 알림 발송, 여러 테이블 결합, 지점별 관리자 권한은 현재 MVP 범위에 포함하지 않는다.

## 2. 기술 구성

### 백엔드

- Java 21, Spring Boot 4.1
- Spring MVC, Spring Data JPA, Spring Security, Actuator
- PostgreSQL 17, Flyway, Testcontainers
- 로컬 HTTP Basic과 운영 OIDC JWT Resource Server 프로필
- RFC 9457 Problem Details와 `X-Request-Id` 기반 요청 추적

### 프런트엔드

- React 19, TypeScript 5.9, Vite 8
- React Router, TanStack Query
- OpenAPI에서 생성한 TypeScript 계약
- 로컬 Basic 관리자 연결과 운영 Authorization Code + PKCE
- 고객 예약 정보와 관리 토큰을 URL이나 브라우저 저장소에 보관하지 않는 메모리 흐름

### 운영 기반

- 비루트 백엔드·Nginx 운영 이미지
- 로컬·운영 Docker Compose
- GitHub Actions 품질 게이트와 Dependabot
- 배포 사전검증, 읽기 전용 smoke, 명시적 쓰기 승인 UAT CLI

## 3. 핵심 도메인과 용어

- `Restaurant`: 하나 이상의 지점을 소유하는 식당 브랜드 또는 운영 단위
- `Branch`: 시간대, 예약 정책, 영업시간을 가지는 실제 예약 지점
- `DiningTable`: 단일 예약에 배정되는 물리 테이블
- `BusinessHours`: 요일별 예약 가능 영업 구간
- `BookingBlock`: 지점 전체 또는 특정 테이블의 예약 차단 구간
- `Reservation`: 고객 정보 스냅샷, 이용·점유 구간, 상태와 배정 테이블을 보관하는 예약
- `ReservationStatusHistory`: 상태 전이의 행위자, 사유와 시각을 기록하는 감사 이력
- `Idempotency-Key`: 네트워크 재시도에서 예약 중복 생성을 막는 요청 단위 UUID
- `Manage Token`: 고객 셀프서비스 조회·변경·취소에 사용하는 일회성 표시 비밀값

## 4. 확정된 예약 정책

- 기본 시간대는 `Asia/Seoul`이며 지점에는 IANA 시간대만 허용한다.
- 기본 슬롯 간격은 30분, 이용 시간은 90분, 정리 시간은 15분이다.
- 최소 60분 전부터 최대 30일 뒤까지 예약할 수 있다.
- 고객 변경·취소는 시작 3시간 전까지 허용한다.
- 노쇼는 확정 예약 시작 15분 뒤부터 관리자 또는 시스템이 처리할 수 있다.
- 예약 인원은 1~8명이며 빈 좌석이 최대 2석인 가장 작은 활성 테이블을 우선한다.
- 같은 고객의 같은 지점 이용 구간이 겹치면 다른 테이블이 있어도 거부한다.
- 고객 중복 판정은 이용 종료까지만 적용하고 정리 시간은 포함하지 않는다.
- 관리자는 마감 뒤에도 사유를 남기고 취소할 수 있지만 수용 인원이나 중복 제약을 우회할 수 없다.

운영 공개 전에는 개인정보 보존 기간, 실제 영업 정책, rate limit, OIDC 역할과 관리자 승인 SLA를 사업·보안 책임자가 다시 승인해야 한다.

## 5. 상태 전이

정상 상태 흐름은 다음과 같다.

```text
PENDING → CONFIRMED → SEATED → COMPLETED
    └──────────────→ CANCELLED
             └────→ NO_SHOW
```

- 생성된 예약은 `PENDING`이며 즉시 좌석을 점유한다.
- `PENDING → CONFIRMED`는 시작 전 관리자만 수행한다.
- `CONFIRMED → SEATED`는 지점 현지 예약일 당일 관리자만 수행한다.
- `SEATED → COMPLETED`는 이용 종료 뒤 관리자만 수행한다.
- 고객 취소는 마감 전 `PENDING`·`CONFIRMED`에만 허용한다.
- `CANCELLED`와 `NO_SHOW`는 좌석을 즉시 해제한다.
- `COMPLETED`는 기록된 `occupiedUntil`까지 DB 점유 제약에 남는다.
- 모든 관리자 전이는 `expectedVersion`으로 낙관적 잠금을 검증한다.
- 같은 목표 상태 재요청은 이력을 중복 기록하지 않고 현재 상태를 반환한다.

## 6. 데이터 무결성과 동시성

예약 생성은 애플리케이션 잠금과 DB 제약을 함께 사용한다.

1. 멱등 키 advisory lock으로 같은 요청의 동시 재시도를 직렬화한다.
2. 정책, 영업시간과 차단 구간을 다시 검증한다.
3. 후보 테이블을 수용 인원과 이름 순으로 잠근다.
4. 잠금 안에서 활성 예약과 차단 겹침을 다시 조회한다.
5. 첫 가용 테이블에 예약과 최초 이력을 저장한다.
6. PostgreSQL exclusion constraint가 테이블 점유와 고객 이용 구간 중복을 최종 방어한다.

시간 구간은 `[start, end)` 반열림 구간이다. 이용 종료와 다음 고객 시작이 같으면 고객 중복은 아니지만, 기존 테이블은 정리 종료까지 점유되므로 다른 가용 테이블이 필요하다.

변경은 예약과 후보 테이블을 같은 트랜잭션에서 다시 잠그고 재배정한다. 취소와 신규 예약이 동시에 실행돼도 최종 활성 예약의 중복이 남지 않는지를 통합 테스트로 검증한다.

## 7. API와 화면 범위

### 공개 API

- `GET /api/public/booking-context`
- `GET /api/public/branches/{branchId}/availability`
- `POST /api/public/reservations`
- `GET|PATCH /api/public/reservations/{reservationCode}`
- `POST /api/public/reservations/{reservationCode}/cancellation`

### 관리자 API

- 식당·지점 생성과 상태·정책 변경
- 테이블 생성·변경
- 주간 영업시간 전체 교체
- 지점·테이블 예약 차단 생성·삭제
- 예약 목록·대시보드·상세·상태 전이

### 주요 화면

- `/reservations/new`: 고객 예약 생성
- `/reservations/manage`: 고객 예약 조회·변경·취소
- `/privacy`: 개인정보 수집·이용 안내
- `/admin/reservations`: 관리자 당일 운영
- `/admin/settings`: 식당·지점·테이블·영업시간·차단 설정
- `/auth/callback`, `/auth/logout-callback`: 운영 OIDC 왕복

정확한 계약은 `openapi/openapi.yaml`을 단일 기준으로 사용한다.

## 8. 보안과 개인정보

- 고객 이름과 전화번호는 AES-GCM으로 암호화해 저장한다.
- 전화번호 중복 판정은 정규화한 값의 HMAC 지문을 사용한다.
- 관리 토큰은 HMAC 해시만 DB에 저장하고 생성 직후 한 번만 원문을 표시한다.
- 예약 번호와 토큰 조합이 틀린 경우 어느 값이 잘못됐는지 구분하지 않는 동일한 404를 반환한다.
- 관리자 API는 로컬 Basic 또는 운영 OIDC 관리자 역할을 요구한다.
- 공개 민감 API에는 경로별 rate limit을 적용한다.
- health는 공개하지만 Prometheus와 그 밖의 Actuator는 인증 또는 내부 네트워크 경계를 요구한다.
- 개인정보 보존 기간이 지난 종료 예약은 배치로 비식별화하고 관리 토큰을 무효화한다.
- AES 이전 키 암호문은 활성 키로 재암호화할 수 있다.

## 9. 테스트와 품질 게이트

자동화 테스트는 다음 층으로 구성한다.

- 백엔드 단위 테스트: 상태 전이, 암호화·정규화·키 회전, OIDC 역할 매핑, rate limit 정리
- 백엔드 통합 테스트: 스키마 제약, 설정·가용성, 예약 생성·변경·취소, 관리자 운영, 보존·키 회전, 동시성
- 프런트 테스트: 고객·관리자 화면, 멱등 재시도, 자격정보 비저장, OIDC와 시간대 변환
- 운영 스크립트 테스트: 사전검증, 읽기 전용 smoke, 쓰기 UAT 안전장치와 정리
- 스테이징 수동 UAT: 실제 IdP 왕복, 브라우저 저장소, 운영 데이터와 로그·관측 검증

전체 로컬 품질 게이트는 다음 명령으로 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

2026-08-17 최신 전체 검증 결과:

- 백엔드 54개 테스트 통과, 실패·오류·건너뜀 0개
- 프런트 8개 테스트 파일, 30개 테스트 통과
- 운영 스크립트 15개 테스트 통과
- OpenAPI 생성 타입 일치, oxlint, TypeScript와 Vite 운영 빌드 통과

로컬 `verify.ps1`은 애플리케이션 테스트와 빌드를 담당한다. 운영 컨테이너 빌드는 GitHub Actions의 `containers` job 또는 별도 Docker 실행으로 확인한다.

상세 테스트 데이터, 절차, 기대 결과와 자동화 매핑은 `outputs/TableFlow_MVP_테스트_케이스.md`에 정리한다.

## 10. 현재 남은 운영 결정

- 실제 OIDC issuer, SPA client, audience, scope와 관리자 역할
- 지점별 관리자 권한 모델
- 개인정보 보존 기간과 파기 승인 주체
- 인그레스·WAF 전역 rate limit과 부하 시험 기준
- 관리자 승인 SLA와 장기 `PENDING` 처리 정책
- 실제 SMS 알림을 붙일 때 발송 실패와 예약 상태의 분리
- 운영 Secret Manager, TLS·DNS, 백업·복구와 롤백 방식
- 접근성, 지원 브라우저와 실제 사용자 인수 기준

이 항목들은 코드 기본값이 있다고 해서 자동 승인된 것으로 간주하지 않는다.

## 11. 다음 권장 순서

1. 팀이 이 요약과 MVP 테스트 명세를 리뷰한다.
2. GitHub Actions에서 전체 품질 게이트를 확인한다.
3. 실제 스테이징 환경의 OIDC·Secret·DB·TLS를 구성한다.
4. 배포 사전검증과 읽기 전용 smoke를 실행한다.
5. 승인된 합성 데이터로 쓰기 UAT와 브라우저 수동 시나리오를 수행한다.
6. 운영 결정과 증적을 체크리스트·런북에 기록한 뒤 릴리스 여부를 판정한다.
