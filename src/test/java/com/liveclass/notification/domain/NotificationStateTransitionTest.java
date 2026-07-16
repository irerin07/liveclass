package com.liveclass.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 엔티티가 직접 담당하는 생성, 클레임, 스턱 복구 전이를 검증한다. */
class NotificationStateTransitionTest {

    private static final Instant NOW = Instant.parse("2026-07-14T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Notification pending() {
        return Notification.pending("key-1", "student-1", NotificationType.PAYMENT_CONFIRMED,
                Channel.EMAIL, "ENROLLMENT", "enrollment-42", null, 3, clock);
    }

    @Test
    void 생성_직후는_PENDING이고_즉시_클레임_가능하다() {
        Notification notification = pending();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttemptCount()).isZero();
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(notification.getProcessingStartedAt()).isNull();
    }

    @Test
    void 클레임하면_PROCESSING이_되고_시도횟수와_토큰이_기록된다() {
        Notification notification = pending();

        notification.claim(clock);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getAttemptCount()).isEqualTo(1);
        assertThat(notification.getProcessingStartedAt()).isEqualTo(NOW);
        assertThat(notification.getClaimToken()).isNotBlank();
    }

    @Test
    void PROCESSING은_다시_클레임할_수_없다() {
        Notification notification = pending();
        notification.claim(clock);

        assertThatThrownBy(() -> notification.claim(clock))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void 스턱_복구하면_PENDING으로_돌아가고_클레임이_해제된다() {
        Notification notification = pending();
        notification.claim(clock);

        notification.recoverStuck("timeout", clock);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(notification.getLastError()).isEqualTo("timeout");
        assertThat(notification.getProcessingStartedAt()).isNull();
        assertThat(notification.getClaimToken()).isNull();
    }
}
