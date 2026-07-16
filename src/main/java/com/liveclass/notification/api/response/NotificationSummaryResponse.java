package com.liveclass.notification.api.response;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import java.time.Instant;

public record NotificationSummaryResponse(
        Long id,
        String receiverId,
        NotificationType type,
        Channel channel,
        String refType,
        String refId,
        NotificationStatus status,
        Instant readAt,
        Instant createdAt
) {

    public static NotificationSummaryResponse from(Notification notification) {
        return new NotificationSummaryResponse(
                notification.getId(),
                notification.getReceiverId(),
                notification.getType(),
                notification.getChannel(),
                notification.getRefType(),
                notification.getRefId(),
                notification.getStatus(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }
}
