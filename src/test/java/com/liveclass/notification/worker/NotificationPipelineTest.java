package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationPoller;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 비동기 발송 파이프라인 통합 테스트 (tasks T3.11~T3.14). 등록 → 폴링 → 워커 → SENT의
 * 전체 흐름을 실제 MySQL에서 검증한다. 스케줄러 대신 {@code pollOnce()}를 직접 호출해
 * 결정적으로 테스트하고, 워커의 비동기 완료는 Awaitility로 기다린다.
 *
 * <p>배치 크기를 2로 낮춰 "배치 초과분이 여러 폴링에 걸쳐 처리되는" 경우를 재현한다.
 */
@TestPropertySource(properties = "notification.batch-size=2")
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

    private long sentCount() {
        return repository.findAll().stream()
                .filter(n -> n.getStatus() == NotificationStatus.SENT)
                .count();
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

    @Test
    void 등록_직후에는_아직_발송되지_않고_PENDING이다() {
        long id = register("e-2", Channel.EMAIL);

        // 폴러를 호출하지 않았으므로(스케줄러도 test 프로파일에서 제외) 발송이 일어나지 않는다.
        Notification notification = repository.findById(id).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.getProcessingStartedAt()).isNull();
    }

    @Test
    void 배치_크기를_초과한_PENDING은_여러_폴링에_걸쳐_전부_처리된다() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(register("bulk-" + i, Channel.EMAIL));
        }

        int firstClaim = poller.pollOnce();
        assertThat(firstClaim).isEqualTo(2); // 한 번의 폴링은 배치 크기(2)로 제한된다

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            poller.pollOnce(); // 남은 PENDING을 이어서 클레임한다
            assertThat(sentCount()).isEqualTo(5);
        });
    }
}
