package com.liveclass.notification.application;

import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationAttemptRepository attemptRepository;
    private final IdempotencyKeyGenerator keyGenerator;
    private final NotificationInserter inserter;

    /**
     * 알림 발송 요청 접수. 저장만 하고 즉시 반환한다 — 발송은 워커(Phase 3)의 몫이다.
     *
     * <p>멱등성 2중 방어 (spec §5.3, decisions.md D-1). 이 메서드는 <b>트랜잭션이 없다</b> —
     * 제약 위반 예외를 트랜잭션 경계 밖에서 잡아야 INSERT 트랜잭션의 롤백이 재조회를
     * 오염시키지 않기 때문이다.
     * <ol>
     *   <li>1차(사전 조회): 같은 키가 이미 있으면 기존 알림 반환 — 흔한 경로의 빠른 응답.</li>
     *   <li>2차(UNIQUE 제약): 동시 요청이 사전 조회를 모두 통과한 경우, INSERT 중 한쪽이
     *       제약 위반 → 예외를 잡아 <b>새 트랜잭션에서 재조회</b>하여 승자 행을 반환한다.</li>
     * </ol>
     *
     * @param explicitIdempotencyKey 클라이언트 제공 {@code Idempotency-Key} 헤더 값 (nullable)
     */
    public RegistrationResult register(RegisterNotificationCommand command,
                                       String explicitIdempotencyKey) {
        String key = keyGenerator.generate(explicitIdempotencyKey, command);

        Optional<Notification> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return asDuplicate(existing.get(), command);
        }

        try {
            return RegistrationResult.created(inserter.insert(key, command));
        } catch (DataIntegrityViolationException race) {
            // 동시 요청 경쟁에서 졌다 — 승자가 이미 커밋했으므로 새 트랜잭션 재조회로 찾는다.
            Notification winner = repository.findByIdempotencyKey(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "멱등성 키 제약 위반이 발생했으나 기존 행을 찾을 수 없음: key=" + key, race));
            return asDuplicate(winner, command);
        }
    }

    /**
     * 재생(replay) 처리. 단, 같은 키에 다른 요청 본문이면 재시도가 아니라 키 오용이므로
     * 거부한다 (spec §5.3). 내용 기반 키는 키가 곧 내용 조합의 해시라 항상 일치하고,
     * 이 검증은 클라이언트 제공 {@code Idempotency-Key}를 다른 본문에 재사용한 경우에만
     * 실제로 걸린다.
     */
    private RegistrationResult asDuplicate(Notification existing, RegisterNotificationCommand command) {
        boolean sameRequest = existing.getType() == command.type()
                && existing.getChannel() == command.channel()
                && Objects.equals(existing.getReceiverId(), command.receiverId())
                && Objects.equals(existing.getRefType(), command.refType())
                && Objects.equals(existing.getRefId(), command.refId());
        if (!sameRequest) {
            throw new IdempotencyKeyMisuseException();
        }
        return RegistrationResult.duplicated(existing);
    }

    @Transactional(readOnly = true)
    public NotificationDetail getDetail(Long id) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
        return new NotificationDetail(notification,
                attemptRepository.findByNotificationIdOrderByAttemptNo(id));
    }
}
