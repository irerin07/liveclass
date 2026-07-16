package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.ClaimedNotification;
import com.liveclass.notification.infra.worker.NotificationTransactionService;
import com.liveclass.notification.infra.worker.NotificationWorkerService;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "notification.stuck-threshold=1s",
        "notification.retry.backoff=10ms"
})
class StuckRecoveryTest extends IntegrationTestSupport {

    @Autowired NotificationRepository repository;
    @Autowired NotificationAttemptRepository attemptRepository;
    @Autowired NotificationTransactionService transactionService;
    @Autowired NotificationWorkerService workerService;

    private Notification pending(String key) {
        Clock past = Clock.fixed(Instant.now().minusSeconds(10), ZoneOffset.UTC);
        return repository.save(Notification.pending(key, "student-1",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", key, null, 3, past));
    }

    @Test
    void 스턱_PROCESSING을_회수하고_재처리한다() {
        Notification notification = pending("stuck-retry");
        ClaimedNotification oldClaim = transactionService.claimBatch().getFirst();
        jdbcTemplate.update("UPDATE notifications SET processing_started_at = "
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 SECOND) WHERE id = ?", notification.getId());

        assertThat(transactionService.recoverStuck()).isEqualTo(1);
        Notification recovered = repository.findById(notification.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(recovered.getAttemptCount()).isEqualTo(1);
        assertThat(recovered.getClaimToken()).isNull();
        assertThat(attemptRepository.findByNotificationIdOrderByAttemptNo(notification.getId()))
                .hasSize(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            workerService.processBatch();
            assertThat(repository.findById(notification.getId()).orElseThrow().getStatus())
                    .isEqualTo(NotificationStatus.SENT);
        });
        assertThat(oldClaim.attemptNo()).isEqualTo(1);
    }

    @Test
    void 회수_후_이전_워커의_늦은_결과는_새_클레임을_덮어쓰지_않는다() {
        Notification notification = pending("stale-result");
        ClaimedNotification oldClaim = transactionService.claimBatch().getFirst();
        jdbcTemplate.update("UPDATE notifications SET processing_started_at = "
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 SECOND) WHERE id = ?", notification.getId());
        transactionService.recoverStuck();

        ClaimedNotification newClaim = transactionService.claimBatch().getFirst();
        assertThat(newClaim.attemptNo()).isEqualTo(2);

        assertThat(transactionService.recordSuccess(oldClaim)).isFalse();
        assertThat(transactionService.recordFailure(oldClaim, true, "late failure")).isFalse();
        Notification current = repository.findById(notification.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(current.getClaimToken()).isEqualTo(newClaim.claimToken());
        assertThat(attemptRepository.findByNotificationIdOrderByAttemptNo(notification.getId()))
                .extracting(a -> a.getAttemptNo()).containsExactly(1);
    }
}
