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

- [x] T2.1 멱등성 키 생성기: 필드별 `길이:값` 인코딩, 명시적/자동 키 namespace 분리, `Idempotency-Key` 헤더 오버라이드 지원. 저장 키는 SHA-256 해시 (spec §5.3)
- [x] T2.2 등록 흐름 1차 방어: 사전 조회(`findByIdempotencyKey`) → 존재 시 `202 + 기존 ID + duplicated: true`. 컨트롤러 `Idempotency-Key` 헤더 배선 + 서비스 키 생성/사전 조회
- [x] T2.3 등록 흐름 2차 방어: `NotificationCreationService`의 독립 트랜잭션으로 INSERT 격리, orchestration 서비스는 트랜잭션 밖에서 UNIQUE 충돌을 처리하고 기존 행 반환 (decisions.md D-1)
- [x] T2.4 컨트롤러 상태 코드 분기 제거 → 신규·중복 항상 202 (T2.2에 포함, 리뷰 코멘트 2·3 반영, decisions.md D-1)
- [x] T2.5 키 오용 처리: 같은 `Idempotency-Key` + 다른 요청 본문 → `422 IDEMPOTENCY_KEY_MISUSE`. 별도 fingerprint 컬럼 없이 재조회된 기존 행의 필드(type/refType/refId/receiverId/channel)를 요청과 비교 — 내용 기반 키는 키가 곧 조합 해시라 항상 일치, 명시 헤더 키 재사용 시에만 실제 감지
- [x] T2.6 통합 테스트: 동일 키 순차 재요청 → 202 + 기존 ID + `duplicated:true`, 신규 행 없음
- [x] T2.7 동시성 테스트: 동일 키 10-스레드 동시 등록 (`ExecutorService` + `CountDownLatch`) → 알림 정확히 1건, 전부 같은 ID, 생성 1·중복 9 (더블클릭·이중제출 시나리오, 실 MySQL)
- [x] T2.8 테스트: 같은 이벤트·다른 채널(EMAIL vs IN_APP) → 각각 생성, 2건 (spec §7.1)
- [x] T2.9 ⛳ 전체 테스트 통과(31건) + **커밋**

---

## Phase 3 — 비동기 발송: 성공 경로 (FR-7)

### 발송 포트

- [x] T3.1 `NotificationSender` 포트 인터페이스 (`supportedChannel`, `send`) + `NotificationSenderRouter`(채널별 인덱싱, 미등록/중복 채널 예외)
- [x] T3.2 `LoggingEmailSender` 구현 (EMAIL 채널, 로그 출력 = 발송, 이번 phase는 항상 성공)
- [x] T3.3 `InAppSender` 구현 (IN_APP 채널, 저장 = 도달, EMAIL과 동일 파이프라인 통과 — spec §7.2)

### 클레임 + 워커

- [x] T3.4 클레임 쿼리 `findClaimable`: `status = PENDING AND next_attempt_at <= :now` + `ORDER BY next_attempt_at, id` + `LIMIT :batchSize` + `FOR UPDATE SKIP LOCKED` (네이티브). 선택 조건·정렬·batchSize + SKIP LOCKED 동작(잠긴 행 건너뛰기·비블로킹)을 동시 트랜잭션 테스트로 검증
- [x] T3.5 `NotificationTransactionService`: 짧은 트랜잭션에서 claim, 결과 기록, stuck recovery 수행
- [x] T3.6 `NotificationWorkerService`: 트랜잭션 없이 수신 상태 확인과 발송을 수행하고 결과 기록 위임
- [x] T3.7 결과 기록은 claim token 조건부 UPDATE와 attempt INSERT를 같은 트랜잭션에서 처리
- [x] T3.8 `NotificationScheduler`가 주기적으로 `processBatch`와 stuck recovery를 호출하며 테스트에서는 `processBatch()`를 직접 호출
- [x] T3.9 스레드풀 구성(`WorkerConfig`): 발송 전용 `ThreadPoolTaskExecutor` + `@EnableScheduling`. 테스트는 `@ActiveProfiles("test")`로 스케줄 트리거 제외

### 검증

