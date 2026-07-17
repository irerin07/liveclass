# Plan — 알림 발송 시스템 개발 계획

> 본 문서는 spec.md를 구현하기 위한 단계별 개발 계획이다.
> **가장 단순한 동기 CRUD에서 시작해 phase마다 기능을 한 겹씩 추가**하는 방식으로 진행하며,
> 각 phase는 "그 시점까지의 코드만으로도 빌드·테스트가 통과하는" 완결 상태로 끝난다.
> phase 종료 = 커밋 포인트이다. spec의 FR/NFR 식별자를 각 phase에 매핑한다.

## 진행 원칙

1. **한 phase에 한 가지 복잡도만 추가한다.** 예: 비동기화(Phase 3)와 재시도(Phase 4)를 한 번에 만들지 않는다.
2. **각 phase의 완료 기준은 테스트다.** spec의 AC 중 해당 phase 몫을 테스트로 옮기고 통과시킨 후 다음 phase로 넘어간다.
3. **되돌아가지 않도록 인터페이스를 먼저 세운다.** Phase 1에서 만든 API 계약과 엔티티는 이후 phase에서 컬럼/상태 추가만 있고 파괴적 변경은 없도록 설계한다.
4. 선택 구현(Phase 7)은 Phase 6까지의 품질이 확보된 경우에만 착수한다. 시간 부족 시 문서 답변으로 대체한다(spec §4 선택 구현 정책).

## Phase 개요

| Phase | 주제 | 추가되는 복잡도 | spec 매핑 |
| --- | --- | --- | --- |
| 0 | 프로젝트 골격 + 기술 검증 | — | R-1, NFR-7 |
| 1 | 동기 CRUD: 요청 등록 + 조회 | 도메인 모델, 상태 정의 | FR-1(부분), FR-2 |
| 2 | 멱등성 (중복 요청 방지) | 동시성 (요청 경로) | FR-1(완성), FR-6 |
| 3 | 비동기 발송 (성공 경로만) | 폴러/워커, 클레임, 트랜잭션 분리 | FR-7, FR-4(부분) |
| 4 | 실패 처리와 재시도 | 실패 주입, 백오프, 최종 실패 | FR-5, FR-9, FR-4(완성) |
| 5 | 운영 시나리오 | 스턱 회수, 재시작, 다중 인스턴스 | FR-8 |
| 6 | 사용자 목록 조회 + 읽음 처리 | Querydsl 동적 쿼리, 페이지네이션 | FR-3, OPT-2 |
| 7 | 선택 구현 | 수동 재시도, 예약 발송 | OPT-1, OPT-3 |
| 8 | 문서화 + 최종 검증 | — | 제출물 체크리스트 전체 |

---

## Phase 0 — 프로젝트 골격 + 기술 검증

**목표**: 빌드/실행/테스트 인프라를 확정하고, 최대 리스크(Boot 4.x ↔ Querydsl 호환성, spec R-1)를 첫날 해소한다.

### 작업

1. Spring Boot 4.x 프로젝트 생성 (Gradle, Java 21)
2. 의존성 구성
   - `spring-boot-starter-webmvc`, `spring-boot-starter-jackson`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`
   - Querydsl: 본가 `com.querydsl:querydsl-jpa:5.1.0:jakarta` 우선 시도
   - 테스트: `spring-boot-starter-test`, Boot 4 테스트 모듈, `testcontainers-mysql`, `testcontainers-junit-jupiter`, `awaitility`
3. **Querydsl 스모크 테스트**: 더미 엔티티 1개 → Q클래스 생성 → `JPAQueryFactory`로 조회 1건 실행까지 통과 확인
   - 실패 시 폴백 1: OpenFeign 포크 `io.github.openfeign.querydsl:querydsl-jpa` 로 교체
   - 실패 시 폴백 2: Querydsl 제거, 해당 쿼리 JPQL로 구현 (결정을 README에 기록)
4. Docker Compose 작성: `mysql:8` + 애플리케이션. `docker compose up` 단일 명령 실행 확인
5. Testcontainers 기반 통합 테스트 베이스 클래스 작성 (`@ServiceConnection` 또는 `@DynamicPropertySource`)
6. 공통 설정: JVM/JDBC 타임존 UTC 고정 (`connectionTimeZone=UTC`), 에러 응답 형식(`code`, `message`)과 `@RestControllerAdvice` 골격

### 기술 메모

- **Querydsl APT (Gradle)**: `annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")` + `jakarta.persistence:jakarta.persistence-api`를 annotationProcessor 경로에 추가. Boot 4는 Hibernate 7 기반이므로 Q클래스 생성은 되어도 런타임(`JPAQueryFactory`)에서 깨질 수 있음 → 스모크 테스트는 반드시 **쿼리 실행까지** 포함
- **패키지 구조** (포트/어댑터 분리를 처음부터 — spec §5.4):
  ```
  com.liveclass.notification
  ├─ api            // Controller, request/response DTO, 예외 핸들러
  ├─ application    // 서비스, 포트 인터페이스 (NotificationSender 등)
  ├─ domain         // 엔티티, 상태 enum, 상태 전이 로직
  ├─ infra
  │   ├─ persistence  // Repository, Querydsl
  │   ├─ worker       // 폴러, 워커, 스턱 회수 (Phase 3, 5)
  │   └─ sender       // Mock 발송기 (Phase 3, 4)
  └─ config         // 스레드풀, 설정 프로퍼티 바인딩
  ```
