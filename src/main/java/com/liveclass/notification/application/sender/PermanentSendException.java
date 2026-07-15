package com.liveclass.notification.application.sender;

/**
 * 영구적 발송 실패 (수신자 형식 오류, 유효하지 않은 채널 등 4xx 상당). 재시도하지 않고
 * 즉시 최종 실패로 처리한다 (spec §7.3).
 */
public class PermanentSendException extends RuntimeException {

    public PermanentSendException(String message) {
        super(message);
    }
}
