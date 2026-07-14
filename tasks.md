# Tasks — 알림 발송 시스템 구현 체크리스트

> plan.md의 각 Phase를 실행 가능한 세부 작업으로 분해한 체크리스트이다.
> 작업 단위는 "하나의 커밋 또는 그 이하" 크기로 쪼갰다.
> 각 Phase의 마지막 작업은 항상 **검증(테스트) + 커밋**이다.
> 표기: `T<phase>.<번호>` / ⛳ = phase 완료 게이트 (모두 체크되어야 다음 phase 진행)

---

## Phase 0 — 프로젝트 골격 + 기술 검증

### 프로젝트 생성

- [x] T0.1 Spring Boot 4.x + Java 21 Gradle 프로젝트 생성 — Boot **4.0.7**, `starter-webmvc`(Boot 4에서 `starter-web` 대체), `data-jpa`, `validation`, `actuator`
- [x] T0.2 `.gitignore` 정리 (build/, .idea/, Q클래스 생성 디렉토리 포함)
- [x] T0.3 테스트 의존성 추가 — Boot 4는 Testcontainers **2.x**를 관리하며 아티팩트명이 변경됨: `testcontainers-mysql`, `testcontainers-junit-jupiter` + Awaitility

### Querydsl 스모크 (R-1 — 최우선)

- [x] T0.4 Querydsl 의존성 구성: `com.querydsl:querydsl-jpa:5.1.0:jakarta` + APT annotationProcessor 설정
- [x] T0.5 더미 엔티티(테스트 소스 전용, 앱 패키지 밖 격리) → Q클래스 생성 확인
- [x] T0.6 `JPAQueryFactory` 조회 쿼리 실행 스모크 테스트 통과 (Testcontainers MySQL)
- [x] T0.7 폴백 **불필요** — 본가 5.1.0:jakarta가 Boot 4.0.7/Hibernate 7에서 동작 확인 → [결정 기록] 반영

### 실행 환경

- [x] T0.8 `docker-compose.yml` 작성: `mysql:8` (utf8mb4, 타임존 UTC) + 애플리케이션 서비스 + Dockerfile(멀티스테이지)
- [x] T0.9 `application.yml` 기본 구성: 데이터소스(`connectionTimeZone=UTC`), JPA(`ddl-auto=validate`), sql init
- [x] T0.10 `schema.sql` 초안: `notifications`, `notification_attempts` 테이블 + 인덱스 2종 + UNIQUE 제약 (spec §5.5 그대로)
- [x] T0.11 compose MySQL + 앱 기동 → `/actuator/health` 200 UP 확인. 단, 샌드박스 프록시 제약으로 app 컨테이너 **이미지 빌드**는 미검증 — T8.13 최종 검증에서 재확인

### 공통 기반

- [x] T0.12 패키지 구조 생성: `api / config` (domain/application/infra는 해당 클래스가 생기는 phase에서 생성)
- [x] T0.13 `NotificationProperties` 설정 클래스 골격 (pollingInterval, batchSize — 재시도·스턱 필드는 해당 phase에서 추가)
- [x] T0.14 에러 응답 형식 `{code, message}` + `@RestControllerAdvice` 골격
- [x] T0.15 Testcontainers 통합 테스트 베이스 클래스 (`IntegrationTestSupport`, `@ServiceConnection`)
- [x] T0.16 ⛳ context load 통합 테스트 통과 + **커밋** (`chore: 프로젝트 골격 및 기술 검증`)

---

## Phase 1 — 동기 CRUD: 요청 등록 + 조회

### 도메인

