# Spec — 알림 발송 시스템 (과제 C)

> 본 문서는 백엔드 채용 과제 C(알림 발송 시스템)의 요구사항 명세(Spec)이다.
> 원 과제 요구사항(README.md)을 해석하고, 애매한 부분에 대한 정책 결정과 그 근거,
> 수용 기준(Acceptance Criteria)을 정의하여 구현의 단일 기준점으로 삼는다.

---

## 1. 개요

### 1.1 배경

강의 플랫폼에서는 수강 신청 완료, 결제 확정, 강의 시작 D-1, 취소 처리 등 다양한
이벤트가 발생하며, 이벤트 발생 시 사용자에게 이메일 또는 인앱 알림을 발송해야 한다.

알림은 부가 기능이지만 실패 처리는 부가적이지 않다. 본 시스템은 다음 원칙 위에서
동작해야 한다.

- 알림 처리 실패가 비즈니스 트랜잭션에 영향을 주지 않는다. 단, 예외를 단순히
  무시하는 방식으로 이를 달성하지 않는다.
- 일시적 장애(네트워크 오류, 외부 이메일 서버 장애)에 대해 재시도가 가능하다.
- 동일 이벤트에 대해 알림이 중복 발송되지 않는다.
- 서버 재시작·다중 인스턴스 환경에서도 위 보장이 유지된다.

### 1.2 목표

1. 알림 발송 요청을 접수하고, API 요청 스레드와 분리된 워커가 비동기로 발송한다.
2. 알림의 생애주기(접수 → 처리 → 성공/실패)를 상태 머신으로 관리하고 조회 가능하게 한다.
3. 발송 실패 시 정책에 따라 자동 재시도하며, 최종 실패는 사유와 함께 보존한다.
4. 요청 수준의 중복(동일 이벤트에 대한 다중 요청, 동시 요청 포함)을 차단한다.
5. 서버 재시작 후 미처리 알림이 유실 없이 재처리되고, 다중 인스턴스에서 동일 알림이
   중복 처리되지 않는다.
6. 실제 메시지 브로커 없이 구현하되, 브로커 기반 운영 환경으로 전환 가능한 구조를 갖춘다.

### 1.3 비목표 (Non-Goals)

- 실제 이메일 발송 (Mock/로그 출력으로 대체 — 과제 제약사항)
- 실제 메시지 브로커(Kafka, SQS 등) 설치 및 연동 (전환 가능한 구조만 요구됨)
- 인증/인가 체계 (userId를 헤더/파라미터로 전달하는 수준으로 간략화 — 과제 공통 제약)
- 알림 발송의 exactly-once 보장 (§7.4 참고 — at-least-once + 요청 수준 멱등성으로 정의)
- 이벤트를 발생시키는 원천 도메인(수강 신청, 결제 등)의 구현
  (본 시스템은 발송 요청 API를 진입점으로 하며, 원천 이벤트는 API 호출자로 추상화한다)

### 1.4 기술 스택

| 구분 | 선택 | 비고 |
| --- | --- | --- |
| 언어/런타임 | Java 21 | |
| 프레임워크 | Spring Boot 4.x | 착수 전 Querydsl 호환성 스모크 테스트 필수 (§10.1) |
| DB | MySQL 8.x | `FOR UPDATE SKIP LOCKED` 지원이 클레임 전략의 전제 |
| ORM | JPA/Hibernate + Querydsl | Querydsl은 목록 조회 동적 쿼리에 사용 |
| 테스트 | JUnit 5, Testcontainers(MySQL), Awaitility | SKIP LOCKED는 H2로 검증 불가 |
| 실행 환경 | Docker Compose (app + MySQL) | clone 후 단일 명령 실행 목표 |

---

## 2. 용어 정의

| 용어 | 정의 |
| --- | --- |
| 알림(Notification) | 특정 수신자에게 특정 채널로 전달되어야 할 메시지 단위. 본 시스템의 처리 단위 |
| 이벤트 | 알림 발송의 원인이 된 도메인 사건 (예: 결제 확정). `type + ref` 조합으로 식별 |
| 채널(Channel) | 발송 수단. `EMAIL`, `IN_APP` |
| 멱등성 키 | "동일 이벤트에 대한 동일 알림"을 판정하는 유일 키 (§5.3) |
| 클레임(Claim) | 워커가 처리할 알림 행을 배타적으로 선점하는 행위 |
| 스턱(Stuck) | PROCESSING 상태가 기준 시간 이상 지속되어 워커 사망이 의심되는 상태 |
| 시도(Attempt) | 1회의 발송 실행. 알림 1건은 여러 시도를 가질 수 있다 |

---

## 3. 사용자 및 이해관계자

