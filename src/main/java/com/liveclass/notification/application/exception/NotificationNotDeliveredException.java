package com.liveclass.notification.application.exception;

import com.liveclass.notification.domain.NotificationStatus;

public class NotificationNotDeliveredException extends RuntimeException {

    public NotificationNotDeliveredException(Long id, NotificationStatus status) {
        super("발송 완료된 알림만 읽음 처리할 수 있습니다: id=" + id + ", status=" + status);
    }
}
