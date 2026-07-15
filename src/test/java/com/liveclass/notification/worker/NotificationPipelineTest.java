package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationPoller;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 비동기 발송 파이프라인 통합 테스트 (tasks T3.11~). 등록 → 폴링 → 워커 → SENT의
 * 전체 흐름을 실제 MySQL에서 검증한다. 스케줄러 대신 {@code pollOnce()}를 직접 호출해
 * 결정적으로 테스트하고, 워커의 비동기 완료는 Awaitility로 기다린다.
 */
class NotificationPipelineTest extends IntegrationTestSupport {

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationPoller poller;

    @Autowired
    NotificationRepository repository;

    private long register(String refId, Channel channel) {
        RegisterNotificationCommand command = new RegisterNotificationCommand(
                "student-1", NotificationType.PAYMENT_CONFIRMED, channel, "ENROLLMENT", refId, null);
        return notificationService.register(command, null).notification().getId();
    }

    @Test
    void 등록된_알림은_폴링_후_발송되어_SENT가_된다() {
        long id = register("e-1", Channel.EMAIL);

        int claimed = poller.pollOnce();

        assertThat(claimed).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(NotificationStatus.SENT));
    }
}
