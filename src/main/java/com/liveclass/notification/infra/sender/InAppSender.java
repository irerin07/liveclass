package com.liveclass.notification.infra.sender;

import com.liveclass.notification.application.sender.NotificationSender;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * IN_APP 채널 발송기 (spec §7.2).
 *
 * <p>인앱 알림은 별도 외부 발송이 없다 — 알림 레코드의 존재 자체가 도달이며, SENT로
 * 전환되면 사용자 목록 조회(Phase 6)에 노출된다. 그럼에도 EMAIL과 <b>동일한 워커
 * 파이프라인</b>을 통과시켜 채널별 분기를 최소화하고, 향후 실제 푸시 게이트웨이가
 * 생겨도 구조 변경이 없게 한다.
 */
@Component
public class InAppSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(InAppSender.class);

    @Override
    public Channel supportedChannel() {
        return Channel.IN_APP;
    }

    @Override
    public void send(Notification notification) {
        log.info("[IN_APP] 도달 id={} receiver={} type={}",
                notification.getId(), notification.getReceiverId(), notification.getType());
    }
}
