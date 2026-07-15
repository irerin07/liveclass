package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.liveclass.notification.NotificationApplication;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationClaimer;
import com.liveclass.notification.infra.worker.NotificationPoller;
import com.liveclass.notification.infra.worker.StuckNotificationRecoverer;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class RestartDurabilityTest extends IntegrationTestSupport {

    @Autowired NotificationRepository repository;
    @Autowired NotificationClaimer claimer;

    @Test
    void 새_애플리케이션_컨텍스트가_DB의_PENDING과_스턱_PROCESSING을_복구한다() {
        Clock past = Clock.fixed(Instant.now().minusSeconds(10), ZoneOffset.UTC);
        Notification pending = repository.save(Notification.pending("restart-pending", "student-1",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", "pending", null, 3, past));
        Notification stuck = repository.save(Notification.pending("restart-stuck", "student-2",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", "stuck", null, 3, past));
        claimer.claimBatch();
        // 두 건 모두 클레임될 수 있으므로 pending 하나는 다시 PENDING으로 복원해 두 상태를 만든다.
        jdbcTemplate.update("UPDATE notifications SET status='PENDING', attempt_count=0, "
                + "processing_started_at=NULL, claim_token=NULL WHERE id=?", pending.getId());
        jdbcTemplate.update("UPDATE notifications SET processing_started_at="
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 SECOND) WHERE id=?", stuck.getId());

        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(NotificationApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run(
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--notification.stuck-threshold=1s",
                        "--notification.retry.backoff=10ms")) {
            StuckNotificationRecoverer recoverer = restarted.getBean(StuckNotificationRecoverer.class);
            NotificationPoller poller = restarted.getBean(NotificationPoller.class);
            NotificationRepository restartedRepository = restarted.getBean(NotificationRepository.class);

            assertThat(recoverer.recoverBatch()).isEqualTo(1);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                poller.pollOnce();
                assertThat(restartedRepository.findById(pending.getId()).orElseThrow().getStatus())
                        .isEqualTo(NotificationStatus.SENT);
                assertThat(restartedRepository.findById(stuck.getId()).orElseThrow().getStatus())
                        .isEqualTo(NotificationStatus.SENT);
            });
        }
    }
}
