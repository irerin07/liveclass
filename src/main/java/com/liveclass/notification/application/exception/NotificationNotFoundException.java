package com.liveclass.notification.application.exception;

public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(Long id) {
        super("알림을 찾을 수 없습니다: id=" + id);
    }
}
