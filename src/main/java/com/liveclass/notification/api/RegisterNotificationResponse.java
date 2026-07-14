package com.liveclass.notification.api;

import com.liveclass.notification.application.RegistrationResult;
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
