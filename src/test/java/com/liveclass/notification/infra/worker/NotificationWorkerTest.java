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

class NotificationWorkerTest extends IntegrationTestSupport {

    @Autowired
    NotificationWorker worker;

    @Autowired
    NotificationClaimer claimer;

    @Autowired
    NotificationRepository repository;

    private Notification savePendingInPast(String key, Channel channel) {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        return repository.save(Notification.pending(key, "student-1",
                NotificationType.PAYMENT_CONFIRMED, channel, "ENROLLMENT", key, null, 3, past));
    }

    @Test
    void 클레임된_EMAIL_알림을_처리하면_SENT가_된다() {
        savePendingInPast("email", Channel.EMAIL);
        List<Long> claimed = claimer.claimBatch();

        worker.process(claimed.getFirst());

        Notification reloaded = repository.findById(claimed.getFirst()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getSentAt()).isNotNull();
    }

    @Test
    void 클레임된_IN_APP_알림도_같은_파이프라인으로_SENT가_된다() {
        savePendingInPast("inapp", Channel.IN_APP);
        List<Long> claimed = claimer.claimBatch();

        worker.process(claimed.getFirst());

        assertThat(repository.findById(claimed.getFirst()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.SENT);
    }
}
