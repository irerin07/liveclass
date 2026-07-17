package com.liveclass.notification.application.sender;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;

/**
 * 발송 포트 (spec §5.4). 현재 구현은 Mock(로그/저장)이며, 실운영 전환 시 이 인터페이스
 * 뒤가 실제 SMTP·푸시 클라이언트로 교체된다.
 *
 * <p>발송은 DB 트랜잭션 밖에서 호출된다 (spec §5.2). 일시적 실패는
 * {@code TransientSendException}, 영구적 실패는 {@code PermanentSendException}으로
 * 구분한다.
 */
public interface NotificationSender {

    /** 이 발송기가 담당하는 채널. */
    Channel supportedChannel();

    void send(Notification notification);
}
