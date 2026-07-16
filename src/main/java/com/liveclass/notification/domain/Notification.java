package com.liveclass.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 알림 상태와 처리 메타데이터. claim과 stuck recovery는 엔티티 메서드로 수행하고,
 * worker 결과는 claim token을 검사하는 repository 조건부 UPDATE로 반영한다.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    private static final int LAST_ERROR_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;

    @Column(name = "receiver_id", nullable = false, length = 50, updatable = false)
    private String receiverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private Channel channel;

    @Column(name = "ref_type", length = 50, updatable = false)
    private String refType;

    @Column(name = "ref_id", length = 100, updatable = false)
    private String refId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Notification(String idempotencyKey, String receiverId, NotificationType type,
                         Channel channel, String refType, String refId, String payload,
                         int maxAttempts, Instant now) {
        this.idempotencyKey = idempotencyKey;
        this.receiverId = receiverId;
        this.type = type;
        this.channel = channel;
        this.refType = refType;
        this.refId = refId;
        this.payload = payload;
        this.status = NotificationStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Notification pending(String idempotencyKey, String receiverId,
                                       NotificationType type, Channel channel,
                                       String refType, String refId, String payload,
                                       int maxAttempts, Clock clock) {
        return new Notification(idempotencyKey, receiverId, type, channel,
                refType, refId, payload, maxAttempts, clock.instant());
    }

    /**
     * PENDING → PROCESSING. 워커가 발송을 위해 선점한다.
     * 시도 횟수를 증가시키고 스턱 판정 기준 시각을 기록한다.
     */
    public void claim(Clock clock) {
        require(status == NotificationStatus.PENDING, "claim");
        Instant now = clock.instant();
        this.status = NotificationStatus.PROCESSING;
        this.attemptCount++;
        this.processingStartedAt = now;
        this.claimToken = java.util.UUID.randomUUID().toString();
        this.updatedAt = now;
    }

    /** PROCESSING → PENDING. 사망한 워커의 클레임을 회수하되 시도 횟수는 유지한다. */
    public void recoverStuck(String reason, Clock clock) {
        require(status == NotificationStatus.PROCESSING, "recoverStuck");
        Instant now = clock.instant();
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = now;
        this.lastError = truncate(reason);
        this.processingStartedAt = null;
        this.claimToken = null;
        this.updatedAt = now;
    }

    private void require(boolean allowed, String action) {
        if (!allowed) {
            throw new InvalidStateTransitionException(status, action);
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= LAST_ERROR_MAX_LENGTH
                ? error
                : error.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
