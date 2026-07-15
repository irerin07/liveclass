package com.liveclass.notification.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 클레임 쿼리 검증 (tasks T3.4). 선택 조건과 {@code FOR UPDATE SKIP LOCKED} 동작을
 * 실제 MySQL에서 확인한다 (spec NFR-6).
 */
class ClaimQueryTest extends IntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-07-14T10:00:00Z");

    @Autowired
    NotificationRepository repository;

    @Autowired
    PlatformTransactionManager txManager;

    private Notification savePending(String key, Instant nextAttemptAt) {
        Clock clock = Clock.fixed(nextAttemptAt, ZoneOffset.UTC);
        return repository.save(Notification.pending(key, "student-1",
                NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", key, null, 3, clock));
    }

    private void setStatus(Long id, String status) {
        jdbcTemplate.update("UPDATE notifications SET status = ? WHERE id = ?", status, id);
    }

    @Test
    void PENDING이고_발송시각이_도래한_행만_선택한다() {
        Notification due = savePending("due", NOW.minusSeconds(1));
        savePending("future", NOW.plus(Duration.ofHours(1)));
        setStatus(savePending("processing", NOW.minusSeconds(1)).getId(), "PROCESSING");
        setStatus(savePending("sent", NOW.minusSeconds(1)).getId(), "SENT");

        List<Notification> claimable = repository.findClaimable(NOW, 10);

        assertThat(claimable).extracting(Notification::getId).containsExactly(due.getId());
    }

    @Test
    void batchSize만큼_발송시각_오름차순으로_선택한다() {
        Notification first = savePending("a", NOW.minus(Duration.ofSeconds(30)));
        Notification second = savePending("b", NOW.minus(Duration.ofSeconds(20)));
        savePending("c", NOW.minus(Duration.ofSeconds(10)));

        List<Notification> claimable = repository.findClaimable(NOW, 2);

        assertThat(claimable).extracting(Notification::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void 다른_트랜잭션이_잠근_행은_건너뛰고_블로킹하지_않는다() throws Exception {
        Notification locked = savePending("locked", NOW.minusSeconds(2));       // 더 이른 행
        Notification available = savePending("available", NOW.minusSeconds(1));

        CountDownLatch lockedLatch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // 다른 트랜잭션이 가장 이른 행 1건을 클레임(잠금)한 채 대기한다.
        CompletableFuture<List<Long>> holder = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            List<Long> claimed = repository.findClaimable(NOW, 1).stream()
                    .map(Notification::getId).toList();
            lockedLatch.countDown();
            awaitQuietly(release);
            return claimed;
        }));

        assertThat(lockedLatch.await(10, TimeUnit.SECONDS)).isTrue();

        // 잠긴 행은 건너뛰고 남은 행만 즉시 반환한다 (SKIP LOCKED이 아니면 여기서 블로킹).
        List<Notification> result = tx.execute(status -> repository.findClaimable(NOW, 10));

        release.countDown();
        List<Long> lockedIds = holder.get(10, TimeUnit.SECONDS);

        assertThat(lockedIds).containsExactly(locked.getId());
        assertThat(result).extracting(Notification::getId).containsExactly(available.getId());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
