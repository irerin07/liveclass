package com.liveclass.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationWorkerService;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 상태 조회 응답의 시도 이력 검증 (tasks T4.9, FR-2 완성).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "notification.retry.backoff=50ms")
class GetWithAttemptsApiTest extends IntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationWorkerService workerService;

    @Autowired
    NotificationRepository repository;

    @Test
    void 상태_조회_응답에_시도_이력이_포함된다() throws Exception {
        long id = notificationService.register(new RegisterNotificationCommand(
                "fail-2-times-a", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", "e-1", null), null).notification().getId();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            workerService.processBatch();
            assertThat(repository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(NotificationStatus.SENT);
        });

        mockMvc.perform(get("/api/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.attemptCount").value(3))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].success").value(false))
                .andExpect(jsonPath("$.attempts[2].success").value(true))
                .andExpect(jsonPath("$.attempts[0].errorMessage").isNotEmpty());
    }
}
