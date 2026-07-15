package com.liveclass.notification.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 알림 시스템 운영 파라미터. 스턱 임계 등은 해당 phase에서 필드가 추가된다.
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        @DefaultValue("1s") Duration pollingInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("4") int workerPoolSize,
        @DefaultValue("2") int schedulerPoolSize,
        @DefaultValue Retry retry
) {

    /**
     * 재시도 정책 (spec FR-5). {@code backoff}는 실패한 시도 번호(1-based)에 대응하는
     * 대기 시간 목록이다 — 1회차 실패 후 {@code backoff[0]}, 2회차 실패 후 {@code backoff[1]}...
     * 목록보다 시도 번호가 크면 마지막 값을 사용한다.
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue({"30s", "2m", "10m"}) List<Duration> backoff
    ) {
    }
}
