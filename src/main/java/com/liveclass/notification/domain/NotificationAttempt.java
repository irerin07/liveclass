package com.liveclass.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발송 시도 이력. 알림 1건은 여러 시도를 가질 수 있으며,
 * 실패 사유 추적의 원천이다 (spec FR-5). 기록 로직은 Phase 4에서 연결된다.
 */
@Entity
@Table(name = "notification_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationAttempt {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private Long notificationId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    @Column(name = "success", nullable = false, updatable = false)
    private boolean success;

    @Column(name = "error_message", length = 1000, updatable = false)
    private String errorMessage;

    private NotificationAttempt(Long notificationId, int attemptNo, Instant startedAt,
                                Instant finishedAt, boolean success, String errorMessage) {
        this.notificationId = notificationId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static NotificationAttempt success(Long notificationId, int attemptNo,
                                              Instant startedAt, Instant finishedAt) {
        return new NotificationAttempt(notificationId, attemptNo, startedAt, finishedAt, true, null);
    }

    public static NotificationAttempt failure(Long notificationId, int attemptNo,
                                              Instant startedAt, Instant finishedAt,
                                              String errorMessage) {
        return new NotificationAttempt(notificationId, attemptNo, startedAt, finishedAt, false,
                truncate(errorMessage));
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
