package com.liveclass.notification.application;

import com.liveclass.notification.config.NotificationProperties;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** INSERT 실패가 후속 조회를 오염시키지 않도록 알림 생성을 독립 트랜잭션에서 수행한다. */
@Component
@RequiredArgsConstructor
public class NotificationCreationService {

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification create(String idempotencyKey, RegisterNotificationCommand command) {
        Notification notification = Notification.pending(
                idempotencyKey,
                command.receiverId(),
                command.type(),
                command.channel(),
                command.refType(),
                command.refId(),
                command.payload(),
                properties.retry().maxAttempts(),
                clock
        );
        return repository.saveAndFlush(notification);
    }
}