- [x] T1.1 `NotificationStatus` enum (PENDING / PROCESSING / SENT / FAILED)
- [x] T1.2 `NotificationType`, `Channel` enum (EMAIL / IN_APP)
- [x] T1.3 `Notification` 엔티티: spec §5.5 전체 컬럼 정의 (attempt_count, next_attempt_at, processing_started_at 등 이후 phase 컬럼 포함), `idempotency_key` UNIQUE — Phase 1에서는 UUID 저장
- [x] T1.4 상태 전이 메서드 구현: `claim(clock)`, `markSent(clock)`, `scheduleRetry(nextAt, error, clock)`, `markFailed(reason, clock)` — 비허용 전이·시도 예산 소진은 `InvalidStateTransitionException`
- [x] T1.5 `NotificationAttempt` 엔티티 정의 (success/failure 팩토리 포함, 기록 로직은 Phase 4)
- [x] T1.6 상태 전이 단위 테스트 13건: 허용 전이 전체(부수 효과 포함) + 비허용 전이 예외 + 최종 상태 불변 + 1000자 절단

### API

- [x] T1.7 `POST /api/notifications` 요청/응답 DTO (record) + Bean Validation (enum 바인딩 에러 → 400 매핑)
- [x] T1.8 등록 서비스: PENDING 저장 → 202 + `{notificationId, status, duplicated: false}` (멱등성 없음 — Phase 2)
- [x] T1.9 `GET /api/notifications/{id}` 단건 조회 + 404 (`NOTIFICATION_NOT_FOUND`)
- [x] T1.10 통합 테스트 5건: POST 202 → DB PENDING 확인, GET 200/404, 필수 필드 누락 400, 잘못된 enum 400
- [x] T1.11 ⛳ 전체 테스트 통과(19건) + **커밋** (`feat: 알림 요청 등록 및 조회 API`)

---

## Phase 2 — 멱등성 (FR-6)

- [x] T2.1 멱등성 키 생성기: `type:refType:refId:receiverId:channel`, `Idempotency-Key` 헤더 오버라이드 지원. 저장 키는 SHA-256 해시(원시 조합이 컬럼 200자 초과 가능·구분자 충돌 방지) (spec §5.3)
- [x] T2.2 등록 흐름 1차 방어: 사전 조회(`findByIdempotencyKey`) → 존재 시 `202 + 기존 ID + duplicated: true`. 컨트롤러 `Idempotency-Key` 헤더 배선 + 서비스 키 생성/사전 조회
- [ ] T2.3 등록 흐름 2차 방어: INSERT의 `DataIntegrityViolationException` 캐치 → 기존 행 재조회 → 202 응답 (등록 트랜잭션 분리 — plan.md Phase 2 기술 메모의 REQUIRES_NEW 구조)
- [x] T2.4 컨트롤러 상태 코드 분기 제거 → 신규·중복 항상 202 (T2.2에 포함, 리뷰 코멘트 2·3 반영, decisions.md D-1)
- [ ] T2.5 키 오용 처리: 같은 `Idempotency-Key` + 다른 요청 본문 → `422 Unprocessable`
- [x] T2.6 통합 테스트: 동일 키 순차 재요청 → 202 + 기존 ID + `duplicated:true`, 신규 행 없음
- [ ] T2.7 동시성 테스트: 동일 키 10-스레드 동시 POST (`ExecutorService` + `CountDownLatch`) → 알림 정확히 1건, 전 응답 202 (더블클릭·이중제출 시나리오)
- [ ] T2.8 테스트: 같은 이벤트·다른 채널(EMAIL vs IN_APP) → 각각 생성 (spec §7.1)
- [ ] T2.9 ⛳ 전체 테스트 통과 + **커밋** (`feat: 멱등성 기반 중복 요청 방지`)

---

## Phase 3 — 비동기 발송: 성공 경로 (FR-7)

### 발송 포트

- [ ] T3.1 `NotificationSender` 포트 인터페이스 (`send(notification)`) + 채널 라우팅
- [ ] T3.2 `LoggingEmailSender` 구현 (이번 phase는 항상 성공, 로그 출력)
- [ ] T3.3 `InAppSender` 구현 (저장 = 도달, 동일 파이프라인 통과 — spec §7.2)