- 설정은 `@ConfigurationProperties(prefix = "notification")` 클래스 하나로 바인딩 (폴링 주기, 배치 크기, 재시도, 스턱 임계 — 이후 phase에서 필드 추가)

### 완료 기준

- [ ] `./gradlew build` 성공 (Q클래스 생성 포함)
- [ ] Querydsl 스모크 테스트 통과 (또는 폴백 결정 완료 및 기록)
- [ ] `docker compose up` 으로 앱 + MySQL 기동, health check 응답
- [ ] Testcontainers 통합 테스트 1건 (context load) 통과

---

## Phase 1 — 동기 CRUD: 알림 요청 등록 + 조회

**목표**: 비동기 없이, 가장 단순한 형태의 "알림 요청을 저장하고 조회한다"를 완성한다. 이 phase의 API 계약과 엔티티가 이후 모든 phase의 뼈대가 된다.

### 작업

1. 엔티티: `Notification` (spec §5.5의 전체 컬럼 중 이 시점에 필요한 것 + 이후 phase 컬럼도 **미리 전부 정의** — attempt_count, next_attempt_at 등. 스키마 변경 재작업 방지)
2. 상태 enum: `PENDING / PROCESSING / SENT / FAILED`. 초기 구현에서는 상태 전이를 엔티티 메서드로 두고, 최종 구조에서는 claim·stuck recovery는 엔티티 메서드, 발송 결과는 claim token 조건부 bulk UPDATE로 처리한다.
3. `POST /api/notifications`: 검증(@Valid, enum 바인딩 에러 → 400) 후 PENDING 저장, 202 응답. **멱등성은 아직 없음**
4. `GET /api/notifications/{id}`: 상태 단건 조회, 404 처리
5. `notification_attempts` 엔티티도 이 시점에 정의 (기록 로직은 Phase 4)

### 기술 메모

- ID는 `BIGINT AUTO_INCREMENT`. `idempotency_key` 컬럼은 정의하되 UNIQUE 제약은 Phase 2에서 활성화 (혹은 처음부터 걸어두고 Phase 1에서는 키에 UUID 저장 — **후자 권장**, 마이그레이션 불필요)
- 스키마 관리: `ddl-auto=validate` + Flyway `V1__init_schema.sql`. 인덱스 (`status, next_attempt_at`), (`receiver_id, created_at`)와 UNIQUE 제약을 최초 마이그레이션부터 포함
- 시간 필드는 전부 `Instant` ↔ `DATETIME(6)`
- 상태 전이를 엔티티 메서드로 두는 이유: FR-4 AC("정의되지 않은 전이는 실패")를 단위 테스트로 바로 검증 가능

### 완료 기준

- [ ] 상태 전이 단위 테스트: claim·stuck recovery의 허용 전이와 비허용 상태 예외
- [ ] POST → 202 + PENDING 저장, GET → 200 / 404 통합 테스트
- [ ] 검증 실패 → 400 + 통일된 에러 형식

---

## Phase 2 — 멱등성 (중복 요청 방지)

**목표**: FR-6 완성. "동일 이벤트" 요청이 동시에 몰려도 알림은 정확히 1건만 생성된다.

### 작업

