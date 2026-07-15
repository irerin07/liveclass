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

    private Notification saveProcessing(String key) {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        Notification saved = repository.save(Notification.pending(key, "student-1",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", key, null, 3, past));
        // 클레임된 상태(PROCESSING)로 만든다.
        jdbcTemplate.update("UPDATE notifications SET status = 'PROCESSING', processing_started_at = ? "
                + "WHERE id = ?", java.sql.Timestamp.from(past.instant()), saved.getId());
        return saved;
    }

    @Test
    void 성공_기록하면_SENT로_전환되고_발송시각이_기록된다() {
        Notification processing = saveProcessing("a");

        recorder.recordSuccess(processing.getId());

        Notification reloaded = repository.findById(processing.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getSentAt()).isNotNull();
        assertThat(reloaded.getProcessingStartedAt()).isNull();
    }
}
