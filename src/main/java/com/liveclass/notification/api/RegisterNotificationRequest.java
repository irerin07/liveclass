package com.liveclass.notification.api;

import tools.jackson.databind.JsonNode;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterNotificationRequest(
        @NotBlank @Size(max = 50) String receiverId,
        @NotNull NotificationType type,
        @NotNull Channel channel,
        @NotBlank @Size(max = 50) String refType,
        @NotBlank @Size(max = 100) String refId,
        JsonNode payload
) {

    public RegisterNotificationCommand toCommand() {
        return new RegisterNotificationCommand(
                receiverId,
                type,
                channel,
                refType,
                refId,
                payload == null || payload.isNull() ? null : payload.toString()
        );
    }
}
