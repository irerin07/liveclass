package com.liveclass.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 잘못된 설정이 기동 시 거부되는지 검증한다.
 */
class NotificationPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnablePropsConfig.class);

    @Configuration
    @EnableConfigurationProperties(NotificationProperties.class)
    static class EnablePropsConfig {
    }

    @Test
    void 기본값은_정상_바인딩된다() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void max_attempts가_0이면_기동에_실패한다() {
        runner.withPropertyValues("notification.retry.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void backoff가_비어_있으면_기동에_실패한다() {
        runner.withPropertyValues("notification.retry.backoff=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void backoff에_0이_있으면_기동에_실패한다() {
        runner.withPropertyValues("notification.retry.backoff=0s,2m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void batch_size가_0이면_기동에_실패한다() {
        runner.withPropertyValues("notification.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void stuck_threshold가_0이면_기동에_실패한다() {
        runner.withPropertyValues("notification.stuck-threshold=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void stuck_recovery_interval이_0이면_기동에_실패한다() {
        runner.withPropertyValues("notification.stuck-recovery-interval=0s")
                .run(context -> assertThat(context).hasFailed());
    }
}
