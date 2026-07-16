package com.liveclass.notification.application.result;

import com.liveclass.notification.domain.Notification;

/**
 * 등록 결과. duplicated=true는 멱등 처리로 기존 알림이 반환됐음을 뜻한다 (Phase 2).
 */
public record RegistrationResult(Notification notification, boolean duplicated) {

    public static RegistrationResult created(Notification notification) {
        return new RegistrationResult(notification, false);
    }

    public static RegistrationResult duplicated(Notification notification) {
        return new RegistrationResult(notification, true);
    }
}
