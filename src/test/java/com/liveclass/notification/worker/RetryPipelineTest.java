package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationAttempt;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationAttemptRepository;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationPoller;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 재시도·최종 실패 파이프라인 검증 (tasks T4.11~T4.13). 실패 주입 수신자로 재시도 흐름을
 * 재현하고, 짧은 백오프로 여러 폴링에 걸쳐 결과에 도달함을 확인한다.
 */
@TestPropertySource(properties = "notification.retry.backoff=50ms")
class RetryPipelineTest extends IntegrationTestSupport {

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationPoller poller;

    @Autowired
    NotificationRepository repository;

    @Autowired
    NotificationAttemptRepository attemptRepository;

    private long register(String receiver, String refId) {
        RegisterNotificationCommand command = new RegisterNotificationCommand(
                receiver, NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", refId, null);
        return notificationService.register(command, null).notification().getId();
    }

    private void drainUntil(long id, NotificationStatus target) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            poller.pollOnce();
            assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(target);
        });
    }

    private List<NotificationAttempt> attempts(long id) {
        return attemptRepository.findByNotificationIdOrderByAttemptNo(id);
    }

    @Test
    void 두_번_실패_후_세번째_시도에_성공하고_시도_이력이_3건_남는다() {
        long id = register("fail-2-times-a", "e-1");

        drainUntil(id, NotificationStatus.SENT);

        assertThat(attempts(id)).hasSize(3);
        assertThat(attempts(id)).filteredOn(a -> !a.isSuccess()).hasSize(2);
        assertThat(attempts(id).getLast().isSuccess()).isTrue();
        assertThat(repository.findById(id).orElseThrow().getAttemptCount()).isEqualTo(3);
    }

    @Test
    void 최대_시도까지_연속_실패하면_FAILED가_되고_사유와_전체_이력이_남는다() {
        long id = register("fail-5-times-b", "e-2");

        drainUntil(id, NotificationStatus.FAILED);

        Notification failed = repository.findById(id).orElseThrow();
        assertThat(failed.getLastError()).isNotBlank();
        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(attempts(id)).hasSize(3).allMatch(a -> !a.isSuccess());
    }

    @Test
    void 영구_실패는_재시도_없이_즉시_FAILED가_된다() {
        long id = register("fail-permanent-c", "e-3");

        int claimed = poller.pollOnce();
        assertThat(claimed).isEqualTo(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(NotificationStatus.FAILED));

        assertThat(repository.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
        assertThat(attempts(id)).hasSize(1).allMatch(a -> !a.isSuccess());
    }
}