| 역할 | 니즈 |
| --- | --- |
| 이벤트 발생 시스템 (API 호출자) | 알림 요청을 던지고 즉시 복귀. 자기 트랜잭션이 알림 실패에 영향받지 않아야 함 |
| 수신자 (수강생/크리에이터) | 알림을 중복 없이 수신, 인앱 알림 목록 조회 및 읽음 관리 |
| 플랫폼 운영자 | 알림 상태 추적, 실패 사유 확인, 최종 실패 건 수동 재시도 |

---

## 4. 기능 요구사항

각 요구사항은 `FR-n` 식별자와 수용 기준(AC)을 갖는다. 수용 기준은 곧 테스트 목록이다.

### FR-1. 알림 발송 요청 등록

- 요청: 수신자 ID, 알림 타입, 참조 데이터(refType, refId), 발송 채널(EMAIL / IN_APP)
- 처리: 멱등성 검증 후 알림을 `PENDING` 상태로 저장. **API 요청 스레드에서 발송하지 않는다.**
- 응답: 신규·중복 모두 `202 Accepted` + 알림 ID. 중복(멱등 재생) 여부는 상태 코드가
  아니라 본문 `duplicated` 플래그로 전달한다 (§5.3)

**AC**
- [ ] 유효한 요청 → 202 응답, DB에 PENDING 상태 알림 1건 생성
- [ ] 응답 시점에 발송이 수행되지 않았음 (발송은 워커가 수행)
- [ ] 필수 필드 누락/잘못된 enum 값 → 400 + 일관된 에러 응답 형식
- [ ] 동일 멱등성 키 재요청 → 202 + 기존 알림 ID + `duplicated: true`, 신규 행 미생성

### FR-2. 알림 상태 조회

- 단건 조회: 알림 ID로 현재 상태, 시도 횟수, 최근 실패 사유, 시도 이력 조회
- 존재하지 않는 ID → 404

**AC**
- [ ] 각 상태(PENDING/PROCESSING/SENT/FAILED)의 알림이 정확한 상태 값으로 조회됨
- [ ] 실패 이력이 있는 알림 조회 시 실패 사유가 포함됨

### FR-3. 사용자 알림 목록 조회

- 수신자 기준 목록 조회. 읽음/안읽음 필터, 페이지네이션 지원
- 읽음 개념은 `IN_APP` 채널에만 적용 (§7.2)

**AC**
- [ ] 수신자별 목록이 최신순으로 페이지네이션되어 반환됨
- [ ] `read=true|false` 필터가 IN_APP 알림에 대해 동작함

### FR-4. 알림 상태 관리 (상태 머신)

상태와 전이는 §6에 정의한다. 시스템은 정의된 전이 외의 상태 변경을 허용하지 않는다.

**AC**
- [ ] 정의되지 않은 전이 시도(예: SENT → PENDING)는 실패하며 상태가 변하지 않음
- [ ] 모든 전이는 원자적으로 수행됨 (경쟁 상태에서 이중 전이 없음)

### FR-5. 발송 실패 재시도 및 최종 실패 처리

- 발송 실패 시 설정 기반 단계형 backoff로 자동 재시도. 기본: 최대 3회, 간격 30초 → 2분 → 10분 (설정화)
- 최대 시도 도달 시 `FAILED`(최종 실패)로 전환하고 실패 사유를 기록
- 모든 시도는 `notification_attempts`에 시도 번호, 시각, 결과, 오류 메시지로 기록

**AC**
- [ ] 2회 실패 후 3회차 성공 시나리오: 최종 SENT, 시도 이력 3건 기록
- [ ] 최대 횟수 연속 실패: FAILED 전환, last_error 및 전체 시도 이력 보존
- [ ] 재시도 간격이 정책대로 스케줄됨 (next_attempt_at 검증)
- [ ] 재시도 정책 수치가 설정 파일로 변경 가능함

### FR-6. 중복 발송 방지 (멱등성)

- 동일 이벤트에 대한 알림 요청은 1건만 생성된다. 동시 요청도 포함한다.
- 판정 기준과 방어 계층은 §5.3에 정의한다.

**AC**
- [ ] 동일 멱등성 키로 N개 스레드 동시 POST → 알림 정확히 1건 생성, 나머지는 기존 ID 반환
- [ ] 애플리케이션 사전 검증을 우회한 경쟁 삽입이 DB UNIQUE 제약으로 차단됨

### FR-7. 비동기 처리 구조

- 발송은 API 요청 스레드와 분리된 워커(폴러 + 워커 스레드풀)가 수행한다.
- 큐는 DB 기반 durable work queue이며, 인메모리 큐를 신뢰 저장소로 사용하지 않는다.
- 발송 처리(외부 호출)는 DB 트랜잭션 외부에서 수행한다 (§5.2).

**AC**
- [ ] PENDING 알림이 폴링 주기 내에 워커에 의해 처리됨 (Awaitility 검증)
- [ ] 발송 지연(느린 Mock) 상황에서 API 응답 시간이 영향받지 않음

