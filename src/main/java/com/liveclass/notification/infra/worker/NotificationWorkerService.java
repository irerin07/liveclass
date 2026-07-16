package com.liveclass.notification.infra.worker;

import com.liveclass.notification.application.recipient.RecipientStatus;
import com.liveclass.notification.application.recipient.RecipientStatusPort;
import com.liveclass.notification.application.sender.NotificationSenderRouter;
import com.liveclass.notification.application.sender.PermanentSendException;
import com.liveclass.notification.application.sender.TransientSendException;
import com.liveclass.notification.domain.Notification;
import com.liveclass.notification.domain.NotificationStatus;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** 클레임, 외부 발송, 결과 기록 순서를 조정하며 외부 발송 중에는 트랜잭션을 열지 않는다. */
@Service
public class NotificationWorkerService {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorkerService.class);

    private final NotificationTransactionService transactionService;
    private final RecipientStatusPort recipientStatusPort;
    private final NotificationSenderRouter senderRouter;
    private final ThreadPoolTaskExecutor workerExecutor;

    public NotificationWorkerService(
            NotificationTransactionService transactionService,
            RecipientStatusPort recipientStatusPort,
            NotificationSenderRouter senderRouter,
            @Qualifier("notificationWorkerExecutor") ThreadPoolTaskExecutor workerExecutor) {
        this.transactionService = transactionService;
        this.recipientStatusPort = recipientStatusPort;
        this.senderRouter = senderRouter;
        this.workerExecutor = workerExecutor;
    }

    public int processBatch() {
        int availableWorkers = workerExecutor.getMaxPoolSize() - workerExecutor.getActiveCount();
        if (availableWorkers <= 0) {
            return 0;
        }

        List<ClaimedNotification> claimed = transactionService.claimBatch(availableWorkers);
        for (ClaimedNotification claim : claimed) {
            try {
                workerExecutor.execute(() -> process(claim));
            } catch (RuntimeException rejected) {
                log.error("클레임한 알림을 워커에 제출하지 못함 id={} - 스턱 회수 대기",
                        claim.id(), rejected);
            }
        }
        return claimed.size();
    }

    public void process(ClaimedNotification claim) {
        try {
            processInternal(claim);
        } catch (Exception unexpected) {
            log.error("알림 처리 중 예상 밖 오류 - retryable로 기록 id={}", claim.id(), unexpected);
            recordUnexpectedFailure(claim, unexpected);
        }
    }

    private void processInternal(ClaimedNotification claim) {
        Notification notification = transactionService.findById(claim.id())
                .orElseThrow(() -> new IllegalStateException(
                        "처리 대상 알림을 찾을 수 없음: id=" + claim.id()));

        if (notification.getStatus() != NotificationStatus.PROCESSING
                || !Objects.equals(notification.getClaimToken(), claim.claimToken())) {
            log.info("발송 전 stale 클레임 폐기 id={} attempt={}", claim.id(), claim.attemptNo());
            return;
        }

        RecipientStatus recipientStatus =
                recipientStatusPort.check(notification.getReceiverId(), notification.getChannel());
        if (recipientStatus == RecipientStatus.WITHDRAWN) {
            transactionService.recordFailure(claim, false,
                    "RECIPIENT_GONE: 탈퇴 수신자 receiver=" + notification.getReceiverId());
            return;
        }
        if (recipientStatus == RecipientStatus.NOT_FOUND) {
            log.warn("수신자 미존재 - 데이터 이상 가능 id={} receiver={}",
                    claim.id(), notification.getReceiverId());
            transactionService.recordFailure(claim, false,
                    "RECIPIENT_NOT_FOUND: 미존재 수신자 receiver=" + notification.getReceiverId());
            return;
        }

        try {
            senderRouter.send(notification);
        } catch (TransientSendException e) {
            transactionService.recordFailure(claim, true, e.getMessage());
            return;
        } catch (PermanentSendException e) {
            transactionService.recordFailure(claim, false, e.getMessage());
            return;
        }
        transactionService.recordSuccess(claim);
    }

    private void recordUnexpectedFailure(ClaimedNotification claim, Exception unexpected) {
        try {
            transactionService.recordFailure(claim, true,
                    "UNKNOWN: " + unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
        } catch (Exception recordingFailure) {
            log.error("결과 기록마저 실패 id={} - 스턱 회수 대상으로 남김", claim.id(), recordingFailure);
        }
    }

}