### 클레임 + 워커

- [ ] T3.4 클레임 쿼리 구현: `status = PENDING AND next_attempt_at <= now` + `FOR UPDATE SKIP LOCKED` + `LIMIT :batchSize` — JPA 힌트(`lock.timeout=-2`) vs 네이티브 쿼리 검증 후 택1, [결정 기록]에 메모
- [ ] T3.5 `NotificationClaimer` 빈: `@Transactional` 클레임 → PROCESSING 전환 + `processing_started_at` 기록 → ID 목록 반환 (TX1)
- [ ] T3.6 `NotificationWorker` 빈: 트랜잭션 **없이** 발송 수행 → 결과를 `ResultRecorder`에 위임
- [ ] T3.7 `ResultRecorder` 빈: `@Transactional`로 SENT 전환 (TX2). 세 빈 분리로 self-invocation 프록시 문제 회피
- [ ] T3.8 폴러: `@Scheduled(fixedDelayString = "${notification.polling-interval}")` → 클레임 → 워커 스레드풀 위임
- [ ] T3.9 스레드풀 구성: `ThreadPoolTaskExecutor`(워커) + `ThreadPoolTaskScheduler` size ≥ 2 (NFR-4), 설정 프로퍼티 바인딩 (폴링 주기, 배치 크기, 워커 스레드 수)

### 검증

- [ ] T3.10 테스트 프로파일: `polling-interval: 100ms` + Awaitility 셋업
- [ ] T3.11 통합 테스트: POST → 폴링 주기 내 SENT 전환 (Awaitility)
- [ ] T3.12 통합 테스트: 응답 시점 상태 = PENDING (API가 발송을 기다리지 않음)
- [ ] T3.13 통합 테스트: Mock에 지연 주입 → API 응답 시간 영향 없음 (NFR-1)
- [ ] T3.14 통합 테스트: 배치 크기 초과 PENDING → 여러 주기에 걸쳐 전량 처리
- [ ] T3.15 ⛳ 전체 테스트 통과 + **커밋** (`feat: 폴링 워커 기반 비동기 발송 (성공 경로)`)

---

## Phase 4 — 실패 처리와 재시도 (FR-5, FR-9)

### 실패 모델

- [ ] T4.1 `TransientSendException` / `PermanentSendException` 정의 (spec §7.3)
- [ ] T4.2 `LoggingEmailSender` 실패 주입: 수신자 패턴 규칙 (`fail-2-times-*` → 2회차까지 Transient, `fail-permanent-*` → Permanent). 테스트/데모 겸용
- [ ] T4.3 `Clock` 빈 등록 및 시간 사용 지점 전부 주입으로 전환 (엔티티 전이 메서드 포함)

### 수신 가능 검증 (spec §7.7)

- [ ] T4.4 `RecipientStatusPort` 포트 인터페이스 — 상태 반환 계약 (ACTIVE / WITHDRAWN / NOT_FOUND) + 패턴 스텁 구현 (`withdrawn-*` → WITHDRAWN, `ghost-*` → NOT_FOUND, 그 외 ACTIVE)
- [ ] T4.5 워커 발송 직전 검증 정책: WITHDRAWN → FAILED + `RECIPIENT_GONE`(정상 억제) / NOT_FOUND → FAILED + `RECIPIENT_NOT_FOUND`(데이터 이상, 경고 로그) — 둘 다 발송기 미호출·재시도 없음, attempt 이력 1건 기록

### 재시도

- [ ] T4.6 `BackoffPolicy` 순수 함수: 지수 백오프 (기본 30s → 2m → 10m), `notification.retry.*` 설정 바인딩 + 간격 단위 테스트
- [ ] T4.7 `ResultRecorder` 확장: 성공 → SENT / Transient & attempt < max → `scheduleRetry` / Transient & attempt ≥ max 또는 Permanent → FAILED + `last_error`(1000자 절단)
- [ ] T4.8 attempt 기록: 모든 시도를 `notification_attempts`에 TX2와 동일 트랜잭션으로 기록
- [ ] T4.9 `GET /api/notifications/{id}` 응답에 시도 이력 포함 (FR-2 완성)

