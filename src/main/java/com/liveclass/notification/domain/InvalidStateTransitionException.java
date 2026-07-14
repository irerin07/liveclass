package com.liveclass.notification.domain;

/**
 * spec §6에 정의되지 않은 상태 전이를 시도한 경우 발생한다.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(NotificationStatus from, String action) {
        super("상태 " + from + " 에서는 '" + action + "' 전이가 허용되지 않습니다");
    }

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
