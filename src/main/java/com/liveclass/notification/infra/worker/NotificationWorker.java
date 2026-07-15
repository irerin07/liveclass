package com.liveclass.notification.infra.worker;

import com.liveclass.notification.application.recipient.RecipientStatus;
import com.liveclass.notification.application.recipient.RecipientStatusPort;
import com.liveclass.notification.application.sender.NotificationSenderRouter;
import com.liveclass.notification.application.sender.PermanentSendException;
import com.liveclass.notification.application.sender.TransientSendException;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.infra.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 발송 단계 (spec §5.2). 클레임된(PROCESSING) 알림을 <b>트랜잭션 밖에서</b> 발송하고
 * 결과를 {@link NotificationResultRecorder}(TX2)에 위임한다.
 *
 * <p>발송 직전 수신 가능 여부를 확인한다 (spec §7.7). ACTIVE가 아니면 발송기를 호출하지
 * 않고 재시도 없이 최종 실패로 종료한다: WITHDRAWN은 정상 억제({@code RECIPIENT_GONE}),
 * NOT_FOUND는 데이터 이상({@code RECIPIENT_NOT_FOUND}, 경고 로그).
 *
 * <p>발송 실패는 종류에 따라 분기한다: {@link TransientSendException}은 재시도 대상,
 * {@link PermanentSendException}은 즉시 최종 실패 (spec §7.3).
 */
@Component
@RequiredArgsConstructor
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationRepository repository;
    private final RecipientStatusPort recipientStatusPort;
    private final NotificationSenderRouter senderRouter;
    private final NotificationResultRecorder resultRecorder;

    public void process(Long notificationId) {
        Notification notification = repository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "처리 대상 알림을 찾을 수 없음: id=" + notificationId));

        RecipientStatus recipientStatus =
                recipientStatusPort.check(notification.getReceiverId(), notification.getChannel());
        if (recipientStatus == RecipientStatus.WITHDRAWN) {
            resultRecorder.recordFailure(notificationId, false,
                    "RECIPIENT_GONE: 탈퇴 수신자 receiver=" + notification.getReceiverId());
            return;
        }
        if (recipientStatus == RecipientStatus.NOT_FOUND) {
            log.warn("수신자 미존재 — 데이터 이상 가능 id={} receiver={}",
                    notificationId, notification.getReceiverId());
            resultRecorder.recordFailure(notificationId, false,
                    "RECIPIENT_NOT_FOUND: 미존재 수신자 receiver=" + notification.getReceiverId());
            return;
        }

        try {
            senderRouter.send(notification);
        } catch (TransientSendException e) {
            resultRecorder.recordFailure(notificationId, true, e.getMessage());
            return;
        } catch (PermanentSendException e) {
            resultRecorder.recordFailure(notificationId, false, e.getMessage());
            return;
        }
        resultRecorder.recordSuccess(notificationId);
    }
}