### FR-8. 운영 시나리오 대응

- **스턱 복구**: PROCESSING 상태가 기준 시간(기본 5분, 설정화)을 초과하면 회수
  스케줄러가 PENDING으로 되돌린다. attempt_count는 유지한다.
- **재시작 내구성**: 서버 재시작 후 PENDING(및 회수된 PROCESSING) 알림이 유실 없이
  재처리된다.
- **다중 인스턴스**: 여러 워커/인스턴스가 동시에 폴링해도 동일 알림은 정확히 한
  워커에만 클레임된다 (`FOR UPDATE SKIP LOCKED`).

**AC**
- [ ] processing_started_at이 임계 초과인 PROCESSING 행이 회수되어 재처리됨
- [ ] PENDING 데이터 존재 상태에서 애플리케이션 재기동 → 자동 재처리
- [ ] PENDING 100건 + 워커 4개 동시 폴링 → 각 알림이 동시에 중복 클레임되지 않고 누락 없이 처리됨
- 외부 발송은 장애 시 재시도될 수 있으므로 at-least-once 의미를 가지며, 수신 측 멱등 처리가 필요하다.

### FR-9. Mock 발송기 및 실패 주입

- EMAIL 발송은 로그 출력으로 대체하되, 재시도·실패 흐름을 시연/테스트할 수 있도록
  실패 주입이 가능해야 한다 (예: 특정 수신자 패턴이면 N회차까지 실패).
- 수신 불가 수신자를 시뮬레이션할 수 있어야 한다
  (`withdrawn-*` → WITHDRAWN, `ghost-*` → NOT_FOUND, §7.7).

**AC**
- [ ] 실패 주입 규칙으로 FR-5의 시나리오를 통합 테스트에서 재현 가능
- [ ] 탈퇴 수신자 → 발송기 미호출, 재시도 없이 FAILED + `RECIPIENT_GONE` (§7.7)
- [ ] 미존재 수신자 → 발송기 미호출, 재시도 없이 FAILED + `RECIPIENT_NOT_FOUND` + 경고 로그 (§7.7)
- [ ] README에 실패 → 재시도 → 성공 흐름의 실행 가능한 데모 절차 포함

### 선택 구현 (우선순위 순, 시간 허용 시)

| ID | 항목 | 비고 |
| --- | --- | --- |
| OPT-1 | 최종 실패 수동 재시도 API | 재시도 횟수 초기화 정책 질문에 대한 답을 겸함 (§7.5) |
| OPT-2 | 읽음 처리 API + 다중 기기 동시 읽음 정책 | 멱등 UPDATE로 처리 (§7.2). 구현이 가볍고 질문형 요구사항에 답이 됨 |
| OPT-3 | 발송 예약 (scheduledAt) | next_attempt_at 메커니즘 재사용으로 저비용 구현 가능 |
| OPT-4 | 알림 템플릿 관리 | 우선순위 최하. 미구현 시 문서로 설계 방향만 기술 |

> 선택 구현 중 질문형 항목(읽음 동시 처리, 수동 재시도 정책)은 구현 여부와
> 무관하게 [requirements-and-improvements.md](requirements-and-improvements.md)에 서술한다.

---

## 5. 시스템 설계 요구사항

### 5.1 아키텍처 개요

```
[API 호출자]
    │  POST /api/notifications
    ▼
[API 레이어] ── 멱등성 검증 → notifications INSERT (PENDING) → 즉시 202
    │                              │
    │                              ▼
    │                    [notifications 테이블 = durable work queue]
    │                              │
    │            ┌─────────────────┤ (다중 인스턴스 각각의 폴러가 경쟁)
    ▼            ▼                 ▼
[폴러 @Scheduled]  SELECT ... WHERE status=PENDING AND next_attempt_at<=now
                   FOR UPDATE SKIP LOCKED → PROCESSING 전환 → 커밋
    │
    ▼
[워커 스레드풀] ── 트랜잭션 밖에서 수신 가능 검증(RecipientStatusPort) 후 NotificationSender 호출
    │
    ├─ 성공 → SENT (커밋)
    └─ 실패 → attempt 기록 + 재시도 스케줄(PENDING) 또는 FAILED (커밋)

[스턱 회수 @Scheduled] ── PROCESSING & processing_started_at < now-5m → PENDING 회수
```

### 5.2 트랜잭션 경계 (필수 준수)

발송(외부 호출)을 DB 트랜잭션 안에 두지 않는다. 처리 흐름은 3개의 짧은
트랜잭션으로 분리한다.

1. **TX1 (클레임)**: SKIP LOCKED 조회 → PROCESSING 전환 + processing_started_at 기록 → 커밋
2. **(트랜잭션 없음)**: 발송 수행
3. **TX2 (결과 기록)**: 시도 이력 기록 + SENT / 재시도 스케줄 / FAILED 전환 → 커밋

