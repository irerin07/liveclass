package com.liveclass.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 알림 시스템 운영 파라미터. 재시도·스턱 임계 등은 해당 phase에서 필드가 추가된다.
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        @DefaultValue("1s") Duration pollingInterval,
        @DefaultValue("50") int batchSize
) {
}
