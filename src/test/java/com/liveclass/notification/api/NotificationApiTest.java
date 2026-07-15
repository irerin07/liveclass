package com.liveclass.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.liveclass.notification.domain.NotificationStatus;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 알림 등록/조회 API 통합 테스트 (tasks T1.10). 실제 MySQL 위에서 수행한다.
 */
@AutoConfigureMockMvc
class NotificationApiTest extends IntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NotificationRepository repository;

    private static final String VALID_BODY = """
            {
              "receiverId": "student-1",
              "type": "PAYMENT_CONFIRMED",
              "channel": "EMAIL",
              "refType": "ENROLLMENT",
              "refId": "enrollment-42",
              "payload": { "courseTitle": "Spring Boot 입문" }
            }
            """;

    @Test
    void 등록하면_202와_PENDING_상태의_알림_ID를_받는다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.duplicated").value(false))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long id = body.get("notificationId").asLong();

        assertThat(repository.findById(id)).hasValueSatisfying(n -> {
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.getReceiverId()).isEqualTo("student-1");
            assertThat(n.getAttemptCount()).isZero();
        });
    }

    @Test
    void 등록된_알림을_ID로_조회할_수_있다() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("notificationId").asLong();

        mockMvc.perform(get("/api/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.receiverId").value("student-1"))
                .andExpect(jsonPath("$.type").value("PAYMENT_CONFIRMED"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0));
    }

    @Test
    void 존재하지_않는_알림_조회는_404와_에러_형식을_반환한다() throws Exception {
        mockMvc.perform(get("/api/notifications/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void 필수_필드가_없으면_400과_에러_형식을_반환한다() throws Exception {
        String missingReceiver = """
                {
                  "type": "PAYMENT_CONFIRMED",
                  "channel": "EMAIL",
                  "refType": "ENROLLMENT",
                  "refId": "enrollment-42"
                }
                """;

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingReceiver))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 잘못된_enum_값은_400과_에러_형식을_반환한다() throws Exception {
        String invalidChannel = VALID_BODY.replace("\"EMAIL\"", "\"CARRIER_PIGEON\"");

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidChannel))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void payload가_64KB를_넘으면_400을_반환한다() throws Exception {
        String hugeValue = "x".repeat(70_000);
        String hugePayloadBody = """
                {
                  "receiverId": "student-1",
                  "type": "PAYMENT_CONFIRMED",
                  "channel": "EMAIL",
                  "refType": "ENROLLMENT",
                  "refId": "enrollment-42",
                  "payload": { "blob": "%s" }
                }
                """.formatted(hugeValue);

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hugePayloadBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
