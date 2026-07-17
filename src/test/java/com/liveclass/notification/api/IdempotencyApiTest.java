package com.liveclass.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.liveclass.notification.infra.persistence.NotificationRepository;
import com.liveclass.notification.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * 멱등성 동작 통합 테스트. 실제 MySQL 위에서 수행한다.
 */
@AutoConfigureMockMvc
class IdempotencyApiTest extends IntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NotificationRepository repository;

    private static final String BODY = """
            {
              "receiverId": "student-1",
              "type": "PAYMENT_CONFIRMED",
              "channel": "EMAIL",
              "refType": "ENROLLMENT",
              "refId": "enrollment-42"
            }
            """;

    private long postExpectingAccepted(String body, boolean expectedDuplicated) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicated").value(expectedDuplicated))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("notificationId").asLong();
    }

    @Test
    void 동일_키_순차_재요청은_202_기존_ID_duplicated_true를_반환하고_행을_새로_만들지_않는다() throws Exception {
        long firstId = postExpectingAccepted(BODY, false);
        long secondId = postExpectingAccepted(BODY, true);

        assertThat(secondId).isEqualTo(firstId);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 같은_이벤트라도_채널이_다르면_각각_생성된다() throws Exception {
        long emailId = postExpectingAccepted(BODY, false);
        long inAppId = postExpectingAccepted(BODY.replace("\"EMAIL\"", "\"IN_APP\""), false);

        assertThat(inAppId).isNotEqualTo(emailId);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void 같은_Idempotency_Key에_같은_본문이면_멱등_재생으로_처리된다() throws Exception {
        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicated").value(false));

        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicated").value(true));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 같은_Idempotency_Key에_다른_본문이_오면_422_키_오용이다() throws Exception {
        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        String differentBody = BODY.replace("enrollment-42", "enrollment-99");
        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content(differentBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISUSE"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 같은_Idempotency_Key에_payload만_달라도_422다() throws Exception {
        String bodyWithPayload = BODY.replace("\"refId\": \"enrollment-42\"",
                "\"refId\": \"enrollment-42\", \"payload\": { \"amount\": 1000 }");
        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-3")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithPayload))
                .andExpect(status().isAccepted());

        String differentPayload = BODY.replace("\"refId\": \"enrollment-42\"",
                "\"refId\": \"enrollment-42\", \"payload\": { \"amount\": 9999 }");
        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-3")
                        .contentType(MediaType.APPLICATION_JSON).content(differentPayload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISUSE"));
    }

    @Test
    void 같은_Idempotency_Key에_payload_필드_순서만_다르면_정상_재생이다() throws Exception {
        String order1 = BODY.replace("\"refId\": \"enrollment-42\"",
                "\"refId\": \"enrollment-42\", \"payload\": { \"a\": 1, \"b\": 2 }");
        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-4")
                        .contentType(MediaType.APPLICATION_JSON).content(order1))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicated").value(false));

        String order2 = BODY.replace("\"refId\": \"enrollment-42\"",
                "\"refId\": \"enrollment-42\", \"payload\": { \"b\": 2, \"a\": 1 }");
        mockMvc.perform(post("/api/notifications").header("Idempotency-Key", "client-key-4")
                        .contentType(MediaType.APPLICATION_JSON).content(order2))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicated").value(true));

        assertThat(repository.count()).isEqualTo(1);
    }
}