근거: 외부 호출을 트랜잭션에 포함하면 외부 지연이 DB 커넥션·락 점유로 전파되고,
발송 성공 후 롤백 시 상태 불일치가 생긴다.

### 5.3 멱등성 설계

- **논리 키 정의**: `type`, `refType`, `refId`, `receiverId`, `channel`을 각각
  `문자열 길이:값`으로 인코딩해 연결한다. 필드 값에 `:`가 포함돼도 경계가 모호하지 않다.
  - 클라이언트가 `Idempotency-Key` 헤더를 명시하면 그 값을 우선 사용한다.
  - 명시적 키와 자동 생성 키에는 각각 `explicit:`, `generated:` namespace를 적용한다.
- **저장 키**: namespace가 적용된 논리 키의 **SHA-256 해시(hex, 64자)**. 긴 원시 조합을
  고정 길이로 저장하며, 개별 필드는 엔티티 컬럼에 그대로 남긴다.
- **방어 계층 (2중)**
  1. 애플리케이션: 저장 전 키 조회, 존재 시 기존 알림 반환 — 일반 경로의 빠른 응답
  2. DB: `idempotency_key` UNIQUE 제약 — 동시 요청 경쟁의 최종 방어선.
     제약 위반 예외를 잡아 기존 행을 재조회 후 반환한다.
- **중복 응답 정책**: 신규와 **동일하게** `202 + 기존 알림 ID`, 재생 여부는 본문
  `duplicated: true`로 표시 (Stripe·IETF Idempotency-Key 방식의 replay).
  - 근거 1 — 응답 멱등성: 같은 요청은 같은 응답을 받아야 한다. 신규 202 / 중복 200으로
    상태 코드가 갈리면 멱등 계약이 깨진다.
  - 근거 2 — 200의 의미 오류: 신규가 202(접수됨, 미완료)인데 중복에 200 OK를 주면
    "완료됨"을 암시하지만 그 알림은 여전히 PENDING이다. 둘 다 202가 일관된다.
  - 근거 3 — 409 미채택: 중복은 오류가 아니라 이미 달성된 성공이다. at-least-once
    수집 엔드포인트에서 중복(타임아웃 재시도·버스 재전달·더블클릭)은 예상된 정상
    트래픽이며, 서버는 이들을 와이어에서 구분할 수 없다. 409는 정상 재시도까지
    에러로 오분류하고 재시도가 필요로 하는 ID를 withhold한다. 자세한 논거는
    decisions.md D-1.
- **키 오용 예외**: 같은 `Idempotency-Key`에 **다른 요청 본문**이 오면 재생이 아니라
  모순이므로 `422 Unprocessable`로 거부한다.

### 5.4 브로커 전환 가능 구조

현재는 `NotificationScheduler`가 DB의 durable work queue를 폴링하고
`NotificationWorkerService`에 처리를 위임한다. 브로커를 도입할 때는 이 진입점을
브로커 컨슈머로 교체하고, 수신자 검증·발송·결과 기록 정책은 그대로 재사용한다.

| 역할 | 현재 구현 (과제) | 브로커 도입 후 |
| --- | --- | --- |
| 작업 저장·선점 | `notifications` 저장 + DB 폴링 + `FOR UPDATE SKIP LOCKED` | 브로커 발행 및 컨슈머 전달 정책으로 대체 |
| 처리 진입점 | `NotificationScheduler` → `NotificationWorkerService` | Broker Consumer → 동일한 처리 흐름 |
| 발송 직전 수신 가능 검증 | `RecipientStatusPort` 패턴 기반 스텁 | 사용자 서비스 조회 또는 사용자 상태 이벤트 프로젝션 |
| 채널 발송 | `NotificationSender`의 EMAIL/IN_APP 구현 | 실제 SMTP·푸시 클라이언트 구현 |
| 결과 기록 | claim token을 비교하는 조건부 상태 변경 + attempt 기록 | 동일한 상태 변경·재시도·stale 결과 폐기 정책 유지 |

현재 구현에는 별도의 `NotificationEnqueuer`나 outbox relay가 없다. 브로커 전환 시
DB 작업 큐를 계속 병행할지, 트랜잭셔널 outbox를 새로 둘지는 브로커 발행의 원자성
요구사항에 따라 결정한다.

### 5.5 데이터 모델

