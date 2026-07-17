package com.liveclass.notification.application;

import com.liveclass.notification.application.command.RegisterNotificationCommand;
import com.liveclass.notification.application.result.RegistrationResult;
import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 멱등성 동시성 검증. 동일 키 다수 스레드 동시 등록 = 더블클릭·이중제출·
 * 동시 재시도 시나리오. UNIQUE 제약 + 새 트랜잭션 재조회(2차 방어)가 정확히 1건만
 * 생성함을 실제 MySQL에서 검증한다 (spec NFR-6).
 */
class IdempotencyConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationRepository repository;

    @Test
    void 동일_키_10스레드_동시_등록은_정확히_1건만_생성하고_모두_같은_ID를_받는다() throws Exception {
        int threadCount = 10;
        RegisterNotificationCommand command = new RegisterNotificationCommand(
                "student-1", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", "enrollment-42", null);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch fire = new CountDownLatch(1);
        List<Future<RegistrationResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                fire.await();
                return notificationService.register(command, null);
            }));
        }

        ready.await();
        fire.countDown();

        List<RegistrationResult> results = new ArrayList<>();
        for (Future<RegistrationResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(repository.count()).isEqualTo(1);

        Set<Long> distinctIds = results.stream()
                .map(r -> r.notification().getId())
                .collect(Collectors.toSet());
        assertThat(distinctIds).hasSize(1);

        long created = results.stream().filter(r -> !r.duplicated()).count();
        assertThat(created).isEqualTo(1);
        assertThat(results).hasSize(threadCount);
    }
}
