package com.liveclass.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * spec §6 상태 전이 표 검증 (tasks T1.6).
 * 허용된 전이는 부수 효과까지, 정의되지 않은 전이는 예외를 확인한다.
 */
class NotificationStateTransitionTest {

    private static final Instant NOW = Instant.parse("2026-07-14T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Notification pending() {
        return Notification.pending("key-1", "student-1", NotificationType.PAYMENT_CONFIRMED,
                Channel.EMAIL, "ENROLLMENT", "enrollment-42", null, 3, clock);
    }

    private Notification processing() {
        Notification n = pending();
        n.claim(clock);
        return n;
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        void 생성_직후는_PENDING이고_즉시_클레임_가능하다() {
            Notification n = pending();

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.getAttemptCount()).isZero();
            assertThat(n.getNextAttemptAt()).isEqualTo(NOW);
            assertThat(n.getProcessingStartedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("클레임")
    class Claim {

        @Test
        void PENDING에서_클레임하면_PROCESSING이_되고_시도횟수가_증가한다() {
            Notification n = pending();

            n.claim(clock);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
            assertThat(n.getAttemptCount()).isEqualTo(1);
            assertThat(n.getProcessingStartedAt()).isEqualTo(NOW);
        }

        @Test
        void PROCESSING_SENT_FAILED에서는_클레임할_수_없다() {
            Notification inProcessing = processing();
            assertThatThrownBy(() -> inProcessing.claim(clock))
                    .isInstanceOf(InvalidStateTransitionException.class);

            Notification sent = processing();
            sent.markSent(clock);
            assertThatThrownBy(() -> sent.claim(clock))
                    .isInstanceOf(InvalidStateTransitionException.class);

            Notification failed = processing();
            failed.markFailed("boom", clock);
            assertThatThrownBy(() -> failed.claim(clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("발송성공")
    class MarkSent {

        @Test
        void PROCESSING에서_markSent하면_SENT가_되고_발송시각이_기록된다() {
            Notification n = processing();

            n.markSent(clock);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(n.getSentAt()).isEqualTo(NOW);
            assertThat(n.getProcessingStartedAt()).isNull();
        }

        @Test
        void PENDING에서는_markSent할_수_없다() {
            Notification n = pending();
            assertThatThrownBy(() -> n.markSent(clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        void SENT는_최종_상태다_어떤_전이도_불가() {
            Notification n = processing();
            n.markSent(clock);

            assertThatThrownBy(() -> n.markSent(clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> n.scheduleRetry(NOW, "e", clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> n.markFailed("e", clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("재시도예약")
    class ScheduleRetry {

        @Test
        void PROCESSING에서_scheduleRetry하면_PENDING으로_돌아가고_다음_시도_시각과_오류가_기록된다() {
            Notification n = processing();
            Instant nextAttempt = NOW.plus(Duration.ofSeconds(30));

            n.scheduleRetry(nextAttempt, "SMTP timeout", clock);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.getNextAttemptAt()).isEqualTo(nextAttempt);
            assertThat(n.getLastError()).isEqualTo("SMTP timeout");
            assertThat(n.getAttemptCount()).isEqualTo(1);
            assertThat(n.getProcessingStartedAt()).isNull();
        }

        @Test
        void 시도_예산이_소진되면_scheduleRetry할_수_없다() {
            Notification n = pending();
            for (int i = 0; i < 3; i++) {
                n.claim(clock);
                if (i < 2) {
                    n.scheduleRetry(NOW, "transient", clock);
                }
            }
            assertThat(n.getAttemptCount()).isEqualTo(3);

            assertThatThrownBy(() -> n.scheduleRetry(NOW, "transient", clock))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("시도 예산 소진");
        }

        @Test
        void PENDING에서는_scheduleRetry할_수_없다() {
            Notification n = pending();
            assertThatThrownBy(() -> n.scheduleRetry(NOW, "e", clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("최종실패")
    class MarkFailed {

        @Test
        void PROCESSING에서_markFailed하면_FAILED가_되고_사유가_기록된다() {
            Notification n = processing();

            n.markFailed("recipient rejected", clock);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(n.getLastError()).isEqualTo("recipient rejected");
        }

        @Test
        void 오류_메시지는_1000자로_절단된다() {
            Notification n = processing();

            n.markFailed("x".repeat(5000), clock);

            assertThat(n.getLastError()).hasSize(1000);
        }

        @Test
        void FAILED는_최종_상태다_어떤_전이도_불가() {
            Notification n = processing();
            n.markFailed("boom", clock);

            assertThatThrownBy(() -> n.claim(clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> n.markSent(clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> n.scheduleRetry(NOW, "e", clock))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }
}
