package com.liveclass.notification.application;

import com.liveclass.notification.config.NotificationProperties;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 INSERT를 독립 트랜잭션({@code REQUIRES_NEW})으로 격리한다 (decisions.md D-1).
 *
 * <p>서비스와 별도 빈으로 두는 이유: ① 트랜잭션 경계를 물리적으로 분리해 UNIQUE 제약
 * 위반 시 이 짧은 트랜잭션만 롤백되고 호출자(서비스)의 흐름은 오염되지 않게 하며,
 * ② 같은 클래스 내 self-invocation은 프록시를 우회해 {@code @Transactional}이 적용되지
 * 않으므로 별도 빈이어야 한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationInserter {

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification insert(String idempotencyKey, RegisterNotificationCommand command) {
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
        return repository.save(notification);
    }
}
