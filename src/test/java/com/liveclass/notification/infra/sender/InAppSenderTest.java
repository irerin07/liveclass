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

class InAppSenderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC);

    private final InAppSender sender = new InAppSender();

    @Test
    void IN_APP_채널을_담당한다() {
        assertThat(sender.supportedChannel()).isEqualTo(Channel.IN_APP);
    }

    @Test
    void 발송은_성공한다() {
        Notification notification = Notification.pending("key", "student-1",
                NotificationType.COURSE_D1, Channel.IN_APP, "COURSE", "c-1", null, 3, CLOCK);

        assertThatCode(() -> sender.send(notification)).doesNotThrowAnyException();
    }
}
