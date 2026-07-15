package com.liveclass.notification.infra.recipient;

import com.liveclass.notification.application.recipient.RecipientStatus;
import com.liveclass.notification.application.recipient.RecipientStatusPort;
import com.liveclass.notification.domain.Channel;
import org.springframework.stereotype.Component;

/**
 * 수신자 상태 스텁 (spec §7.7). 사용자 도메인이 없는 과제 범위에서 수신자 ID 패턴으로
 * 상태를 시뮬레이션한다:
 * <ul>
 *   <li>{@code withdrawn-*} → {@link RecipientStatus#WITHDRAWN}</li>
 *   <li>{@code ghost-*} → {@link RecipientStatus#NOT_FOUND}</li>
 *   <li>그 외 → {@link RecipientStatus#ACTIVE}</li>
 * </ul>
 * 실운영 전환 시 이 어댑터가 사용자 서비스 조회로 교체된다.
 */
@Component
public class PatternRecipientStatusPort implements RecipientStatusPort {

    @Override
    public RecipientStatus check(String receiverId, Channel channel) {
        if (receiverId.startsWith("withdrawn-")) {
            return RecipientStatus.WITHDRAWN;
        }
        if (receiverId.startsWith("ghost-")) {
            return RecipientStatus.NOT_FOUND;
        }
        return RecipientStatus.ACTIVE;
    }
}
