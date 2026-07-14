package com.liveclass.notification.application;

import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    /** Phase 4에서 재시도 정책 설정(notification.retry.*)으로 대체된다. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final NotificationRepository repository;
    private final Clock clock;

    /**
     * 알림 발송 요청 접수. 저장만 하고 즉시 반환한다 — 발송은 워커(Phase 3)의 몫이다.
     * 멱등성 키는 Phase 2에서 요청 내용 기반으로 대체된다 (현재는 UUID).
     */
    @Transactional
    public RegistrationResult register(RegisterNotificationCommand command) {
        Notification notification = Notification.pending(
                UUID.randomUUID().toString(),
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