- [x] T3.10 Awaitility로 워커 비동기 완료 대기. 스케줄러 대신 `processBatch()` 직접 호출로 결정적 검증
- [x] T3.11 통합 테스트: 등록 → processBatch → Awaitility로 SENT 전환 확인
- [x] T3.12 통합 테스트: 등록 직후 상태 = PENDING, sent_at/processing_started_at null (API가 발송을 기다리지 않음)
- [x] T3.13 (설계상 보장) Phase 3에서 register는 발송기를 호출하지 않으므로(발송은 워커 전담) 발송 지연이 API에 영향을 줄 수 없다 — T3.12가 이 분리를 입증. 지연 주입 기반 명시 테스트는 주입 가능한 발송기가 생기는 Phase 4(T4.x)에서 수행 (NFR-1)
- [x] T3.14 통합 테스트: 배치 크기(2) 초과 5건 → 첫 폴링은 2건 제한, 여러 폴링에 걸쳐 전량 SENT
- [x] T3.15 ⛳ 전체 테스트 통과 + **커밋**

---

## Phase 4 — 실패 처리와 재시도 (FR-5, FR-9)

### 실패 모델

- [x] T4.1 `TransientSendException`(재시도 대상) / `PermanentSendException`(즉시 최종 실패) 정의 (spec §7.3)
- [x] T4.2 `LoggingEmailSender` 실패 주입: `fail-permanent-*` → Permanent, `fail-<n>-times-*` → n회차까지 Transient(attemptCount 기반) 후 성공. 단위 테스트 4건
- [x] T4.3 `Clock` 빈(ClockConfig, Phase 1부터) — 프로덕션 코드에 `Instant.now()`/`LocalDateTime.now()` 직접 호출 없음(grep 검증). 엔티티 전이 메서드·claimer·recorder 모두 주입 Clock 경유

### 수신 가능 검증 (spec §7.7)

- [x] T4.4 `RecipientStatusPort` 포트 (상태 반환: ACTIVE/WITHDRAWN/NOT_FOUND) + `PatternRecipientStatusPort` 스텁 (`withdrawn-*`→WITHDRAWN, `ghost-*`→NOT_FOUND, 그 외 ACTIVE). 단위 테스트 3건
- [x] T4.5 워커 발송 직전 검증: RecipientStatusPort 확인 → WITHDRAWN → FAILED + `RECIPIENT_GONE`(억제) / NOT_FOUND → FAILED + `RECIPIENT_NOT_FOUND` + 경고 로그. 둘 다 발송기 미호출·재시도 없음·attempt 1건

### 재시도

- [x] T4.6 `BackoffPolicy` 순수 함수: `notification.retry.backoff` 기반 시도 번호별 대기 시간 계산. `NotificationCreationService`가 생성 시 maxAttempts 저장
- [x] T4.7 `NotificationTransactionService`가 일시 실패는 PENDING으로 예약하고 영구 실패·예산 소진은 FAILED로 기록
- [x] T4.8 attempt 기록: `NotificationAttemptRepository` + 매 시도를 성공/실패 이력으로 TX2와 같은 트랜잭션에 기록 (startedAt=processing_started_at, attemptNo, error)
- [x] T4.9 `GET /api/notifications/{id}` 응답에 `attempts` 배열 포함 (attemptNo/success/started·finishedAt/errorMessage). `NotificationDetail`(notification+attempts)로 조회, 테스트로 재시도 3건 이력 확인 (FR-2 완성)

### 검증

- [x] T4.10 테스트 백오프 축소: `@TestPropertySource("notification.retry.backoff=50ms")`로 재시도가 짧은 간격에 일어나게 하여 Awaitility로 결정적 검증
- [x] T4.11 통합 테스트: fail-2-times → 3회차 성공 SENT + attempts 3건(실패 2, 성공 1)
- [x] T4.12 통합 테스트: max 연속 Transient 실패 → FAILED + last_error + 이력 3건
- [x] T4.13 통합 테스트: Permanent 실패 → 재시도 없이 즉시 FAILED(1시도)
- [x] T4.14 통합 테스트: `withdrawn-*` → FAILED + `RECIPIENT_GONE`(1시도) / `ghost-*` → FAILED + `RECIPIENT_NOT_FOUND` (발송 없이 최종 실패)
- [x] T4.15 단위 테스트: 백오프 시도 번호별 대기 시간·목록 초과 고정 (BackoffPolicyTest, T4.6에 포함)
- [x] T4.16 테스트: `max-attempts=2` 오버라이드 시 2회 시도 후 FAILED (설정 변경이 동작에 반영됨)
- [x] T4.17 ⛳ 전체 테스트 통과(63건) + **커밋**