```
notifications
├─ id                     BIGINT PK AUTO_INCREMENT
├─ idempotency_key        VARCHAR(200) NOT NULL, UNIQUE
├─ receiver_id            VARCHAR(50)  NOT NULL
├─ type                   VARCHAR(50)  NOT NULL  -- ENROLLMENT_COMPLETED, PAYMENT_CONFIRMED, COURSE_D1, ENROLLMENT_CANCELLED
├─ channel                VARCHAR(20)  NOT NULL  -- EMAIL / IN_APP
├─ ref_type, ref_id       VARCHAR               -- 참조 데이터 (이벤트/강의 ID 등)
├─ payload                JSON NULL             -- 메시지 파라미터
├─ status                 VARCHAR(20)  NOT NULL -- PENDING / PROCESSING / SENT / FAILED
├─ attempt_count          INT NOT NULL DEFAULT 0
├─ max_attempts           INT NOT NULL          -- 생성 시점 정책값 스냅샷
├─ next_attempt_at        DATETIME(6) NOT NULL  -- 폴링 대상 판정 기준
├─ processing_started_at  DATETIME(6) NULL      -- 스턱 판정 기준
├─ claim_token            VARCHAR(36) NULL      -- 현재 클레임 세대 식별
├─ last_error             VARCHAR(1000) NULL
├─ sent_at                DATETIME(6) NULL
├─ read_at                DATETIME(6) NULL      -- IN_APP 전용
├─ created_at, updated_at DATETIME(6) NOT NULL
└─ INDEX (status, next_attempt_at)              -- 폴링 쿼리용
   INDEX (receiver_id, created_at)              -- 사용자 목록 조회용

notification_attempts
├─ id                     BIGINT PK AUTO_INCREMENT
├─ notification_id        BIGINT FK → notifications
├─ attempt_no             INT NOT NULL
├─ started_at, finished_at DATETIME(6)
├─ success                BOOLEAN NOT NULL
├─ error_message          VARCHAR(1000) NULL
├─ UNIQUE (notification_id, attempt_no)
└─ INDEX (notification_id)
```

시간 컬럼은 UTC 기준 저장(`DATETIME(6)` + 애플리케이션 `Instant`), 표시 변환은
API 레이어 책임으로 한다.

---

## 6. 상태 머신

```
                 클레임(TX1)
  PENDING ────────────────────▶ PROCESSING
     ▲                              │
     │   실패 & attempt < max       ├── 발송 성공 ──▶ SENT  (최종)
     │   (next_attempt_at =        │
     │    now + backoff)           │
     └──────────────────────────────┤
     ▲                              └── 실패 & attempt = max ──▶ FAILED (최종)
     │                                                            │
     │        스턱 회수 (PROCESSING 5분 초과, attempt 유지)         │
     └────────────────────────────────────────────────────────────┘
                        수동 재시도 (OPT-1)
```

| 전이 | 트리거 | 조건 | 부수 효과 |
| --- | --- | --- | --- |
| PENDING → PROCESSING | 폴러 클레임 | next_attempt_at ≤ now | processing_started_at 기록, attempt_count += 1 |
| PROCESSING → SENT | 발송 성공 | — | sent_at 기록, 성공 attempt 기록 |
| PROCESSING → PENDING | 발송 실패 | attempt_count < max_attempts | next_attempt_at = now + backoff(attempt_count), 실패 attempt 기록 |
| PROCESSING → FAILED | 발송 실패 | attempt_count ≥ max_attempts | last_error 확정, 실패 attempt 기록 |
| PROCESSING → PENDING | 스턱 회수 | processing_started_at < now − 임계 | attempt_count 유지, 회수 사유 기록 |
| FAILED → PENDING | 수동 재시도 (OPT-1) | 운영자 요청 | §7.5 정책 적용 |

**설계 결정**: 재시도 대기를 별도 상태(RETRY_WAIT)로 두지 않고
`PENDING + next_attempt_at`으로 표현한다.
- 장점: 상태 수 최소화, 폴링 쿼리 단일화 (`status = PENDING AND next_attempt_at <= now`)
- 트레이드오프: 상태만으로 "첫 대기"와 "재시도 대기"를 구분 못 함 → `attempt_count > 0`으로 구분
- 이 결정과 근거를 README 설계 문서에 기술한다.

---

## 7. 정책 결정 사항 (요구사항 해석)

원 요구사항이 열어둔 부분에 대한 본 구현의 결정이다. 제출용 해석과 개선 의견은
[requirements-and-improvements.md](requirements-and-improvements.md)에 별도로 정리한다.

### 7.1 "동일 이벤트"의 정의

동일 이벤트 = **(알림 타입, 참조 대상, 수신자, 채널)이 모두 같은 요청**.
같은 결제 건에 대한 EMAIL과 IN_APP은 서로 다른 알림으로 취급한다(채널별 발송이
자연스러운 도메인 요구이므로). 클라이언트가 더 정밀한 제어를 원하면
`Idempotency-Key` 헤더로 오버라이드할 수 있다.

### 7.2 채널별 처리 차이