### 검증

- [ ] T4.10 테스트 프로파일 백오프 축소 (100ms 단위)
- [ ] T4.11 통합 테스트: 2회 실패 → 3회차 성공 → SENT + attempts 3건(실패 2, 성공 1)
- [ ] T4.12 통합 테스트: max 연속 Transient 실패 → FAILED + last_error + 전체 이력
- [ ] T4.13 통합 테스트: Permanent 실패 → 재시도 없이 즉시 FAILED
- [ ] T4.14 통합 테스트: 탈퇴 수신자(`withdrawn-*`) → 발송기 미호출·FAILED + `RECIPIENT_GONE` / 미존재 수신자(`ghost-*`) → 발송기 미호출·FAILED + `RECIPIENT_NOT_FOUND` + 경고 로그
- [ ] T4.15 단위 테스트: `next_attempt_at` 이 백오프 정책대로 설정
- [ ] T4.16 테스트: 설정 변경(횟수/간격)이 동작에 반영됨
- [ ] T4.17 ⛳ 전체 테스트 통과 + **커밋** (`feat: 발송 실패 재시도·최종 실패·수신 가능 검증`)

---

## Phase 5 — 운영 시나리오 (FR-8)

### 스턱 회수

- [ ] T5.1 상태 전이를 조건부 UPDATE(`WHERE status = 'PROCESSING'`)로 보강 — 회수 ↔ 정상 완료 경쟁 시 이중 전이 방지 (plan.md Phase 5 기술 메모)
- [ ] T5.2 스턱 회수 스케줄러: `PROCESSING AND processing_started_at < now - threshold` → SKIP LOCKED로 PENDING 복귀 (attempt_count 유지), 회수 로그/이력 기록
- [ ] T5.3 스턱 임계(`notification.stuck-threshold`, 기본 5분) 설정 바인딩

### 검증

- [ ] T5.4 통합 테스트: `processing_started_at`을 과거로 직접 UPDATE → 회수 → 재처리 → SENT
- [ ] T5.5 통합 테스트: 회수와 정상 완료 경쟁 → 상태 이중 전이 없음 (조건부 UPDATE 검증)
- [ ] T5.6 재시작 내구성 테스트: PENDING/스턱 PROCESSING 데이터를 DB에 심고 컨텍스트 기동 → 전량 SENT
- [ ] T5.7 다중 인스턴스 테스트: PENDING 100건 + 클레임 주체 4개 동시 실행 → Mock sender의 `ConcurrentHashMap` 수집 결과 중복 0·누락 0
- [ ] T5.8 ⛳ 전체 테스트 통과 + **커밋** (`feat: 스턱 회수·재시작 내구성·다중 인스턴스 대응`)

---

## Phase 6 — 사용자 목록 조회 + 읽음 처리 (FR-3, OPT-2)

- [ ] T6.1 Querydsl 동적 쿼리: 수신자별 목록, `read` 필터(IN_APP), 최신순, 페이지네이션 (`BooleanBuilder` + offset/limit + count)
- [ ] T6.2 `GET /api/users/{userId}/notifications` API + 응답 DTO
- [ ] T6.3 읽음 처리: 벌크 조건부 UPDATE (`SET read_at = :now WHERE id = :id AND read_at IS NULL`) — 0 row여도 200 (멱등)
- [ ] T6.4 `PATCH /api/notifications/{id}/read` API — EMAIL 대상이면 400 (`CHANNEL_NOT_SUPPORTED`), 404 처리
- [ ] T6.5 통합 테스트: 필터/페이지네이션 동작
- [ ] T6.6 동시성 테스트: 동시 읽음 요청 N개 → `read_at` 1회만 기록, 전부 200
- [ ] T6.7 ⛳ 전체 테스트 통과 + **커밋** (`feat: 사용자 알림 목록 조회 및 읽음 처리`)

