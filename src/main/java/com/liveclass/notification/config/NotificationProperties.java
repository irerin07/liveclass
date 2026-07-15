package com.liveclass.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 알림 시스템 운영 파라미터. 잘못된 설정(빈 backoff·음수 등)은 기동 시 fail-fast로
 * 거부한다 — 런타임에 알림이 PROCESSING에 고착되는 것보다 시작 실패가 안전하다.
 */
@Validated
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        @DefaultValue("1s") @NotNull Duration pollingInterval,
        @DefaultValue("50") @Min(1) int batchSize,
        @DefaultValue("4") @Min(1) int workerPoolSize,
        @DefaultValue("2") @Min(2) int schedulerPoolSize,
        @Valid @NotNull @DefaultValue Retry retry
) {

    public NotificationProperties {
        requirePositive("polling-interval", pollingInterval);
    }

    /**
     * 재시도 정책 (spec FR-5). {@code backoff}는 실패한 시도 번호(1-based)에 대응하는
     * 대기 시간 목록이다 — 1회차 실패 후 {@code backoff[0]}, 2회차 실패 후 {@code backoff[1]}...
     * 목록보다 시도 번호가 크면 마지막 값을 사용한다.
     */
    public record Retry(
            @DefaultValue("3") @Min(1) int maxAttempts,
            @DefaultValue({"30s", "2m", "10m"}) @NotEmpty List<Duration> backoff
    ) {

        public Retry {
            if (backoff != null) {
                backoff.forEach(delay -> requirePositive("retry.backoff", delay));
            }
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                    "notification." + name + " 은(는) 양수 Duration이어야 합니다: " + value);
        }
    }
}
