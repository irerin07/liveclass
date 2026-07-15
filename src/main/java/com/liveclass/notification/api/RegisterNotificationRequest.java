package com.liveclass.notification.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record RegisterNotificationRequest(
        @NotBlank @Size(max = 50) String receiverId,
        @NotNull NotificationType type,
        @NotNull Channel channel,
        @NotBlank @Size(max = 50) String refType,
        @NotBlank @Size(max = 100) String refId,
        JsonNode payload
) {

    /** payload 직렬화 크기 상한. 메모리·저장·직렬화 비용 보호 (spec 성능/제약). */
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    @JsonIgnore
    @AssertTrue(message = "payload가 너무 큽니다 (최대 64KB)")
    public boolean isPayloadWithinLimit() {
        if (payload == null || payload.isNull()) {
            return true;
        }
        return payload.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES;
    }

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
