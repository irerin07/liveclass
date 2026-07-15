package com.liveclass.notification.infra.worker;

import com.liveclass.notification.config.NotificationProperties;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/**
 * 클레임 단계(TX1, spec §5.2). 발송 가능한 알림을 {@code FOR UPDATE SKIP LOCKED}로
 * 선점하고 즉시 PROCESSING으로 전환한 뒤 커밋한다. 발송(외부 호출)은 이 트랜잭션 밖에서
 * 워커가 수행하므로, 클레임과 발송의 트랜잭션 경계가 분리된다.
 *
 * <p>클레임된 엔티티는 영속 상태이므로 {@code claim()}의 변경이 커밋 시 flush된다
 * (status=PROCESSING, processing_started_at, attempt_count++). 선점 잠금은 이 트랜잭션
 * 커밋까지 유지되어, 커밋 후에는 다른 폴러가 해당 행을 PENDING으로 보지 못한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationClaimer {

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final Clock clock;

    /**
     * 발송 가능한 알림을 배치로 클레임하고 그 ID 목록을 반환한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedNotification> claimBatch() {
        return claimBatch(properties.batchSize());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedNotification> claimBatch(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Instant now = clock.instant();
        List<Notification> claimable = repository.findClaimable(now,
                Math.min(limit, properties.batchSize()));
        claimable.forEach(notification -> notification.claim(clock));
        return claimable.stream().map(ClaimedNotification::from).toList();
    }
}
