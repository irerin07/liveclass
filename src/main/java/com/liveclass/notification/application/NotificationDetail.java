package com.liveclass.notification.application;

import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationAttempt;
import java.util.List;

/**
 * 알림 단건 + 시도 이력 (spec FR-2). 상태 조회 시 실패 사유 추적을 위해 함께 제공한다.
 */
public record NotificationDetail(Notification notification, List<NotificationAttempt> attempts) {
}
