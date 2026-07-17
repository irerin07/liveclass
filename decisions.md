# Decisions — 설계 결정 기록

과제 구현에서 선택지가 있었던 결정과 이유를 기록한다. 상세 요구사항과 상태 전이는
`spec.md`에서 요구사항과 수용 기준을 관리하고, 최종 구현 결과는 README와 테스트에서 확인한다.

## D-1. 중복 요청은 멱등 성공으로 응답한다

### 선택

- 신규와 중복 요청 모두 `202 Accepted`를 반환한다.
- 중복 요청에는 기존 알림 ID와 `duplicated: true`를 반환한다.
- 같은 명시적 `Idempotency-Key`를 다른 요청 본문에 재사용하면 `422`를 반환한다.

### 이유

등록 API는 비동기이므로 알림 ID가 상태 조회의 연결 고리다. 호출자가 응답을 받지 못해
재시도하더라도 같은 요청에는 같은 ID를 돌려줘야 한다. 중복은 오류가 아니라 이미 접수된
결과의 replay다.

### 구현

1. 멱등성 키로 기존 알림을 먼저 조회한다.
2. 없으면 `NotificationCreationService`의 독립 트랜잭션에서 INSERT한다.
3. 동시 요청으로 UNIQUE 제약이 충돌하면 생성 트랜잭션만 롤백한다.
4. 호출 서비스가 트랜잭션 밖에서 기존 행을 다시 조회해 동일 ID를 반환한다.

DB의 `UNIQUE(idempotency_key)`가 최종 중복 방어선이다. 별도 deadlock 재시도는 두지 않는다.

### 기각한 대안

- `409 Conflict`: 정상적인 timeout 재시도를 오류로 분류하고 기존 ID도 제공하지 못한다.
- 신규 202, 중복 200: 같은 요청의 응답 의미가 달라지고 PENDING을 완료로 오해할 수 있다.
- 사전 조회 없이 INSERT 우선: 동작하지만 순차 중복도 매번 예외 경로를 탄다.

### 검증

- 순차 중복 요청은 기존 ID를 반환한다.
- 동일 키 10개 동시 요청은 DB에 한 건만 생성되고 모두 같은 ID를 받는다.
- MySQL Testcontainers로 UNIQUE 제약과 동시성 동작을 검증한다.

## D-2. claim token으로 worker 세대를 식별한다

### 선택

- 클레임마다 UUID `claim_token`을 발급한다.
- 성공·재시도·최종 실패는 `(id, PROCESSING, claim_token)` 조건부 UPDATE로 기록한다.
- UPDATE 결과가 0건이면 stale worker 결과로 보고 상태와 attempt 이력을 변경하지 않는다.
- `attemptNo`는 세대 판별이 아니라 재시도 판단과 이력 번호에만 사용한다.

### 이유

오래된 PROCESSING을 PENDING으로 회수한 순간에도 이전 worker가 살아 있을 수 있다.
상태만 검사하면 회수 후 생성된 새 PROCESSING 결과를 이전 worker가 덮어쓸 수 있다.
매 클레임의 고유 token을 비교하면 현재 worker의 결과만 반영할 수 있다.

### 처리 흐름

1. 짧은 트랜잭션에서 `FOR UPDATE SKIP LOCKED`로 알림을 claim한다.
2. 트랜잭션 밖에서 발송한다.
3. 별도 트랜잭션에서 claim token을 검사해 결과와 attempt를 함께 기록한다.
4. stuck recovery는 token을 지우고 실패 attempt를 기록한다. 남은 시도가 있으면
   PENDING으로 복귀시키고, 최대 시도에 도달했으면 FAILED로 종료한다.

worker executor는 대기 큐를 사용하지 않고 빈 실행 슬롯만큼만 claim한다. 따라서 실행 전
대기 작업이 PROCESSING으로 표시되어 stuck으로 오인되지 않는다.

### 트레이드오프

- DB 컬럼과 전달 record가 하나 추가된다.
- 발송 성공 후 결과 커밋 전에 장애가 나면 중복 발송될 수 있다. 이는 at-least-once 방식의
  한계이며 실제 발송 게이트웨이 또는 수신 측 멱등성으로 보완해야 한다.

### 검증

- stuck 회수 후 이전 worker의 성공·실패 결과는 모두 폐기된다.
- 새 token을 가진 worker 결과만 반영된다.
- 다중 claimer가 같은 알림을 중복 claim하지 않는다.

## 기타 결정

| 결정 | 근거 |
| --- | --- |
| 메시지 브로커 대신 DB 테이블을 큐로 사용 | 과제 규모와 실행 편의성, spec §5.1·§5.4 |
| 외부 발송은 DB 트랜잭션 밖에서 수행 | 느린 외부 I/O 동안 DB lock을 유지하지 않기 위해 |
| 재시도 대기는 `PENDING + next_attempt_at`으로 표현 | 불필요한 RETRY_WAIT 상태를 만들지 않기 위해 |
| 발송 보장 수준은 at-least-once | 발송과 DB 커밋을 원자화하지 않으므로 |
| 수신 가능 여부는 발송 직전에 확인 | 접수와 실제 발송 사이에 사용자 상태가 바뀔 수 있으므로 |
| 스키마는 Flyway V1로 관리 | 제출 평가는 fresh clone과 빈 DB를 기준으로 함 |
