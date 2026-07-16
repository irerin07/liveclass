package com.liveclass.notification.application.command;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationType;

public record RegisterNotificationCommand(
        String receiverId,
        NotificationType type,
        Channel channel,
        String refType,
        String refId,
        String payload
) {
}
