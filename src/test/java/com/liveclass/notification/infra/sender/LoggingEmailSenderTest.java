package com.liveclass.notification.infra.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoggingEmailSenderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC);

    private final LoggingEmailSender sender = new LoggingEmailSender();

    @Test
    void EMAIL_채널을_담당한다() {
        assertThat(sender.supportedChannel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    void 발송은_성공한다() {
        Notification notification = Notification.pending("key", "student-1",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", "e-1", null, 3, CLOCK);

        assertThatCode(() -> sender.send(notification)).doesNotThrowAnyException();
    }
}
