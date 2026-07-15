package com.liveclass.notification.application.sender;

/**
 * 일시적 발송 실패 (네트워크 오류, 외부 서버 5xx 등). 재시도 대상이다 (spec §7.3).
 */
public class TransientSendException extends RuntimeException {

    public TransientSendException(String message) {
        super(message);
    }
}
