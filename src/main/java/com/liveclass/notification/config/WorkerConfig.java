package com.liveclass.notification.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 발송 인프라 (spec §5.1, NFR-4).
 *
 * <p>발송은 전용 워커 풀에 위임한다. 워커 executor에는 대기 큐를 두지 않고,
 * 워커 서비스가 빈 실행 슬롯만큼만 클레임한다.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class WorkerConfig {

    private final NotificationProperties properties;

    @Bean
    public ThreadPoolTaskExecutor notificationWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerPoolSize());
        executor.setMaxPoolSize(properties.workerPoolSize());
        // NotificationWorkerService가 executor의 빈 실행 슬롯만큼만 클레임하므로 실행 전 backlog를
        // 만들지 않는다. 큐 대기 시간이 스턱 시간으로 오인되는 경로를 제거한다.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("notif-worker-");
        // 종료 시 진행 중·큐 대기 작업을 기다려 PROCESSING 유실을 줄인다. 강제 종료로
        // 완료하지 못한 건은 스턱 회수가 최종 안전망이다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
