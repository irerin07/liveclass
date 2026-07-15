package com.liveclass.notification.application.sender;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 알림의 채널에 맞는 {@link NotificationSender}로 발송을 위임한다.
 * 등록된 발송기를 채널별로 인덱싱하며, 같은 채널에 발송기가 둘 이상이면 기동 시 실패한다.
 */
@Component
public class NotificationSenderRouter {

    private final Map<Channel, NotificationSender> byChannel = new EnumMap<>(Channel.class);

    public NotificationSenderRouter(List<NotificationSender> senders) {
        for (NotificationSender sender : senders) {
            NotificationSender previous = byChannel.put(sender.supportedChannel(), sender);
            if (previous != null) {
                throw new IllegalStateException(
                        "채널 " + sender.supportedChannel() + " 에 발송기가 둘 이상입니다: "
                                + previous.getClass().getSimpleName() + ", "
                                + sender.getClass().getSimpleName());
            }
        }
    }

    public void send(Notification notification) {
        NotificationSender sender = byChannel.get(notification.getChannel());
        if (sender == null) {
            throw new IllegalStateException(
                    "채널에 대한 발송기가 없습니다: " + notification.getChannel());
        }
        sender.send(notification);
    }
}
