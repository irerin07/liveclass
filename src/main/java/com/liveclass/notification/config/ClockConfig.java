package com.liveclass.notification.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 사용 지점을 전부 Clock 주입으로 통일한다.
 * 재시도 스케줄·스턱 판정 테스트에서 시간을 조작하기 위한 장치다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