---

## Phase 7 — 선택 구현 (시간 허용 시에만, OPT-1 → OPT-3 순)

> 중단 규칙: Phase 8에 최소 하루를 남기고 중단. 미구현 항목은 README에 정책 서술로 대체 (spec §7.5 등).

### OPT-1. 수동 재시도

- [ ] T7.1 `POST /api/notifications/{id}/retry`: FAILED만 허용(아니면 409), attempt_count 초기화 + 기존 이력 보존, PENDING 복귀 (spec §7.5)
- [ ] T7.2 통합 테스트: FAILED → 수동 재시도 → SENT, 이력 보존, 비FAILED 대상 409
- [ ] T7.3 **커밋** (`feat: 최종 실패 알림 수동 재시도`)

### OPT-3. 발송 예약

- [ ] T7.4 요청에 `scheduledAt` 추가 → `next_attempt_at = scheduledAt` (과거 시각은 즉시 발송, 정책 기록)
- [ ] T7.5 통합 테스트: 예약 시각 전 미발송 / 이후 발송
- [ ] T7.6 **커밋** (`feat: 발송 예약`)

---

## Phase 8 — 문서화 + 최종 검증

### README (과제 공통 템플릿 9항목)

- [ ] T8.1 프로젝트 개요 / 기술 스택
- [ ] T8.2 실행 방법 (docker compose 단일 명령 + 로컬 실행 대안)
- [ ] T8.3 API 목록 및 예시 (엔드포인트별 curl + 응답 샘플)
- [ ] T8.4 데이터 모델 설명 (ERD — mermaid, 인덱스 설계 이유 포함)
- [ ] T8.5 요구사항 해석 및 가정 (spec §7 이관: 동일 이벤트 정의, 채널별 차이, 실패 분류, at-least-once, 수동 재시도 정책, 보관 정책, 수신 가능 여부 확인 책임 §7.7)
- [ ] T8.6 설계 결정과 이유 (**decisions.md 이관** — D-1 중복 요청 처리 연쇄 포함, 아웃박스 선택, RETRY_WAIT 미도입, 트랜잭션 3단 분리, tasks.md [결정 기록] 반영)
- [ ] T8.7 테스트 실행 방법 (Testcontainers 요구사항 — Docker 필요 명시)
- [ ] T8.8 미구현 / 제약사항 (Phase 7 미착수분의 정책 서술 포함)
- [ ] T8.9 AI 활용 범위 (사용 도구·범위·직접 검증/수정 내용 구체적으로)

### C 전용 추가 제출물

- [ ] T8.10 비동기 처리 구조 및 재시도 정책 문서: 아키텍처 다이어그램(mermaid), 상태 머신 전이 표, 트랜잭션 경계, 백오프 정책, 브로커 전환 시나리오 (spec §5.4 표)
- [ ] T8.11 요구사항 해석 및 개선 의견: at-least-once 한계와 개선 방향(수신측 멱등성, dedupe), 탈퇴 이벤트 연동에 의한 선제 취소(CANCELED 상태 도입)·탈퇴자 알림 데이터 파기 정책(§7.6·§7.7), 수신 가능 정책의 알림 타입 차원(거래성 vs 마케팅성 — 탈퇴자에게도 가야 하는 알림), 요구사항 자체에 대한 제안

### 데모 + 최종 검증

- [ ] T8.12 실패 → 재시도 → 성공 데모 절차 작성 (실패 주입 수신자 패턴 + curl 시퀀스)
- [ ] T8.13 새 clone → `docker compose up` → 데모 절차 재현 (문서만 보고 수행)
- [ ] T8.14 `./gradlew test` 전체 통과 확인
- [ ] T8.15 main 브랜치 머지 → main 기준 실행 가능 상태 확인 (과제 제출 요건)
- [ ] T8.16 ⛳ 제출물 체크리스트(spec §12) 전항목 대조 + **커밋** (`docs: README 및 제출 문서 작성`)

