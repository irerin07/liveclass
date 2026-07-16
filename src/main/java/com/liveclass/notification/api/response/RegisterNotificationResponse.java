package com.liveclass.notification.api.response;

import com.liveclass.notification.application.result.RegistrationResult;
import com.liveclass.notification.domain.NotificationStatus;

public record RegisterNotificationResponse(
        Long notificationId,
        NotificationStatus status,
        boolean duplicated
) {

    public static RegisterNotificationResponse from(RegistrationResult result) {
        return new RegisterNotificationResponse(
                result.notification().getId(),
                result.notification().getStatus(),
                result.duplicated()
        );
    }
}
