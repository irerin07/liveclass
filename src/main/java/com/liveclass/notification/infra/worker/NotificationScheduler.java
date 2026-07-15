package com.liveclass.notification.infra.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 알림 폴링과 스턱 복구의 주기 실행 진입점. */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationWorkerService workerService;
    private final NotificationTransactionService transactionService;

    @Scheduled(fixedDelayString = "${notification.polling-interval}")
    public void poll() {
        workerService.processBatch();
    }

    @Scheduled(fixedDelayString = "${notification.stuck-recovery-interval}")
    public void recoverStuck() {
        transactionService.recoverStuck();
    }
}
