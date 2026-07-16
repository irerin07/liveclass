package com.liveclass.notification.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegisterNotificationCommand;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationType;
import com.liveclass.notification.support.IntegrationTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class UserNotificationApiTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationService notificationService;

    @Test
    void 수신자_알림을_최신순으로_페이지네이션한다() throws Exception {
        long oldest = register("user-1", "ref-1", Channel.EMAIL);
        long middle = register("user-1", "ref-2", Channel.IN_APP);
        long newest = register("user-1", "ref-3", Channel.EMAIL);
        register("other-user", "ref-4", Channel.IN_APP);
        setCreatedAt(oldest, "2026-01-01T00:00:00Z");
        setCreatedAt(middle, "2026-01-02T00:00:00Z");
        setCreatedAt(newest, "2026-01-03T00:00:00Z");

        mockMvc.perform(get("/api/users/user-1/notifications")
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newest))
                .andExpect(jsonPath("$.content[1].id").value(middle))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void read_true는_읽은_IN_APP_알림만_반환한다() throws Exception {
        long read = register("user-1", "read", Channel.IN_APP);
        register("user-1", "unread", Channel.IN_APP);
        register("user-1", "email", Channel.EMAIL);
        jdbcTemplate.update("UPDATE notifications SET read_at = ? WHERE id = ?",
                Instant.parse("2026-01-04T00:00:00Z"), read);

        mockMvc.perform(get("/api/users/user-1/notifications").param("read", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(read))
                .andExpect(jsonPath("$.content[0].channel").value("IN_APP"))
                .andExpect(jsonPath("$.content[0].readAt").isNotEmpty());
    }

    @Test
    void read_false는_읽지_않은_IN_APP_알림만_반환한다() throws Exception {
        long read = register("user-1", "read", Channel.IN_APP);
        long unread = register("user-1", "unread", Channel.IN_APP);
        register("user-1", "email", Channel.EMAIL);
        jdbcTemplate.update("UPDATE notifications SET read_at = ? WHERE id = ?",
                Instant.parse("2026-01-04T00:00:00Z"), read);

        mockMvc.perform(get("/api/users/user-1/notifications").param("read", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(unread))
                .andExpect(jsonPath("$.content[0].channel").value("IN_APP"))
                .andExpect(jsonPath("$.content[0].readAt").isEmpty());
    }

    private long register(String receiverId, String refId, Channel channel) {
        return notificationService.register(new RegisterNotificationCommand(
                receiverId,
                NotificationType.PAYMENT_CONFIRMED,
                channel,
                "PAYMENT",
                refId,
                null
        ), null).notification().getId();
    }

    private void setCreatedAt(long id, String instant) {
        jdbcTemplate.update("UPDATE notifications SET created_at = ? WHERE id = ?",
                Instant.parse(instant), id);
    }
}
