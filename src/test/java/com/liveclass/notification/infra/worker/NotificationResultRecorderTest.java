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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NotificationResultRecorderTest extends IntegrationTestSupport {

    @Autowired
    NotificationResultRecorder recorder;

    @Autowired
    NotificationRepository repository;

    @Autowired
    NotificationClaimer claimer;

    private Notification saveProcessing(String key) {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        Notification saved = repository.save(Notification.pending(key, "student-1",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", key, null, 3, past));
        return saved;
    }

    @Test
    void 성공_기록하면_SENT로_전환되고_발송시각이_기록된다() {
        Notification processing = saveProcessing("a");
        ClaimedNotification claim = claimer.claimBatch().getFirst();

        recorder.recordSuccess(claim);

        Notification reloaded = repository.findById(processing.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getSentAt()).isNotNull();
        assertThat(reloaded.getProcessingStartedAt()).isNull();
    }
}
