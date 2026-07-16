package com.liveclass.notification.application;

import com.liveclass.notification.application.command.RegisterNotificationCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 요청 필드를 모호하지 않게 직렬화한 뒤 64자 SHA-256 저장 키를 만든다. */
@Component
public class IdempotencyKeyGenerator {

    private static final String EXPLICIT_NAMESPACE = "explicit:";
    private static final String GENERATED_NAMESPACE = "generated:";

    /**
     * 저장용 멱등성 키(해시)를 생성한다.
     *
     * @param explicitKey 클라이언트 제공 {@code Idempotency-Key} 헤더 값 (nullable)
     * @param command     발송 요청 내용
     */
    public String generate(String explicitKey, RegisterNotificationCommand command) {
        if (StringUtils.hasText(explicitKey)) {
            return sha256Hex(EXPLICIT_NAMESPACE + explicitKey.strip());
        }
        String composite = encode(command.type().name())
                + encode(command.refType())
                + encode(command.refId())
                + encode(command.receiverId())
                + encode(command.channel().name());
        return sha256Hex(GENERATED_NAMESPACE + composite);
    }

    private String encode(String value) {
        return value.length() + ":" + value;
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
