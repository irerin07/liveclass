package com.liveclass.notification.api;

import com.liveclass.notification.application.NotificationDetail;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;

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
        Instant createdAt,
        List<AttemptResponse> attempts
) {

    public static NotificationResponse from(NotificationDetail detail) {
        Notification n = detail.notification();
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
                n.getCreatedAt(),
                detail.attempts().stream().map(AttemptResponse::from).toList()
        );
    }
}
