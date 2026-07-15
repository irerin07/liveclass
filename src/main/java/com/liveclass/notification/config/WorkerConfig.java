package com.liveclass.notification.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 발송 인프라 (spec §5.1, NFR-4).
 *
 * <p>스케줄러 풀(폴러·스턱 회수)과 워커 풀(발송)을 분리해 서로 블로킹하지 않게 한다.
 * 워커 풀은 큐가 가득 차면 호출 스레드(폴러)가 직접 실행({@code CallerRunsPolicy})하여
 * 발송이 느릴 때 폴링에 자연스러운 backpressure를 준다.
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
        executor.setQueueCapacity(properties.batchSize());
        executor.setThreadNamePrefix("notif-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * {@code @Scheduled}가 사용하는 스케줄러. 폴러와 (Phase 5의) 스턱 회수가 상호
     * 블로킹하지 않도록 풀 크기를 2 이상으로 둔다.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.schedulerPoolSize());
        scheduler.setThreadNamePrefix("notif-sched-");
        return scheduler;
    }
}