1. 멱등성 키 생성 규칙 구현: `(type, refType, refId, receiverId, channel)` 각 필드를 `길이:값`으로 인코딩해 연결 (spec §5.3). `Idempotency-Key` 헤더 오버라이드 지원
2. `idempotency_key` UNIQUE 제약 활성화
3. 등록 흐름을 2중 방어로 변경:
   - 사전 조회 → 존재 시 `202 + 기존 ID + duplicated: true`
   - INSERT 시 `DataIntegrityViolationException` 캐치 → 기존 행 재조회 → 202 응답
4. 동시성 테스트: 동일 키 10-스레드 동시 POST → 1건 생성

### 기술 메모

- 제약 위반 캐치는 **새 트랜잭션에서 재조회**해야 함 — 제약 위반이 발생한 영속성 컨텍스트는 오염 상태이므로, 등록 서비스를 `등록 시도(REQUIRES_NEW)` → 실패 시 `조회` 구조로 분리하거나, 컨트롤러 레벨에서 재조회
- 동시성 테스트 패턴: `ExecutorService` + `CountDownLatch`로 동시 출발 → 응답 수집 → `SELECT COUNT(*)` 검증. Testcontainers MySQL에서 실행 (H2는 잠금/제약 타이밍이 달라 무의미 — NFR-6)
- 유니크 키 길이: `VARCHAR(200)` — MySQL utf8mb4 인덱스 길이 제한(767/3072 bytes) 내

### 완료 기준

- [ ] 동일 키 순차 재요청 → 202 + 기존 ID + `duplicated: true`
- [ ] 동일 키 10-스레드 동시 POST → 알림 1건, 나머지 202 응답 (FR-6 AC)
- [ ] 서로 다른 채널의 같은 이벤트 → 각각 생성됨 (spec §7.1 검증)

---

## Phase 3 — 비동기 발송 (성공 경로만)

**목표**: FR-7 완성. 저장된 PENDING 알림을 별도 워커가 집어가 발송(성공만)하고 SENT로 만든다. **실패/재시도는 다음 phase** — 이번 phase의 Mock 발송기는 항상 성공한다.

### 작업

1. `NotificationSender` 포트 인터페이스 + 구현체:
   - `LoggingEmailSender`: 로그 출력 = 발송 (실패 주입은 Phase 4)
   - `InAppSender`: 저장 = 도달 (spec §7.2 — 동일 파이프라인 통과)
2. 폴러: `@Scheduled(fixedDelayString = "${notification.polling-interval}")` — PENDING & `next_attempt_at <= now` 배치 클레임
3. 클레임 쿼리: `SELECT ... FOR UPDATE SKIP LOCKED` + `LIMIT :batchSize` → PROCESSING 전환 + `processing_started_at` 기록 → 커밋 (TX1)
4. 워커 스레드풀: 클레임된 건을 풀에 위임, **트랜잭션 밖에서** 발송 → 결과 기록 트랜잭션(TX2)에서 SENT 전환
5. `@Scheduled` 진입점과 외부 발송 실행 분리: scheduler는 짧은 claim/recovery만 수행하고 실제 발송은 전용 worker executor에 위임 (NFR-4)

### 기술 메모

- **SKIP LOCKED 구현 선택지** (둘 다 검증 후 택1, 결정 기록):
  - JPA: `@Lock(PESSIMISTIC_WRITE)` + `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))` — Hibernate가 `-2`를 SKIP LOCKED로 해석. Hibernate 7에서의 동작을 스모크로 확인
  - 네이티브 쿼리: `... FOR UPDATE SKIP LOCKED` 명시 — 확실하지만 엔티티 매핑 수동. **불확실하면 네이티브 권장**
- **트랜잭션 경계 (spec §5.2)**: 폴러 메서드에 `@Transactional`을 통으로 걸지 않는다.
  - `NotificationTransactionService.claimBatch()` — `@Transactional`, 클레임 후 `List<ClaimedNotification>` 반환
  - `NotificationWorkerService.process(claim)` — 트랜잭션 없이 발송
  - `NotificationTransactionService.record*(claim, result)` — claim token 조건부 결과 기록 트랜잭션
  - self-invocation 프록시 문제를 피하려고 orchestration과 트랜잭션 책임을 **별도 빈**으로 분리
