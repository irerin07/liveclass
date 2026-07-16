package com.liveclass.notification.infra.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liveclass.notification.application.sender.PermanentSendException;
import com.liveclass.notification.application.sender.TransientSendException;
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

    /** 지정한 시도 횟수(attemptCount)를 가진 PROCESSING 알림을 만든다. */
    private Notification processingWithAttempt(String receiver, int attempt) {
        Notification notification = Notification.pending("key", receiver,
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", "e-1", null, 5, CLOCK);
        for (int i = 1; i <= attempt; i++) {
            notification.claim(CLOCK);
            if (i < attempt) {
                notification.recoverStuck("retry", CLOCK);
            }
        }
        return notification;
    }

    @Test
    void EMAIL_채널을_담당한다() {
        assertThat(sender.supportedChannel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    void 일반_수신자에게는_성공한다() {
        assertThatCode(() -> sender.send(processingWithAttempt("student-1", 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void fail_permanent_수신자는_영구_실패다() {
        assertThatThrownBy(() -> sender.send(processingWithAttempt("fail-permanent-1", 1)))
                .isInstanceOf(PermanentSendException.class);
    }

    @Test
    void fail_2_times_수신자는_2회차까지_일시_실패하고_3회차에_성공한다() {
        assertThatThrownBy(() -> sender.send(processingWithAttempt("fail-2-times-1", 1)))
                .isInstanceOf(TransientSendException.class);
        assertThatThrownBy(() -> sender.send(processingWithAttempt("fail-2-times-1", 2)))
                .isInstanceOf(TransientSendException.class);
        assertThatCode(() -> sender.send(processingWithAttempt("fail-2-times-1", 3)))
                .doesNotThrowAnyException();
    }

    @Test
    void 실패_횟수가_int_범위를_넘으면_예외_없이_정상_발송한다() {
        assertThatCode(() -> sender.send(
                processingWithAttempt("fail-99999999999999999999-times-user", 1)))
                .doesNotThrowAnyException();
    }
}
