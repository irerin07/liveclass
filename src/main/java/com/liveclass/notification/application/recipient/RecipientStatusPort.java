package com.liveclass.notification.application.recipient;

import com.liveclass.notification.domain.Channel;

/**
 * 수신 가능 여부 확인 포트 (spec §5.4, §7.7).
 *
 * <p>상태의 원천은 외부 사용자 도메인이며 본 시스템 범위 밖이다. 이 포트는 발송 직전
 * 검증의 자리만 제공하고, 실운영 전환 시 사용자 서비스 조회 또는 사용자 상태 이벤트
 * 프로젝션으로 교체된다. 현재는 패턴 스텁 구현을 쓴다.
 */
public interface RecipientStatusPort {

    RecipientStatus check(String receiverId, Channel channel);
}
