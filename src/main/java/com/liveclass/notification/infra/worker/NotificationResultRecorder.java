package com.liveclass.notification.infra.worker;

import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결과 기록 단계(TX2, spec §5.2). 발송 성공/실패 결과를 별도 트랜잭션으로 기록한다.
 * 이번 단계(Phase 3)는 성공(PROCESSING → SENT)만 처리하며, 재시도·최종 실패·시도 이력은
 * Phase 4에서 추가된다.
 */
@Component
@RequiredArgsConstructor
public class NotificationResultRecorder {

    private final NotificationRepository repository;
    private final Clock clock;

    @Transactional
    public void recordSuccess(Long notificationId) {
        Notification notification = repository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "결과 기록 대상 알림을 찾을 수 없음: id=" + notificationId));
        notification.markSent(clock);
    }
}
