package com.liveclass.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.infra.worker.NotificationPoller;
import com.liveclass.notification.infra.worker.NotificationWorker;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WorkerCapacityAndApiIsolationTest extends IntegrationTestSupport {

    @MockitoBean NotificationWorker worker;
    @Autowired NotificationPoller poller;
    @Autowired NotificationRepository repository;
    @Autowired MockMvc mockMvc;

    @Test
    void 워커가_가득_차면_추가_알림을_미리_PROCESSING으로_클레임하지_않고_API는_응답한다()
            throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        doAnswer(invocation -> {
            workersStarted.countDown();
            releaseWorkers.await(5, TimeUnit.SECONDS);
            return null;
        }).when(worker).process(any());

        Clock past = Clock.fixed(Instant.now().minusSeconds(10), ZoneOffset.UTC);
        for (int i = 0; i < 10; i++) {
            repository.save(Notification.pending("capacity-" + i, "student-" + i,
                    NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                    "ENROLLMENT", "e-" + i, null, 3, past));
        }

        assertThat(poller.pollOnce()).isEqualTo(4);
        assertThat(workersStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(poller.pollOnce()).isZero();
        assertThat(repository.findAll().stream()
                .filter(n -> n.getStatus() == NotificationStatus.PROCESSING)).hasSize(4);
        assertThat(repository.findAll().stream()
                .filter(n -> n.getStatus() == NotificationStatus.PENDING)).hasSize(6);

        String body = """
                {"receiverId":"api-user","type":"PAYMENT_CONFIRMED","channel":"EMAIL",
                 "refType":"ENROLLMENT","refId":"api-while-workers-blocked"}
                """;
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        releaseWorkers.countDown();
    }
}
