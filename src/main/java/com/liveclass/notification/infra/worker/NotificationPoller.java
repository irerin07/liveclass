package com.liveclass.notification.infra.worker;

import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.liveclass.notification.config.NotificationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 폴링 1회분의 로직. 클레임(TX1)한 알림들을 워커 스레드풀에 위임한다.
 *
 * <p>스케줄 트리거({@code @Scheduled})와 분리되어 있어 테스트에서 {@link #pollOnce()}를
 * 직접 호출해 결정적으로 검증할 수 있다. 실제 주기 실행은 {@code ScheduledPoller}가 담당한다.
 */
@Component
public class NotificationPoller {

    private static final Logger log = LoggerFactory.getLogger(NotificationPoller.class);

    private final NotificationClaimer claimer;
    private final NotificationWorker worker;
    private final ThreadPoolTaskExecutor workerExecutor;
    private final Semaphore workerPermits;

    public NotificationPoller(NotificationClaimer claimer, NotificationWorker worker,
                              @Qualifier("notificationWorkerExecutor") ThreadPoolTaskExecutor workerExecutor,
                              NotificationProperties properties) {
        this.claimer = claimer;
        this.worker = worker;
        this.workerExecutor = workerExecutor;
        this.workerPermits = new Semaphore(properties.workerPoolSize());
    }

    /**
     * 발송 가능한 알림을 클레임해 워커 풀에 위임하고, 클레임한 건수를 반환한다.
     * 발송은 워커 스레드에서 비동기로 수행된다.
     */
    public int pollOnce() {
        int permits = acquireAvailablePermits();
        if (permits == 0) {
            return 0;
        }

        List<ClaimedNotification> claimed = claimer.claimBatch(permits);
        workerPermits.release(permits - claimed.size());
        for (ClaimedNotification claim : claimed) {
            try {
                workerExecutor.execute(() -> {
                    try {
                        worker.process(claim);
                    } finally {
                        workerPermits.release();
                    }
                });
            } catch (RuntimeException rejected) {
                workerPermits.release();
                log.error("클레임한 알림을 워커에 제출하지 못함 id={} — 스턱 회수 대상",
                        claim.id(), rejected);
            }
        }
        return claimed.size();
    }

    private int acquireAvailablePermits() {
        int acquired = 0;
        while (workerPermits.tryAcquire()) {
            acquired++;
        }
        return acquired;
    }
}