---

## Phase 4.5 — 리뷰 대응 하드닝 (예상 밖 상황에서 PROCESSING 고착 방지)

> 코드 리뷰에서 발견된 "예외/잘못된 설정/종료 시 알림이 PROCESSING에 갇히는 경로"를
> Phase 5(스턱 회수) 이전에 막는다.

- [x] H1 워커 generic 예외 처리: 예상 밖 `RuntimeException`을 잡아 retryable 실패로 기록(`UNKNOWN` 코드), 결과 기록 자체 실패 시 로그 후 스턱 회수에 위임. `@MockitoBean`으로 수신자 조회 예외 주입 → 고착 없이 재시도 소진 후 FAILED 검증
- [x] H2 `LoggingEmailSender` 안전 파싱: `fail-<n>-times-*`의 n이 int 범위 초과·형식 오류면 `null` 반환 → 실패 주입 없이 정상 발송. 단위 테스트 추가
- [x] H3 설정 fail-fast: `NotificationProperties` `@Validated` + `@Min`/`@NotEmpty` + 양수 Duration 검증(compact constructor). 잘못된 설정(빈/0 backoff, max-attempts=0, batch-size=0)은 기동 실패
- [x] H4 graceful shutdown: 워커 executor `setWaitForTasksToCompleteOnShutdown(true)` + `awaitTerminationSeconds(30)` (종료 시 큐 대기 작업 유실 완화, 강제 종료는 스턱 회수가 담당)
- [x] H5 `UNIQUE(notification_id, attempt_no)` 제약(Flyway V1): 스턱 회수·늦은 결과 경쟁 시 동일 시도 번호 중복 기록을 DB가 차단. 정상 흐름(attempt 1·2·3 구분)에는 영향 없음 확인
- [x] H6 payload 크기 상한 64KB: `RegisterNotificationRequest`에 `@AssertTrue` 검증 → 초과 시 400. 메모리·저장·직렬화 비용 보호. 테스트 1건(70KB → 400)
- [x] H7 멱등성 payload 검증: **명시적 `Idempotency-Key` 재사용 시에만** payload를 구조적 JSON 동등성(`ObjectMapper.readTree().equals()`, 필드 순서 무시)으로 비교 → 다르면 422. 내용 기반 키는 §7.1대로 payload 무시. 테스트 3건(다른 payload→422, 필드 순서만 다름→재생)
- [x] H8 ⛳ 전체 테스트 통과(74건) + **커밋/푸시**
- [x] H9 (follow-up 리뷰) **Flyway 마이그레이션 도입**: `spring.sql.init` 방식 → `db/migration/V1__init_schema.sql` + `spring-boot-flyway`(Boot 4는 자동설정이 별도 모듈)로 전환, `ddl-auto=validate` 유지. **범위: 새 clone(빈 DB) 정상 실행 보장이 목표** — 평가는 fresh clone 기준이므로 이걸로 충분. 이미 테이블이 있는 기존 개발 볼륨은 Flyway 이력이 없어 자동 인수되지 않으므로 `docker compose down -v`로 초기화 필요(README 명시). (기존 DB 무중단 마이그레이션은 V1=원본/V2=변경 분리 + baseline이 필요하나 과제 범위 밖이라 채택 안 함)
- [x] H10 (follow-up 리뷰) 테스트 종료 소음 제거: QuerydslSmokeTest `create-drop`→`create`(정지된 컨테이너에 DROP 시도로 인한 CommunicationsException 제거), 자체 컨테이너라 Flyway 비활성화

---

## Phase 5 — 운영 시나리오 (FR-8)

### 스턱 회수

- [x] T5.1 `claim_token` UUID + `ClaimedNotification` 도입. 결과 조건부 UPDATE가 `(id, PROCESSING, claimToken)`에 일치할 때만 상태·attempt 이력을 기록하고 stale 결과는 폐기
- [x] T5.2 스턱 회수 스케줄러: `PROCESSING AND processing_started_at < now - threshold` → `FOR UPDATE SKIP LOCKED`로 PENDING 복귀(attempt_count 유지), 실패 attempt와 회수 로그 기록
- [x] T5.3 스턱 임계(`notification.stuck-threshold`, 기본 5분)와 회수 주기(`stuck-recovery-interval`, 기본 30초) 설정·양수 검증
- [x] T5.3a executor backlog 오판 방지: executor의 빈 실행 슬롯만큼만 claim하고 queue를 0으로 설정

