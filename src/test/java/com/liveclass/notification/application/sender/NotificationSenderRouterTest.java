package com.liveclass.notification.application.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationSenderRouterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC);

    private static class RecordingSender implements NotificationSender {
        private final Channel channel;
        final List<Notification> sent = new ArrayList<>();

        RecordingSender(Channel channel) {
            this.channel = channel;
        }

        @Override
        public Channel supportedChannel() {
            return channel;
        }

        @Override
        public void send(Notification notification) {
            sent.add(notification);
        }
    }

    private Notification notification(Channel channel) {
        return Notification.pending("key", "student-1", NotificationType.PAYMENT_CONFIRMED,
                channel, "ENROLLMENT", "e-1", null, 3, CLOCK);
    }

    @Test
    void 채널에_맞는_발송기로_위임한다() {
        RecordingSender email = new RecordingSender(Channel.EMAIL);
        RecordingSender inApp = new RecordingSender(Channel.IN_APP);
        NotificationSenderRouter router = new NotificationSenderRouter(List.of(email, inApp));

        Notification emailNotification = notification(Channel.EMAIL);
        router.send(emailNotification);

        assertThat(email.sent).containsExactly(emailNotification);
        assertThat(inApp.sent).isEmpty();
    }

    @Test
    void 담당_발송기가_없는_채널은_예외다() {
        NotificationSenderRouter router = new NotificationSenderRouter(
                List.of(new RecordingSender(Channel.EMAIL)));

        assertThatThrownBy(() -> router.send(notification(Channel.IN_APP)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IN_APP");
    }

    @Test
    void 같은_채널에_발송기가_둘_이상이면_기동_시_실패한다() {
        assertThatThrownBy(() -> new NotificationSenderRouter(
                List.of(new RecordingSender(Channel.EMAIL), new RecordingSender(Channel.EMAIL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("둘 이상");
    }
}
