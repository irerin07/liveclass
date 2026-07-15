package com.liveclass.notification.application;

import com.liveclass.notification.config.NotificationProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 재시도 백오프 계산 (spec FR-5). 실패한 시도 번호(1-based)를 받아 다음 시도까지의 대기
 * 시간을 돌려주는 순수 함수다. 시간 흐름에 의존하지 않아 단위 테스트가 결정적이다.
 */
@Component
public class BackoffPolicy {

    private final List<Duration> delays;

    public BackoffPolicy(NotificationProperties properties) {
        this.delays = properties.retry().backoff();
    }

    /**
     * {@code attemptNo}회차 시도가 실패했을 때 다음 시도까지의 대기 시간.
     * 목록 길이를 넘는 시도 번호는 마지막 값으로 고정한다.
     */
    public Duration delayFor(int attemptNo) {
        int index = Math.min(attemptNo - 1, delays.size() - 1);
        return delays.get(Math.max(index, 0));
    }
}