- 스레드풀: 외부 발송 전용 `ThreadPoolTaskExecutor`. `@Scheduled` 메서드는 짧은 claim/recovery만 수행하고 발송은 executor에 위임
- 테스트에서 폴링 주기 단축: 테스트 프로파일 `notification.polling-interval: 100ms` + Awaitility `await().atMost(5, SECONDS)`

### 완료 기준

- [ ] POST 후 폴링 주기 내 SENT 전환 (Awaitility, FR-7 AC)
- [ ] API 응답이 발송 완료를 기다리지 않음 (응답 시점 상태 = PENDING)
- [ ] 느린 발송(Mock에 지연 주입) 중에도 API 응답 시간 영향 없음 (NFR-1)
- [ ] 배치 크기 초과 PENDING 존재 시 여러 주기에 걸쳐 전부 처리됨

---

## Phase 4 — 실패 처리와 재시도

**목표**: FR-5, FR-9 완성. 발송이 실패할 수 있게 만들고(실패 주입), 설정 기반 단계형 backoff 재시도와 최종 실패 처리를 완성한다.

### 작업

1. 실패 모델: `TransientSendException` (재시도 대상) / `PermanentSendException` (즉시 FAILED) — spec §7.3
2. `LoggingEmailSender`에 실패 주입 규칙 추가: 수신자 ID 패턴 기반 (예: `fail-2-times-*`는 2회차까지 Transient 실패, `fail-permanent-*`는 Permanent 실패). 규칙은 테스트/데모 겸용
3. 수신 가능 검증 포트 `RecipientStatusPort`: **상태 반환 계약** (ACTIVE / WITHDRAWN / NOT_FOUND) + 패턴 스텁(`withdrawn-*` → WITHDRAWN, `ghost-*` → NOT_FOUND). 워커가 **발송 직전** 호출하고 판단은 워커 정책이 수행 — WITHDRAWN → FAILED + `RECIPIENT_GONE`(정상 억제) / NOT_FOUND → FAILED + `RECIPIENT_NOT_FOUND`(데이터 이상, 경고 로그). 둘 다 재시도 없음 (spec §7.7)
4. 결과 기록 로직 확장 (TX2):
   - 성공 → SENT + attempt 기록
   - Transient 실패 & attempt < max → `scheduleRetry()`: PENDING + `next_attempt_at = now + backoff(attemptCount)` + attempt 기록
   - Transient 실패 & attempt ≥ max, 또는 Permanent 실패 → FAILED + `last_error` + attempt 기록
5. 백오프 함수: 지수 (기본 30s → 2m → 10m), `notification.retry.*` 설정 바인딩
6. `GET /api/notifications/{id}` 응답에 시도 이력(attempts) 포함 (FR-2 완성)

### 기술 메모

- 백오프 계산은 순수 함수로 분리 (`BackoffPolicy.delayFor(attemptNo)`) — 단위 테스트로 간격 검증, 시간 흐름 불필요
- **시계 주입**: `Clock`을 빈으로 등록하고 엔티티 전이 메서드가 받도록 함 — 재시도 스케줄/스턱 판정 테스트에서 시간 조작 가능 (Phase 5에서도 사용)
- attempt 기록과 상태 전이는 같은 TX2에서 원자적으로
- 예외 메시지는 `last_error`에 1000자 절단 저장 (컬럼 초과 방지)
- 통합 테스트에서 재시도를 기다리지 않으려면 테스트 프로파일의 백오프를 밀리초 단위로 축소 (`30s→100ms`)

### 완료 기준

- [ ] 2회 실패 → 3회차 성공: 최종 SENT, attempts 3건 (성공 1, 실패 2) (FR-5 AC)
- [ ] max 연속 Transient 실패 → FAILED + last_error + 전체 이력 (FR-5 AC)
- [ ] Permanent 실패 → 재시도 없이 즉시 FAILED (spec §7.3)
- [ ] 탈퇴 수신자(`withdrawn-*`) → 발송기 미호출, 재시도 없이 FAILED + `RECIPIENT_GONE` (spec §7.7)
- [ ] 미존재 수신자(`ghost-*`) → 발송기 미호출, 재시도 없이 FAILED + `RECIPIENT_NOT_FOUND` + 경고 로그 (spec §7.7)
- [ ] `next_attempt_at`이 백오프 정책대로 설정됨 (단위 테스트)
- [ ] 설정 변경으로 재시도 횟수/간격 조정 가능 (FR-5 AC)

---