---

## 결정 기록

> 진행 중 내린 기술 결정을 여기에 누적한다. Phase 8에서 README "설계 결정과 이유"로 이관.

- [x] (T0.7) Querydsl 최종 구성: **본가 `com.querydsl:querydsl-jpa:5.1.0:jakarta` 유지.** Boot 4.0.7(Hibernate 7.1) 위에서 Q클래스 생성(APT) + `JPAQueryFactory` 쿼리 실행 스모크 통과 — OpenFeign 포크 폴백 불필요
- [x] (Phase 0) Boot 4 마이그레이션 메모: `spring-boot-starter-web` → `starter-webmvc`, 테스트 슬라이스가 모듈별 아티팩트로 분리(`spring-boot-data-jpa-test`의 `...data.jpa.test.autoconfigure.DataJpaTest`, `spring-boot-jdbc-test`의 `...jdbc.test.autoconfigure.AutoConfigureTestDatabase`), `@EntityScan` → `...persistence.autoconfigure`. Testcontainers 2.x: 아티팩트 `testcontainers-mysql`/`testcontainers-junit-jupiter`, `MySQLContainer` 제네릭 제거, 신규 패키지 `org.testcontainers.mysql`
- [x] (Phase 1) Boot 4는 **Jackson 3**(`tools.jackson.*`) 기반 — 웹 계층 JSON은 `spring-boot-starter-jackson` 명시 추가, import는 `tools.jackson.databind.*`. 반면 Hibernate 7.2의 JSON FormatMapper(payload JSON 컬럼)는 Jackson 2 하드와이어 → `com.fasterxml.jackson.core:jackson-databind` (Boot BOM 미관리, 버전 명시)를 **Hibernate 전용 runtimeOnly**로 추가. 웹과 영속 계층이 서로 다른 Jackson을 쓰는 상태를 주석으로 명시
- [x] (Phase 1) MockMvc 테스트: Boot 4에서 `spring-boot-webmvc-test` 아티팩트 + `...webmvc.test.autoconfigure.AutoConfigureMockMvc`
- [x] (Phase 1) 테스트 명명: 한글 메서드명은 유지하되 `@Nested` 클래스명은 영문 + `@DisplayName` — 한글 중첩 클래스명은 클래스 파일명이 되어 비UTF-8 파일시스템에서 컴파일 실패 가능
- [x] (Phase 1, 리뷰 반영) **Lombok 도입** — 프로젝트 컨벤션으로 채택. 빈은 `@RequiredArgsConstructor`, 엔티티는 `@Getter` + `@NoArgsConstructor(access = PROTECTED)`. Querydsl APT + Lombok 동시 애노테이션 프로세싱이 Boot 4/Java 21에서 정상 컴파일 확인 (build.gradle의 annotationProcessor에 lombok 추가). Boot BOM이 Lombok 버전 관리
- [x] (Phase 2, T2.2) **Testcontainers 싱글턴 컨테이너 패턴**: `@Testcontainers` + 베이스 클래스의 static `@Container`는 각 서브클래스 종료 시 공유 컨테이너를 정지시켜, 뒤에 실행되는 테스트 클래스가 죽은 컨테이너에 붙어 연결 타임아웃(실행마다 다른 클래스가 번갈아 실패)이 났다. → static 블록에서 1회 `start()`, 정지 안 함(Ryuk가 JVM 종료 시 회수), `@DynamicPropertySource`로 데이터소스 주입. 내용 기반 멱등성 키 충돌 방지를 위해 `@BeforeEach`에서 테이블 정리(FK 순서 attempts→notifications)
- [ ] (T3.4) SKIP LOCKED 구현 방식: _(미정 — JPA 힌트 vs 네이티브 쿼리)_
