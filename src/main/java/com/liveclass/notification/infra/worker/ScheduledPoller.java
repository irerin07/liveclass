package com.liveclass.notification.infra.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적 폴링 트리거. 설정된 간격마다 {@link NotificationPoller#pollOnce()}를 호출한다.
 *
 * <p>{@code @Profile("!test")}: 통합 테스트에서는 이 트리거를 제외해 폴러가 테스트 데이터를
 * 임의로 처리하지 않게 하고, 테스트는 {@code pollOnce()}를 직접 호출해 결정적으로 검증한다.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ScheduledPoller {

    private final NotificationPoller poller;

    @Scheduled(fixedDelayString = "${notification.polling-interval}")
    public void poll() {
        poller.pollOnce();
    }
}
