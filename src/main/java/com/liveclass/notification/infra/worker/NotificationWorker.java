package com.liveclass.notification.infra.worker;

import com.liveclass.notification.application.sender.NotificationSenderRouter;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 발송 단계 (spec §5.2). 클레임된(PROCESSING) 알림을 <b>트랜잭션 밖에서</b> 발송하고
 * 결과를 {@link NotificationResultRecorder}(TX2)에 위임한다.
 *
 * <p>발송(외부 호출)을 트랜잭션에 넣지 않는 이유: 외부 지연이 DB 커넥션·락 점유로
 * 전파되는 것을 막고, 발송 성공 후 롤백에 의한 상태 불일치를 피하기 위함이다.
 *
 * <p>이번 단계(Phase 3)는 성공 경로만 처리한다. 발송 실패 시의 재시도·최종 실패 분기는
 * Phase 4에서 추가된다.
 */
@Component
@RequiredArgsConstructor
public class NotificationWorker {

    private final NotificationRepository repository;
    private final NotificationSenderRouter senderRouter;
    private final NotificationResultRecorder resultRecorder;

    public void process(Long notificationId) {
        Notification notification = repository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "처리 대상 알림을 찾을 수 없음: id=" + notificationId));

        senderRouter.send(notification);
        resultRecorder.recordSuccess(notificationId);
    }
}
