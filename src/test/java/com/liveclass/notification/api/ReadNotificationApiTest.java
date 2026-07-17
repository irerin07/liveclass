package com.liveclass.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.command.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReadNotificationApiTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void IN_APP_알림은_여러_번_요청해도_최초_readAt을_유지한다() throws Exception {
        long id = register(Channel.IN_APP);

        mockMvc.perform(patch("/api/notifications/{id}/read", id))
                .andExpect(status().isOk());
        Instant firstReadAt = notificationRepository.findById(id).orElseThrow().getReadAt();

        mockMvc.perform(patch("/api/notifications/{id}/read", id))
                .andExpect(status().isOk());
        Instant secondReadAt = notificationRepository.findById(id).orElseThrow().getReadAt();

        assertThat(firstReadAt).isNotNull().isEqualTo(secondReadAt);
    }

    @Test
    void EMAIL_알림은_400을_반환한다() throws Exception {
        long id = register(Channel.EMAIL);

        mockMvc.perform(patch("/api/notifications/{id}/read", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_SUPPORTED"));
    }

    @Test
    void 존재하지_않는_알림은_404를_반환한다() throws Exception {
        mockMvc.perform(patch("/api/notifications/{id}/read", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void 아직_발송되지_않은_IN_APP_알림은_409를_반환한다() throws Exception {
        long id = registerPending(Channel.IN_APP);

        mockMvc.perform(patch("/api/notifications/{id}/read", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_DELIVERED"));

        assertThat(notificationRepository.findById(id).orElseThrow().getReadAt()).isNull();
    }

    @Test
    void 동시_읽음_요청은_모두_성공하고_readAt을_기록한다() throws Exception {
        long id = register(Channel.IN_APP);
        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Integer>> responses = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return mockMvc.perform(patch("/api/notifications/{id}/read", id))
                                .andReturn().getResponse().getStatus();
                    }))
                    .toList();

            ready.await();
            start.countDown();

            for (Future<Integer> response : responses) {
                assertThat(response.get()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(notificationRepository.findById(id).orElseThrow().getReadAt()).isNotNull();
    }

    private long register(Channel channel) {
        long id = registerPending(channel);
        jdbcTemplate.update("UPDATE notifications SET status = 'SENT', sent_at = UTC_TIMESTAMP(6) "
                + "WHERE id = ?", id);
        return id;
    }

    private long registerPending(Channel channel) {
        return notificationService.register(new RegisterNotificationCommand(
                "user-1",
                NotificationType.PAYMENT_CONFIRMED,
                channel,
                "PAYMENT",
                "payment-" + channel,
                null
        ), null).notification().getId();
    }
}
