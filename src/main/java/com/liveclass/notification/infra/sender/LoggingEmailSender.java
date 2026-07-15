package com.liveclass.notification.infra.sender;

import com.liveclass.notification.application.sender.NotificationSender;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EMAIL 채널 Mock 발송기 (과제 제약: 실제 이메일 발송 불필요, 로그로 대체).
 * 이번 단계(Phase 3)는 항상 성공한다. 실패 주입(재시도·최종 실패 시연)은 Phase 4에서 추가된다.
 */
@Component
public class LoggingEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public Channel supportedChannel() {
        return Channel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        log.info("[EMAIL] 발송 id={} receiver={} type={} ref={}:{}",
                notification.getId(), notification.getReceiverId(), notification.getType(),
                notification.getRefType(), notification.getRefId());
    }
}
