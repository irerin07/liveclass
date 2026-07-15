package com.liveclass.notification.application.recipient;

/**
 * 발송 직전 확인하는 수신자 상태 (spec §7.7). 발송 가부(boolean)가 아니라 상태를 반환하고,
 * 발송 여부 판단(정책)은 워커가 내린다 — 포트는 상태의 원천을 대리할 뿐이다.
 * 실운영에서는 DORMANT, SUSPENDED, OPTED_OUT 등으로 확장될 수 있다.
 */
public enum RecipientStatus {
    /** 정상 — 발송 진행 */
    ACTIVE,
    /** 탈퇴 — 정상 억제 (RECIPIENT_GONE) */
    WITHDRAWN,
    /** 미존재 — 데이터 이상 (RECIPIENT_NOT_FOUND) */
    NOT_FOUND
}
