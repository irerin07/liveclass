package com.liveclass.notification.application;

import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    /** Phase 4에서 재시도 정책 설정(notification.retry.*)으로 대체된다. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final NotificationRepository repository;
    private final IdempotencyKeyGenerator keyGenerator;
    private final Clock clock;

    /**
     * 알림 발송 요청 접수. 저장만 하고 즉시 반환한다 — 발송은 워커(Phase 3)의 몫이다.
     *
     * <p>1차 방어(사전 조회): 같은 멱등성 키의 알림이 이미 있으면 기존 것을 반환한다.
     * 동시 요청 경쟁의 최종 방어(UNIQUE 제약 + 재조회)는 T2.3에서 추가된다.
     *
     * @param explicitIdempotencyKey 클라이언트 제공 {@code Idempotency-Key} 헤더 값 (nullable)
     */
    @Transactional
    public RegistrationResult register(RegisterNotificationCommand command,
                                       String explicitIdempotencyKey) {
        String key = keyGenerator.generate(explicitIdempotencyKey, command);

        Optional<Notification> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return RegistrationResult.duplicated(existing.get());
        }

        Notification notification = Notification.pending(
                key,
                command.receiverId(),
                command.type(),
                command.channel(),
                command.refType(),
                command.refId(),
                command.payload(),
                DEFAULT_MAX_ATTEMPTS,
                clock
        );
        return RegistrationResult.created(repository.save(notification));
    }

    @Transactional(readOnly = true)
    public Notification get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }
}
