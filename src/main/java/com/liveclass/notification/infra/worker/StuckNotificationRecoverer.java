package com.liveclass.notification.infra.worker;

import com.liveclass.notification.config.NotificationProperties;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationAttempt;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 오래 PROCESSING인 클레임을 회수해 다시 폴링 가능한 PENDING으로 되돌린다. */
@Component
@RequiredArgsConstructor
public class StuckNotificationRecoverer {

    private static final Logger log = LoggerFactory.getLogger(StuckNotificationRecoverer.class);
    private static final String STUCK_REASON = "STUCK_RECOVERED: processing timeout";

    private final NotificationRepository repository;
    private final NotificationAttemptRepository attemptRepository;
    private final NotificationProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recoverBatch() {
        Instant now = clock.instant();
        Instant threshold = now.minus(properties.stuckThreshold());
        List<Notification> stuck = repository.findStuck(threshold, properties.batchSize());
        for (Notification notification : stuck) {
            int attemptNo = notification.getAttemptCount();
            Instant startedAt = notification.getProcessingStartedAt();
            notification.recoverStuck(STUCK_REASON, clock);
            attemptRepository.save(NotificationAttempt.failure(
                    notification.getId(), attemptNo, startedAt, now, STUCK_REASON));
            log.warn("스턱 알림 회수 id={} attempt={}", notification.getId(), attemptNo);
        }
        return stuck.size();
    }
}
