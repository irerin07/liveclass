package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.application.recipient.RecipientStatusPort;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationPoller;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 예상 밖 예외가 알림을 PROCESSING에 고착시키지 않음을 검증 (tasks H1). 수신자 조회에서
 * 알 수 없는 RuntimeException이 계속 발생해도 태스크 종료가 아니라 retryable 실패로
 * 기록되어, 재시도 소진 후 FAILED에 도달해야 한다.
 */
@TestPropertySource(properties = "notification.retry.backoff=50ms")
class WorkerUnexpectedErrorTest extends IntegrationTestSupport {

    @MockitoBean
    RecipientStatusPort recipientStatusPort;

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationPoller poller;

    @Autowired
    NotificationRepository repository;

    @Test
    void 예상_밖_예외는_PROCESSING에_고착되지_않고_retryable로_기록되어_결국_FAILED가_된다() {
        given(recipientStatusPort.check(any(), any()))
                .willThrow(new RuntimeException("예상 밖 오류"));

        long id = notificationService.register(new RegisterNotificationCommand(
                "student-1", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", "e-1", null), null).notification().getId();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            poller.pollOnce();
            assertThat(repository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(NotificationStatus.FAILED);
        });

        assertThat(repository.findById(id).orElseThrow().getLastError()).contains("UNKNOWN");
    }
}