| | EMAIL | IN_APP |
| --- | --- | --- |
| 발송 | Mock 클라이언트 호출 (실패 가능) | 저장 = 도달로 간주하되, 동일 파이프라인 통과 |
| 읽음 | 개념 없음 | read_at으로 관리 |

IN_APP도 동일한 상태 머신·워커 파이프라인을 통과시킨다. 근거: 채널별 분기 최소화,
향후 푸시 게이트웨이 등 실제 발송이 생겨도 구조 변경이 없다.
읽음 처리는 멱등 UPDATE(`SET read_at = now WHERE id = ? AND read_at IS NULL`)로,
다중 기기 동시 읽음 요청 시 최초 1회만 기록되고 이후 요청도 성공(200)으로 응답한다.

### 7.3 실패의 분류

- **일시적 실패** (네트워크 오류, 5xx 상당): 재시도 대상
- **영구적 실패** (수신자 형식 오류, 유효하지 않은 채널 등 4xx 상당): 재시도 없이
  즉시 FAILED. Mock 발송기의 예외 타입으로 구분한다.

근거: 영구 오류를 재시도하는 것은 자원 낭비이며 최종 실패 도달만 지연시킨다.

### 7.4 발송 보장 수준: at-least-once

exactly-once는 분산 환경에서 달성 불가능하다(발송 성공과 상태 커밋 사이의 장애,
스턱 회수 시점의 실제 발송 여부 불확실성). 본 시스템의 보장은 다음과 같이 정의한다.

- **요청 수준**: 동일 이벤트에 대한 알림 레코드는 정확히 1건 (멱등성 키로 보장)
- **발송 수준**: at-least-once — 장애 타이밍에 따라 드물게 재발송 가능
- 완화 수단: 스턱 임계를 발송 타임아웃보다 충분히 크게 설정, 발송 직전 상태 재확인
- 잔여 위험과 근본 해결 방향(수신측 멱등성, 발송 게이트웨이의 dedupe)은
  [requirements-and-improvements.md](requirements-and-improvements.md)에 기술한다.

### 7.5 수동 재시도 시 횟수 초기화 (선택 구현 질문)

수동 재시도는 운영자가 원인(예: 외부 서버 복구)을 확인하고 내리는 새로운 처리 지시이므로,
소진된 자동 재시도 예산과 별도의 retry cycle을 부여한다. 기존 시도 이력은 보존한다.
현재 `UNIQUE(notification_id, attempt_no)`가 있으므로 attempt_count만 0으로 초기화하면 기존
이력과 충돌한다. 구현 시 `retry_cycle`을 추가하거나 전체 이력에서 계속 증가하는 attempt
번호와 cycle 내 시도 번호를 분리해야 한다.

### 7.6 알림 보관

SENT/FAILED 알림은 삭제하지 않고 보존한다(사용자 목록 조회 + 운영 추적의 원천).
대용량 환경에서의 아카이빙 정책은 범위 외로 하되
[requirements-and-improvements.md](requirements-and-improvements.md)에 언급한다.

단, 이 보존 정책은 **탈퇴 사용자의 개인정보 파기 의무와 충돌**한다(알림에는
receiver_id, payload 등 개인정보가 포함됨). 사용자 생애주기가 범위 외이므로 본
과제에서는 구현하지 않되, 실운영 전환 시 탈퇴 이벤트 수신 → 해당 수신자 알림
데이터 파기(또는 비식별화)가 필요함을 별도 요구사항 해석 및 개선 의견 문서에 기술한다.

### 7.7 수신 가능 여부 확인 책임 (발송 직전 검증)

"수신자가 알림을 받을 수 있는 상태인가"(탈퇴, 수신 거부 등)의 확인 책임은 다음과
같이 분리한다.

| 책임 | 주인 | 내용 |
| --- | --- | --- |
| 상태의 원천 | 사용자 도메인 (외부) | 탈퇴·수신 동의·채널 유효성의 single source of truth |
| 판단의 실행 | 알림 시스템 — **발송 직전** | 발송 직전에 원천에 확인하고 발송/중단 결정 |
| 의도의 표명 | 호출자 (이벤트 발생 시스템) | 발송 요청 자체. 수신 가능 여부까지는 책임지지 않음 |

**근거**
- 본 시스템은 접수와 발송 사이에 간격(재시도 포함)이 있는 비동기 시스템이므로,
  접수 시점 검증은 발송 시점의 유효성을 보장하지 못한다(TOCTOU). 검증은 발송
  시점을 소유한 워커가 발송 직전에 수행해야 한다.
- 호출자는 여럿이므로 호출자 책임으로 두면 검증 로직이 중복되고 누락 위험이
  생긴다. 모든 알림이 통과하는 유일한 길목(알림 시스템)이 정책 집행 지점이다.
- 접수 시점의 명백한 무효 거절은 빠른 실패를 위한 최적화일 뿐, 보장은 발송 직전
  검증 한 곳에만 둔다.

