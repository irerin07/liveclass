package com.liveclass.notification.domain;

/**
 * 알림 생애주기 상태 (spec §6).
 * 재시도 대기는 별도 상태가 아니라 PENDING + next_attempt_at으로 표현한다.
 */
public enum NotificationStatus {
    /** 접수 완료, 발송 대기 (next_attempt_at 도래 시 폴러가 클레임) */
    PENDING,
    /** 워커가 클레임하여 발송 처리 중 */
    PROCESSING,
    /** 발송 성공 (최종) */
    SENT,
    /** 최종 실패 (최종, 수동 재시도로만 복귀 가능) */
    FAILED
}
