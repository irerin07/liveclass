package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationPoller;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 재시도 설정 변경이 동작에 반영되는지 검증 (tasks T4.16). max-attempts를 2로 낮추면
 * 기본(3)보다 한 번 적게 시도하고 FAILED에 도달해야 한다.
 */
@TestPropertySource(properties = {
        "notification.retry.max-attempts=2",
        "notification.retry.backoff=50ms"
})
class RetryConfigOverrideTest extends IntegrationTestSupport {

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationPoller poller;

    @Autowired
    NotificationRepository repository;

    @Autowired
    NotificationAttemptRepository attemptRepository;

    @Test
    void max_attempts를_2로_낮추면_2회_시도_후_FAILED가_된다() {
        long id = notificationService.register(new RegisterNotificationCommand(
                "fail-5-times-a", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", "e-1", null), null).notification().getId();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            poller.pollOnce();
            assertThat(repository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(NotificationStatus.FAILED);
        });

        assertThat(repository.findById(id).orElseThrow().getAttemptCount()).isEqualTo(2);
        assertThat(attemptRepository.findByNotificationIdOrderByAttemptNo(id)).hasSize(2);
    }
}
