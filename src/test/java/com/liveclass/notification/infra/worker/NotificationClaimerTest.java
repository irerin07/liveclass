package com.liveclass.notification.infra.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NotificationClaimerTest extends IntegrationTestSupport {

    @Autowired
    NotificationClaimer claimer;

    @Autowired
    NotificationRepository repository;

    private Notification savePendingInPast(String key) {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        return repository.save(Notification.pending(key, "student-1",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", key, null, 3, past));
    }

    @Test
    void 클레임하면_PROCESSING으로_전환되고_시도횟수와_시작시각이_기록된다() {
        Notification a = savePendingInPast("a");
        Notification b = savePendingInPast("b");

        List<Long> claimed = claimer.claimBatch();

        assertThat(claimed).containsExactlyInAnyOrder(a.getId(), b.getId());
        for (Long id : claimed) {
            Notification reloaded = repository.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
            assertThat(reloaded.getAttemptCount()).isEqualTo(1);
            assertThat(reloaded.getProcessingStartedAt()).isNotNull();
        }
    }

    @Test
    void 이미_클레임된_행은_다시_클레임되지_않는다() {
        savePendingInPast("a");

        assertThat(claimer.claimBatch()).hasSize(1);
        assertThat(claimer.claimBatch()).isEmpty();
    }
}