**포트 계약 — 불리언이 아니라 상태를 반환한다**

실무에서 탈퇴는 즉시 삭제가 아니라 소프트 삭제(상태 = 탈퇴, 유예 기간 30일~1년
보관)이며, 수신 가능 여부는 탈퇴 외에도 휴면·정지·채널별 수신 거부 등 여러 상태의
함수다. 따라서 포트는 발송 가부(boolean)가 아니라 **수신자 상태를 반환**하고,
발송 여부 판단(정책)은 워커가 내린다 — 포트는 상태의 원천을 대리할 뿐이다.

```
RecipientStatusPort.check(receiverId, channel)
  → ACTIVE / WITHDRAWN / NOT_FOUND   (실운영 확장: DORMANT, SUSPENDED, OPTED_OUT ...)
```

상태 조회 시 탈퇴 여부를 쿼리 필터로 거르지 않고(그러면 "미존재"와 "탈퇴"가
구분되지 않는다) 상태 무관 조회 후 판단한다. 두 경우는 운영상 의미가 다르다:

| 판정 | 의미 | 처리 |
| --- | --- | --- |
| WITHDRAWN | 정상 억제 — 의도된 미발송 | 재시도 없이 FAILED + `RECIPIENT_GONE`, 통계 집계 대상 |
| NOT_FOUND | 데이터 이상 — 잘못된 요청 또는 정합성 훼손 | 재시도 없이 FAILED + `RECIPIENT_NOT_FOUND`, 경고 로그(모니터링 대상) |

**본 과제에서의 구현**
- 사용자 도메인이 존재하지 않으므로 검증 지점만 포트로 드러내고, 스텁 구현은
  수신자 ID 패턴으로 시뮬레이션한다 (`withdrawn-*` → WITHDRAWN, `ghost-*` →
  NOT_FOUND, 그 외 → ACTIVE). 최소 users 테이블을 두는 대안도 있으나, 과제
  제약(인증/인가 간략화)과 범위 통제를 위해 원천은 외부로 남긴다.
- 워커는 발송 직전에 포트를 호출하며, ACTIVE가 아니면 발송기 호출 없이 위 표의
  처리로 종료한다(§7.3의 영구적 실패로 분류).
- 별도 CANCELED 상태를 도입하지 않는다 — 상태 머신 단순화를 우선하며, "실패가
  아니라 발송 사유 소멸"을 구분하는 CANCELED 도입과 탈퇴 이벤트 구독에 의한
  선제 취소는 실운영 개선안으로 별도 요구사항 해석 및 개선 의견 문서에 기술한다.
- 본 과제는 알림 타입 무관 일괄 차단으로 단순화한다. 실운영에서는 수신 가능
  여부가 **(수신자 상태 × 알림 타입)** 의 함수다 — 탈퇴 처리 완료 안내는 탈퇴자
  본인에게 가야 하고, 유예 기간 중 환불 완료·법정 고지 같은 거래성 알림은
  발송돼야 하는 반면 마케팅성 알림은 즉시 차단돼야 한다. 이 타입별 정책
  매트릭스는 별도 요구사항 해석 및 개선 의견 문서에 기술한다.

---

## 8. 비기능 요구사항

| ID | 항목 | 기준 |
| --- | --- | --- |
| NFR-1 | API 응답성 | 발송 요청 API는 발송 수행/외부 지연과 무관하게 즉시 응답 |
| NFR-2 | 폴링 효율 | 클레임 쿼리는 `(status, next_attempt_at)` 인덱스를 사용하고 배치 크기(LIMIT, 기본 50, 설정화)로 제한 |
| NFR-3 | 설정 외부화 | 폴링 주기, 배치 크기, 재시도 횟수/백오프, 스턱 임계, 워커 스레드 수는 `application.yml`로 변경 가능 |
| NFR-4 | 발송 실행 분리 | `@Scheduled` 진입점은 짧은 claim/recovery만 수행하고 외부 발송은 별도 worker executor에 위임 |
| NFR-5 | 관측성 | 주요 상태 전이·시도 결과를 파라미터 로그로 남기고 실패 사유는 DB에 기록 |
| NFR-6 | 테스트 환경 일치 | 동시성·클레임 검증 테스트는 Testcontainers MySQL로 수행 (H2 금지) |
| NFR-7 | 실행성 | `docker compose up` 단일 명령으로 main 브랜치 실행 가능 |

---

## 9. API 명세 (요약)

사용자 목록의 수신자 식별은 `/api/users/{userId}/notifications`의 path variable로 전달한다.
과제 범위에서는 인증·인가와 userId 소유권 검증을 생략한다.
에러 응답은 `{ "code": "...", "message": "..." }` 형식으로 통일.

