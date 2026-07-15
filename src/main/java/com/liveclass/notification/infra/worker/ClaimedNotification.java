package com.liveclass.notification.infra.worker;

import com.liveclass.notification.domain.Notification;
import java.time.Instant;

/**
 * 한 번의 클레임 세대. 동일 알림이 스턱 회수 후 다시 클레임되더라도 이전 워커 결과가
 * 새 세대의 상태를 덮어쓰지 못하도록 결과 기록까지 전달한다.
 */
public record ClaimedNotification(
        Long id,
        int attemptNo,
        String claimToken,
        Instant processingStartedAt,
        int maxAttempts
) {

    public static ClaimedNotification from(Notification notification) {
        return new ClaimedNotification(
                notification.getId(),
                notification.getAttemptCount(),
                notification.getClaimToken(),
                notification.getProcessingStartedAt(),
                notification.getMaxAttempts()
        );
    }
}
