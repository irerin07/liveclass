package com.liveclass.notification.infra.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 폴러와 독립된 주기로 스턱 알림을 회수한다. */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ScheduledStuckRecovery {

    private final StuckNotificationRecoverer recoverer;

    @Scheduled(fixedDelayString = "${notification.stuck-recovery-interval:30s}")
    public void recover() {
        recoverer.recoverBatch();
    }
}