### 검증

- [x] T5.4 통합 테스트: `processing_started_at`을 과거로 직접 UPDATE → 회수 → 재처리 → SENT
- [x] T5.5 통합 테스트: 회수 후 새 클레임과 이전 워커 결과 경쟁 → stale 결과 UPDATE 0건, 새 상태/토큰 유지, attempt 미기록
- [x] T5.6 재시작 내구성 테스트: PENDING/스턱 PROCESSING 데이터를 DB에 심고 동일 DB를 보는 새 ApplicationContext 기동 → 회수 후 전량 SENT
- [x] T5.7 다중 인스턴스 상당 테스트: PENDING 100건 + 클레임 주체 4개 동시 실행 → 전량 SENT, attempt 100건, 중복 0·누락 0. 큐 클레임/회수 TX는 `READ_COMMITTED`로 설정해 MySQL REPEATABLE_READ의 범위 잠금 deadlock 완화
- [x] T5.7a 워커 4개를 latch로 점유 → 첫 poll 4건만 PROCESSING, 추가 poll 0건, 나머지는 PENDING 유지. 워커가 막힌 동안에도 POST 202 확인(NFR-1)
- [x] T5.8 당시 전체 테스트 통과(81건). Phase 5.5 정리와 멱등 키 경계 테스트 반영 후 현재 74건

---

## Phase 5.5 — Phase 6 전 구조·복잡도 정리

### A. 서비스 경계 단순화

- [x] 등록 트랜잭션 책임을 `NotificationCreationService`로 명확화하고 orchestration은 `NotificationService`에 유지
- [x] 워커 흐름을 `NotificationWorkerService`, 짧은 DB 트랜잭션을 `NotificationTransactionService`로 통합
- [x] 폴링·스턱 복구 스케줄 진입점을 `NotificationScheduler` 하나로 통합
- [x] 기존 semaphore, claim 조건, deadlock 재시도 등 동작 정책을 변경하지 않고 81개 테스트 통과

### B. 오버엔지니어링 제거

- [x] 멱등 등록의 deadlock 재시도 단순화
- [x] worker 용량 제어 정책 단일화
- [x] claim token 중심으로 세대 검증 단순화
- [x] scheduler 전용 pool 및 중복 설정 제거
- [x] 엔티티와 bulk UPDATE에 중복된 상태 전이 정리
- [x] 구조 종속 테스트와 문서 정리

---

## Phase 6 — 사용자 목록 조회 + 읽음 처리 (FR-3, OPT-2)

- [x] T6.1 Querydsl 동적 쿼리: 수신자별 목록, `read` 필터(IN_APP), 최신순, 페이지네이션 (`BooleanBuilder` + offset/limit + count)
- [x] T6.2 `GET /api/users/{userId}/notifications` API + 응답 DTO
- [ ] T6.3 읽음 처리: 벌크 조건부 UPDATE (`SET read_at = :now WHERE id = :id AND read_at IS NULL`) — 0 row여도 200 (멱등)
- [ ] T6.4 `PATCH /api/notifications/{id}/read` API — EMAIL 대상이면 400 (`CHANNEL_NOT_SUPPORTED`), 404 처리
- [x] T6.5 통합 테스트: 필터/페이지네이션 동작
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
- [x] (T3.4) SKIP LOCKED 구현 방식: **네이티브 쿼리 채택.** `@Query(nativeQuery=true)`에 `FOR UPDATE SKIP LOCKED` + `LIMIT` 명시. JPA 힌트(`lock.timeout=-2`) + Pageable 방식은 pagination+lock 경고와 불투명성이 있어 배제. Boot 4/Hibernate 7/MySQL 8에서 정상 동작, Instant 파라미터 바인딩·SKIP LOCKED 비블로킹 동작을 동시 트랜잭션 테스트로 확인. 반드시 호출자 트랜잭션 안에서 실행돼야 잠금 유지
