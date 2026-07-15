package com.liveclass.notification.api;

import com.liveclass.notification.domain.NotificationAttempt;
import java.time.Instant;

public record AttemptResponse(
        int attemptNo,
        boolean success,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {

    public static AttemptResponse from(NotificationAttempt attempt) {
        return new AttemptResponse(
                attempt.getAttemptNo(),
                attempt.isSuccess(),
                attempt.getStartedAt(),
                attempt.getFinishedAt(),
                attempt.getErrorMessage()
        );
    }
}