## Phase 5 — 운영 시나리오 (스턱 회수 · 재시작 · 다중 인스턴스)

**목표**: FR-8 완성. 이 과제의 핵심 변별 요구사항.

### 작업

1. 스턱 회수 스케줄러: `PROCESSING & processing_started_at < now - threshold` → PENDING 복귀 (attempt_count 유지), 회수 사실을 attempt 이력 또는 로그에 기록
2. 재시작 내구성 검증: 별도 구현 없음(DB 큐이므로 자동) — **검증 테스트만 추가**
3. 다중 인스턴스 시뮬레이션 테스트: 워커/폴러 다중 구동으로 SKIP LOCKED 배타성 검증

### 기술 메모

- 스턱 회수도 클레임과 동일하게 SKIP LOCKED 업데이트로 — 회수 스케줄러 자체도 다중 인스턴스에서 돌 수 있으므로 이중 회수 방지
- **회수 ↔ 정상 완료 경쟁**: 회수 시점에 워커가 살아서 TX2를 커밋하려는 경우 → TX2를 `(id, status = PROCESSING, claim_token)` 조건부 UPDATE로 구현해 늦게 도착한 이전 세대 결과가 회수·재처리된 알림을 덮어쓰지 않게 함. at-least-once 잔여 위험은 spec §7.4대로 문서화
- 다중 인스턴스 테스트 방법: 같은 DB를 바라보는 폴러 빈을 테스트에서 수동으로 2개 이상 생성하거나, 클레임 컴포넌트를 N개 스레드에서 직접 호출 → PENDING 100건 투입 → 발송 기록 수집 → 중복/누락 0 검증 (Mock sender가 처리 ID를 `ConcurrentHashMap`에 수집)
- 재시작 테스트: PENDING/PROCESSING(오래된) 데이터를 DB에 직접 심고 `ApplicationContext` 기동 → 전부 SENT 도달 검증
- 스턱 임계 테스트: 주입된 `Clock`을 미래로 이동시키거나 `processing_started_at`을 과거로 직접 UPDATE (후자가 단순 — 권장)

### 완료 기준

- [ ] 스턱 PROCESSING 회수 → 재처리 → SENT (FR-8 AC)
- [ ] 미처리 데이터 존재 상태에서 컨텍스트 기동 → 자동 처리 (FR-8 AC)
- [ ] PENDING 100건 + 클레임 주체 4개 동시 → 중복 0, 누락 0 (FR-8 AC)
- [ ] 회수와 정상 완료가 경쟁해도 상태 이중 전이 없음 (조건부 UPDATE 검증)

---

## Phase 6 — 사용자 알림 목록 + 읽음 처리

**목표**: FR-3, OPT-2 완성. Querydsl이 실제로 쓰이는 phase.

### 작업

1. `GET /api/users/{userId}/notifications`: Querydsl 동적 쿼리 — `read` 필터(IN_APP만 의미), 타입 필터(여유 시), 최신순, 페이지네이션
2. `PATCH /api/notifications/{id}/read`: 멱등 읽음 처리 (spec §7.2)

### 기술 메모

- Querydsl: `BooleanBuilder`로 nullable 파라미터 조건 조립, `offset/limit` + 별도 count 쿼리 (`PageableExecutionUtils`로 count 생략 최적화는 선택)
- 읽음 처리는 엔티티 로드 없이 벌크성 조건부 UPDATE: `UPDATE ... SET read_at = :now WHERE id = :id AND read_at IS NULL` → 다중 기기 동시 요청에서 최초 1회만 기록, 이후에도 200 (0 row여도 성공 응답 — 멱등)
- EMAIL 알림에 읽음 요청 → 400 (`code: CHANNEL_NOT_SUPPORTED`)

### 완료 기준

- [ ] 읽음 필터/페이지네이션 동작 (FR-3 AC)
- [ ] 동시 읽음 요청 N개 → read_at 1회만 기록, 전부 200 (OPT-2)
- [ ] EMAIL 대상 읽음 요청 → 400

---

## Phase 7 — 선택 구현 (시간 허용 시)

**우선순위와 중단 규칙**: OPT-1 → OPT-3 순. Phase 8(문서화)에 최소 하루를 남기고 중단한다. 미구현 항목은 spec §7의 정책 서술로 대체한다.

### OPT-1. 최종 실패 수동 재시도

