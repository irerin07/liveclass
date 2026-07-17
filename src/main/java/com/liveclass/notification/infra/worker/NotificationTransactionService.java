package com.liveclass.notification.infra.worker;

import com.liveclass.notification.application.BackoffPolicy;
import com.liveclass.notification.config.NotificationProperties;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationAttempt;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 알림 워커의 짧은 DB 트랜잭션을 담당한다. 외부 발송은 이 서비스 밖에서 수행한다. */
@Service
@RequiredArgsConstructor
public class NotificationTransactionService {

    private static final Logger log = LoggerFactory.getLogger(NotificationTransactionService.class);
    private static final int ERROR_MAX_LENGTH = 1000;
    private static final String STUCK_REASON = "STUCK_RECOVERED: processing timeout";

    private final NotificationRepository repository;
    private final NotificationAttemptRepository attemptRepository;
    private final NotificationProperties properties;
    private final BackoffPolicy backoffPolicy;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedNotification> claimBatch() {
        return claimBatch(properties.batchSize());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedNotification> claimBatch(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Notification> claimable = repository.findClaimable(
                clock.instant(), Math.min(limit, properties.batchSize()));
        claimable.forEach(notification -> notification.claim(clock));
        return claimable.stream().map(ClaimedNotification::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Notification> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public boolean recordSuccess(ClaimedNotification claim) {
        Instant now = clock.instant();
        int updated = repository.markSentIfCurrent(
                claim.id(), claim.claimToken(), now);
        if (updated == 0) {
            log.info("stale 성공 결과 폐기 id={} attempt={}", claim.id(), claim.attemptNo());
            return false;
        }
        attemptRepository.save(NotificationAttempt.success(
                claim.id(), claim.attemptNo(), claim.processingStartedAt(), now));
        return true;
    }

    @Transactional
    public boolean recordFailure(ClaimedNotification claim, boolean retryable, String error) {
        Instant now = clock.instant();
        String safeError = truncate(error);
        int updated;
        if (retryable && claim.attemptNo() < claim.maxAttempts()) {
            updated = repository.scheduleRetryIfCurrent(
                    claim.id(), claim.claimToken(),
                    now.plus(backoffPolicy.delayFor(claim.attemptNo())), safeError, now);
        } else {
            updated = repository.markFailedIfCurrent(
                    claim.id(), claim.claimToken(), safeError, now);
        }
        if (updated == 0) {
            log.info("stale 실패 결과 폐기 id={} attempt={}", claim.id(), claim.attemptNo());
            return false;
        }
        attemptRepository.save(NotificationAttempt.failure(
                claim.id(), claim.attemptNo(), claim.processingStartedAt(), now, safeError));
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recoverStuck() {
        Instant now = clock.instant();
        Instant threshold = now.minus(properties.stuckThreshold());
        List<Notification> stuck = repository.findStuck(threshold, properties.batchSize());
        for (Notification notification : stuck) {
            int attemptNo = notification.getAttemptCount();
            Instant startedAt = notification.getProcessingStartedAt();
            notification.recoverStuck(STUCK_REASON, clock);
            attemptRepository.save(NotificationAttempt.failure(
                    notification.getId(), attemptNo, startedAt, now, STUCK_REASON));
            log.warn("스턱 알림 처리 id={} attempt={} status={}",
                    notification.getId(), attemptNo, notification.getStatus());
        }
        return stuck.size();
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= ERROR_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, ERROR_MAX_LENGTH);
    }
}
