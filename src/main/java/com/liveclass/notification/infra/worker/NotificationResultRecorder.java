package com.liveclass.notification.infra.worker;

import com.liveclass.notification.application.BackoffPolicy;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationAttempt;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결과 기록 단계(TX2, spec §5.2). 발송 결과에 따라 상태를 전이하고, 매 시도를
 * {@code notification_attempts}에 <b>같은 트랜잭션</b>으로 기록한다 (spec FR-5).
 *
 * <p>상태 전이와 이력 기록은 원자적이다 — 하나의 {@code @Transactional} 안에서 수행된다.
 */
@Component
@RequiredArgsConstructor
public class NotificationResultRecorder {

    private final NotificationRepository repository;
    private final NotificationAttemptRepository attemptRepository;
    private final BackoffPolicy backoffPolicy;
    private final Clock clock;

    @Transactional
    public void recordSuccess(Long notificationId) {
        Notification notification = load(notificationId);
        int attemptNo = notification.getAttemptCount();
        Instant startedAt = notification.getProcessingStartedAt();
        Instant now = clock.instant();

        notification.markSent(clock);
        attemptRepository.save(NotificationAttempt.success(notificationId, attemptNo, startedAt, now));
    }

    /**
     * 발송 실패 기록. 일시적 실패이고 시도 예산이 남아 있으면 백오프 후 재시도로 예약하고,
     * 그 외(영구 실패, 예산 소진, 수신 불가)는 최종 실패로 전환한다.
     *
     * @param retryable 일시적 실패로서 재시도 대상인지 여부
     */
    @Transactional
    public void recordFailure(Long notificationId, boolean retryable, String error) {
        Notification notification = load(notificationId);
        int attemptNo = notification.getAttemptCount();
        Instant startedAt = notification.getProcessingStartedAt();
        Instant now = clock.instant();

        if (retryable && attemptNo < notification.getMaxAttempts()) {
            notification.scheduleRetry(now.plus(backoffPolicy.delayFor(attemptNo)), error, clock);
        } else {
            notification.markFailed(error, clock);
        }
        attemptRepository.save(
                NotificationAttempt.failure(notificationId, attemptNo, startedAt, now, error));
    }

    private Notification load(Long notificationId) {
        return repository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "결과 기록 대상 알림을 찾을 수 없음: id=" + notificationId));
    }
}
