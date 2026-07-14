package com.liveclass.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 멱등성 키 생성 (spec §5.3).
 *
 * <p>논리 키 정의: 클라이언트가 {@code Idempotency-Key} 헤더를 명시하면 그 값을,
 * 아니면 요청 내용 조합 {@code type:refType:refId:receiverId:channel}을 논리 키로 삼는다.
 *
 * <p>저장 키는 논리 키의 <b>SHA-256 해시(hex)</b>다. 이유:
 * <ul>
 *   <li>길이 고정(64자) — 원시 조합은 필드 최대 길이 합이 컬럼(VARCHAR 200)을 넘길 수 있다
 *       (refId 100 + receiverId 50 + refType 50 + type/channel + 구분자 → 최대 ~230자).</li>
 *   <li>구분자 충돌 방지 — refType/refId에 {@code :}가 들어가도 안전.</li>
 * </ul>
 * 개별 필드는 엔티티 컬럼에 그대로 남으므로 디버깅 시 조합 재구성이 가능하다.
 */
@Component
public class IdempotencyKeyGenerator {

    private static final String DELIMITER = ":";

    /**
     * 저장용 멱등성 키(해시)를 생성한다.
     *
     * @param explicitKey 클라이언트 제공 {@code Idempotency-Key} 헤더 값 (nullable)
     * @param command     발송 요청 내용
     */
    public String generate(String explicitKey, RegisterNotificationCommand command) {
        return sha256Hex(logicalKey(explicitKey, command));
    }

    /**
     * 해시 이전의 사람이 읽을 수 있는 논리 키. 로깅·디버깅·테스트용.
     */
    public String logicalKey(String explicitKey, RegisterNotificationCommand command) {
        if (StringUtils.hasText(explicitKey)) {
            return explicitKey.strip();
        }
        return String.join(DELIMITER,
                command.type().name(),
                command.refType(),
                command.refId(),
                command.receiverId(),
                command.channel().name());
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
