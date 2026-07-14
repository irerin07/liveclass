package com.liveclass.notification.application;

/**
 * 같은 멱등성 키(주로 클라이언트 제공 {@code Idempotency-Key})에 다른 요청 본문이
 * 전달된 경우 (spec §5.3). 재시도(replay)가 아니라 키 오용이므로 422로 거부한다.
 */
public class IdempotencyKeyMisuseException extends RuntimeException {

    public IdempotencyKeyMisuseException() {
        super("동일 Idempotency-Key로 다른 요청 본문이 전달되었습니다");
    }
}