- `POST /api/notifications/{id}/retry`: FAILED만 허용 (아니면 409), 기존 이력을 보존하는 새 retry cycle 생성 후 PENDING 복귀
- `UNIQUE(notification_id, attempt_no)`와 충돌하지 않도록 `retry_cycle` 또는 전체 증가 attempt 번호 정책을 먼저 도입하고, 근거를 별도 요구사항 해석 문서에 기술

### OPT-3. 발송 예약

- 요청에 `scheduledAt` 추가 → 저장 시 `next_attempt_at = scheduledAt`으로 설정하면 끝 — 기존 폴링 메커니즘 재사용, 신규 컴포넌트 없음
- 과거 시각 요청은 즉시 발송으로 처리 (정책 기록)

### 완료 기준

- [ ] OPT-1: FAILED → 재시도 → SENT 흐름, 비FAILED 대상 409, 이력 보존 확인
- [ ] OPT-3: 미래 예약 건이 예약 시각 전 미발송, 이후 발송

---

## Phase 8 — 문서화 + 최종 검증

**목표**: 제출물 체크리스트(spec §12) 전항목 충족. C는 문서가 추가 필수 제출물이므로 하루를 통째로 배정한다.

### 작업

1. README.md 작성 — 과제 공통 템플릿 10항목:
   - 프로젝트 개요 / 기술 스택 / 실행 방법 (docker compose) / API 목록 및 예시 (curl 포함) / 데이터 모델 설명 (ERD) / 요구사항 해석 및 가정 / 설계 결정과 이유 / 테스트 실행 방법 / 미구현·제약사항 / AI 활용 범위
2. **[C 전용] 비동기 처리 구조 및 재시도 정책 문서**: 아키텍처 다이어그램(mermaid), 상태 머신 표, 트랜잭션 경계, 백오프 정책, 브로커 전환 시나리오 (spec §5.4 표 이관)
3. **[C 전용] 요구사항 해석 및 개선 의견**: `requirements-and-improvements.md` 별도 문서에 at-least-once 한계와 개선 방향(수신측 멱등성 등), 요구사항 자체에 대한 제안
4. 실패 → 재시도 → 성공 데모 절차 작성 (실패 주입 수신자 패턴 이용, curl 시퀀스)
5. 최종 검증: 새 clone → `docker compose up` → 데모 절차 재현 → 전체 테스트 실행
6. AI 활용 범위 기재

### 완료 기준

- [ ] README 템플릿 10항목 + C 전용 제출물 2건 완비
- [ ] clone부터 데모까지 문서만 보고 재현 가능 (직접 수행 검증)
- [ ] `./gradlew test` 전체 통과, main 브랜치 실행 가능 상태

---

## 일정 매핑 (5일)

| 일차 | Phase | 비고 |
| --- | --- | --- |
| 1 | Phase 0 + Phase 1 | R-1 스모크가 오전 최우선. 실패 시 폴백 결정까지 당일 완료 |
| 2 | Phase 2 + Phase 3 | 멱등성 동시성 테스트, 비동기 성공 경로 |
| 3 | Phase 4 + Phase 5 착수 | 재시도 완성, 스턱 회수 |
| 4 | Phase 5 완료 + Phase 6 + Phase 7 | 운영 시나리오 테스트가 밀리면 Phase 7 포기 |
| 5 | Phase 8 | 문서 + 최종 검증. 이 날은 코드 변경 최소화 |

## 기술 스택 요약 (전 phase 공통)

| 구분 | 선택 | 사용 지점 |
| --- | --- | --- |
| Java 21 | record 기반 API·application 데이터 모델, 최신 LTS 런타임 활용 | 전반 |
| Spring Boot 4.x | Web, Data JPA, Validation, Scheduling | 전반 |
| MySQL 8 | `FOR UPDATE SKIP LOCKED`, UNIQUE 제약, DATETIME(6) | Phase 2, 3, 5 |
| JPA/Hibernate | 엔티티 + 상태 전이 메서드, 조건부 벌크 UPDATE | Phase 1, 5, 6 |
| Querydsl | 동적 필터 목록 조회 | Phase 6 (스모크는 Phase 0) |
| Testcontainers | MySQL 통합 테스트 (H2 미사용) | Phase 0~ |
| Awaitility | 비동기 검증 (`Thread.sleep` 금지) | Phase 3~ |
| Docker Compose | 실행 환경 고정 | Phase 0, 8 |
