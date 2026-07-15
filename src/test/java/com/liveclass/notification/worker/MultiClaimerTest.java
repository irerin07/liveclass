package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.worker.ClaimedNotification;
import com.liveclass.notification.infra.worker.NotificationClaimer;
import com.liveclass.notification.infra.worker.NotificationWorker;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MultiClaimerTest extends IntegrationTestSupport {

    @Autowired NotificationRepository repository;
    @Autowired NotificationAttemptRepository attemptRepository;
    @Autowired NotificationClaimer claimer;
    @Autowired NotificationWorker worker;

    @Test
    void PENDING_100건을_4개_클레임_주체가_중복과_누락_없이_선점한다() throws Exception {
        Clock past = Clock.fixed(Instant.now().minusSeconds(10), ZoneOffset.UTC);
        for (int i = 0; i < 100; i++) {
            repository.save(Notification.pending("multi-" + i, "student-" + i,
                    NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                    "ENROLLMENT", "e-" + i, null, 3, past));
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<ClaimedNotification>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    List<ClaimedNotification> all = new ArrayList<>();
                    List<ClaimedNotification> batch;
                    do {
                        batch = claimer.claimBatch(50);
                        all.addAll(batch);
                        batch.forEach(worker::process);
                    } while (!batch.isEmpty());
                    return all;
                }));
            }
            ready.await();
            start.countDown();

            List<Long> ids = new ArrayList<>();
            for (Future<List<ClaimedNotification>> future : futures) {
                ids.addAll(future.get().stream().map(ClaimedNotification::id).toList());
            }
            assertThat(ids).hasSize(100);
            assertThat(new HashSet<>(ids)).hasSize(100);
            assertThat(repository.findAll()).allMatch(
                    n -> n.getStatus() == NotificationStatus.SENT);
            assertThat(attemptRepository.count()).isEqualTo(100);
        } finally {
            executor.shutdownNow();
        }
    }
}
