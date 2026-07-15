package com.liveclass.notification.infra.worker;

import com.liveclass.notification.application.BackoffPolicy;
import com.liveclass.notification.domain.NotificationAttempt;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 결과 상태와 시도 이력을 같은 트랜잭션에 기록하며, 현재 클레임 세대만 갱신한다. */
@Component
@RequiredArgsConstructor
public class NotificationResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(NotificationResultRecorder.class);
    private static final int ERROR_MAX_LENGTH = 1000;

    private final NotificationRepository repository;
    private final NotificationAttemptRepository attemptRepository;
    private final BackoffPolicy backoffPolicy;
    private final Clock clock;

    @Transactional
    public boolean recordSuccess(ClaimedNotification claim) {
        Instant now = clock.instant();
        int updated = repository.markSentIfCurrent(
                claim.id(), claim.attemptNo(), claim.claimToken(), now);
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
                    claim.id(), claim.attemptNo(), claim.claimToken(),
                    now.plus(backoffPolicy.delayFor(claim.attemptNo())), safeError, now);
        } else {
            updated = repository.markFailedIfCurrent(
                    claim.id(), claim.attemptNo(), claim.claimToken(), safeError, now);
        }
        if (updated == 0) {
            log.info("stale 실패 결과 폐기 id={} attempt={}", claim.id(), claim.attemptNo());
            return false;
        }
        attemptRepository.save(NotificationAttempt.failure(
                claim.id(), claim.attemptNo(), claim.processingStartedAt(), now, safeError));
        return true;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= ERROR_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, ERROR_MAX_LENGTH);
    }
}
