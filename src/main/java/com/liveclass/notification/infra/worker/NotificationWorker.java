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

    public void process(ClaimedNotification claim) {
        try {
            processInternal(claim);
        } catch (Exception unexpected) {
            // 알 수 없는 오류(findById·recipient 조회·sender 내부 등)는 retryable로 기록해
            // 태스크 종료로 인한 영구 PROCESSING 고착을 막는다.
            log.error("알림 처리 중 예상 밖 오류 — retryable로 기록 id={}", claim.id(), unexpected);
            recordUnexpectedFailure(claim, unexpected);
        }
    }

    private void processInternal(ClaimedNotification claim) {
        Notification notification = repository.findById(claim.id())
                .orElseThrow(() -> new IllegalStateException(
                        "처리 대상 알림을 찾을 수 없음: id=" + claim.id()));

        if (notification.getStatus() != com.liveclass.notification.domain.NotificationStatus.PROCESSING
                || notification.getAttemptCount() != claim.attemptNo()
                || !java.util.Objects.equals(notification.getClaimToken(), claim.claimToken())) {
            log.info("발송 전 stale 클레임 폐기 id={} attempt={}", claim.id(), claim.attemptNo());
            return;
        }

        RecipientStatus recipientStatus =
                recipientStatusPort.check(notification.getReceiverId(), notification.getChannel());
        if (recipientStatus == RecipientStatus.WITHDRAWN) {
            resultRecorder.recordFailure(claim, false,
                    "RECIPIENT_GONE: 탈퇴 수신자 receiver=" + notification.getReceiverId());
            return;
        }
        if (recipientStatus == RecipientStatus.NOT_FOUND) {
            log.warn("수신자 미존재 — 데이터 이상 가능 id={} receiver={}",
                    claim.id(), notification.getReceiverId());
            resultRecorder.recordFailure(claim, false,
                    "RECIPIENT_NOT_FOUND: 미존재 수신자 receiver=" + notification.getReceiverId());
            return;
        }

        try {
            senderRouter.send(notification);
        } catch (TransientSendException e) {
            resultRecorder.recordFailure(claim, true, e.getMessage());
            return;
        } catch (PermanentSendException e) {
            resultRecorder.recordFailure(claim, false, e.getMessage());
            return;
        }
        resultRecorder.recordSuccess(claim);
    }

    private void recordUnexpectedFailure(ClaimedNotification claim, Exception unexpected) {
        try {
            resultRecorder.recordFailure(claim, true,
                    "UNKNOWN: " + unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
        } catch (Exception recordingFailure) {
            // 결과 기록마저 실패(예: DB 장애)하면 상태를 바꿀 수 없다 — 스턱 회수(Phase 5)가 최종 안전망.
            log.error("결과 기록마저 실패 id={} — 스턱 회수 대상으로 남김", claim.id(), recordingFailure);
        }
    }
}