| # | 메서드 | 경로 | 설명 | 응답 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/api/notifications` | 발송 요청 등록 | 202 (신규·중복 공통, 중복은 `duplicated:true`) / 400 / 422(키 오용) |
| 2 | GET | `/api/notifications/{id}` | 상태 단건 조회 (시도 이력 포함) | 200 / 404 |
| 3 | GET | `/api/users/{userId}/notifications` | 수신자 목록 (`read`, `page`, `size`) | 200 |
| 4 | PATCH | `/api/notifications/{id}/read` | 읽음 처리 (IN_APP, 멱등) | 200 / 404 / 400(EMAIL 대상) |
| 5 | POST | `/api/notifications/{id}/retry` | 미구현 선택 기능의 API 제안 (OPT-1) | 200 / 404 / 409(FAILED 아님) |

**POST /api/notifications 요청 예시**

```json
{
  "receiverId": "student-1",
  "type": "PAYMENT_CONFIRMED",
  "channel": "EMAIL",
  "refType": "ENROLLMENT",
  "refId": "enrollment-42",
  "payload": { "courseTitle": "Spring Boot 입문" }
}
```

**응답 예시 (202)**

```json
{
  "notificationId": 1,
  "status": "PENDING",
  "duplicated": false
}
```

상세 명세(전체 필드, 에러 코드)는 구현 시 README의 "API 목록 및 예시"에 확정한다.

---

## 10. 리스크 및 대응

| ID | 리스크 | 영향 | 대응 |
| --- | --- | --- | --- |
| R-1 | Spring Boot 4.x ↔ Querydsl 호환성 (본가 5.1.0은 Hibernate 7 미대응 가능성) | 빌드 불가로 일정 손실 | **1일차 최우선으로 스모크 테스트** (Q클래스 생성 + 쿼리 1건 실행). 실패 시 OpenFeign 포크(`io.github.openfeign.querydsl`) → 그래도 실패 시 해당 쿼리만 JPQL 대체. 결정을 README에 기록 |
| R-2 | 비동기 테스트의 시간 의존성 (flaky) | 신뢰 불가한 테스트 | `Thread.sleep` 금지, Awaitility 사용, 테스트 프로파일에서 폴링 주기 단축 (예: 100ms) |
| R-3 | SKIP LOCKED 동작이 테스트 DB와 불일치 | 다중 인스턴스 보장 미검증 | Testcontainers MySQL 고정, H2 미사용 |
| R-4 | `@Scheduled` 진입점에서 외부 발송까지 수행해 scheduler가 블로킹 | 폴링·회수 지연 | scheduler는 짧은 claim/recovery만 수행하고 외부 발송은 별도 worker executor에 위임 |
| R-5 | 범위 초과 (선택 구현 욕심) | 필수 품질 저하 | §4 우선순위 준수. 질문형 선택 항목은 문서 답변으로 대체 가능 |

---

## 11. 마일스톤 (5일)

| 일차 | 산출물 | 완료 기준 |
| --- | --- | --- |
| 1 | 프로젝트 세팅, R-1 스모크, Docker Compose, 엔티티/상태 머신 | 빌드 + Q클래스 생성 + 컨테이너 기동 성공 |
| 2 | FR-1, FR-2, FR-6 (요청 API + 멱등성) | 멱등성 동시성 테스트 통과 |
| 3 | FR-4, FR-5, FR-7, FR-9 (워커, 재시도, 실패 주입) | 재시도/최종 실패/다중 워커 테스트 통과 |
| 4 | FR-8 (스턱 회수, 재시작), FR-3, OPT-1~2 | 운영 시나리오 테스트 통과 |
| 5 | 문서 (README 템플릿 10항목 + 비동기 구조/재시도 정책 + 별도 요구사항 해석/개선 의견 문서), 최종 실행 검증 | clone → `docker compose up` → 데모 절차 재현 성공 |

---

## 12. 제출물 체크리스트 (과제 요구사항 대조)

- [ ] Git repository URL — 커밋 히스토리가 작업 단위로 남을 것 (마일스톤 단위 이상으로 쪼갬)
- [x] README.md — 템플릿 10개 항목 전부 포함
- [x] 소스 코드 및 테스트 코드 — §4의 AC가 테스트로 존재
- [x] 실행 방법 — Docker Compose 기준
- [x] API 명세 또는 샘플 요청/응답 — §9 확장
- [x] DB 스키마 또는 ERD 설명 — §5.5 확장
- [x] AI 사용 내역 — 사용 범위와 직접 검증/수정한 내용을 구체적으로 기재
- [x] **[C 전용] 비동기 처리 구조 및 재시도 정책 설명 문서** — §5, §6, FR-5를 README 섹션으로 정리
- [x] **[C 전용] 요구사항 해석 및 개선 의견** — [requirements-and-improvements.md](requirements-and-improvements.md)에 §7 정책과 개선 제안 정리
