package com.liveclass.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.notification.config.NotificationProperties;
import com.liveclass.notification.config.NotificationProperties.Retry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private BackoffPolicy policy(Duration... delays) {
        NotificationProperties properties = new NotificationProperties(
                Duration.ofSeconds(1), 50, 4, 2,
                Duration.ofMinutes(5), Duration.ofSeconds(30),
                new Retry(3, List.of(delays)));
        return new BackoffPolicy(properties);
    }

    @Test
    void 시도_번호에_해당하는_대기_시간을_돌려준다() {
        BackoffPolicy policy = policy(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));

        assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayFor(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.delayFor(3)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 목록보다_큰_시도_번호는_마지막_값으로_고정된다() {
        BackoffPolicy policy = policy(Duration.ofSeconds(30), Duration.ofMinutes(2));

        assertThat(policy.delayFor(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.delayFor(10)).isEqualTo(Duration.ofMinutes(2));
    }
}
