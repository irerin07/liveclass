package com.liveclass.notification.api;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import java.time.Instant;

public record NotificationResponse(
        Long id,
        String receiverId,
        NotificationType type,
        Channel channel,
        String refType,
        String refId,
        NotificationStatus status,
        int attemptCount,
        int maxAttempts,
        String lastError,
        Instant sentAt,
        Instant readAt,
        Instant createdAt
) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getReceiverId(),
                n.getType(),
                n.getChannel(),
                n.getRefType(),
                n.getRefId(),
                n.getStatus(),
                n.getAttemptCount(),
                n.getMaxAttempts(),
                n.getLastError(),
                n.getSentAt(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }
}
