package com.liveclass.notification.infra.sender;

import com.liveclass.notification.application.sender.NotificationSender;
import com.liveclass.notification.application.sender.PermanentSendException;
import com.liveclass.notification.application.sender.TransientSendException;
import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EMAIL 채널 Mock 발송기 (과제 제약: 실제 이메일 발송 불필요, 로그로 대체).
 *
 * <p>재시도·최종 실패 흐름을 시연·테스트할 수 있도록 수신자 ID 패턴으로 실패를 주입한다:
 * <ul>
 *   <li>{@code fail-permanent-*} → {@link PermanentSendException} (즉시 최종 실패)</li>
 *   <li>{@code fail-<n>-times-*} → 시도 횟수 n회까지 {@link TransientSendException}, 이후 성공
 *       (예: {@code fail-2-times-*}는 1·2회차 실패 후 3회차 성공)</li>
 *   <li>그 외 → 성공</li>
 * </ul>
 */
@Component
public class LoggingEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);
    private static final String FAIL_PERMANENT_PREFIX = "fail-permanent-";
    private static final Pattern FAIL_N_TIMES = Pattern.compile("^fail-(\\d+)-times-.*");

    @Override
    public Channel supportedChannel() {
        return Channel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        String receiver = notification.getReceiverId();

        if (receiver.startsWith(FAIL_PERMANENT_PREFIX)) {
            throw new PermanentSendException("영구 실패 주입: receiver=" + receiver);
        }

        Matcher matcher = FAIL_N_TIMES.matcher(receiver);
        if (matcher.matches()) {
            Integer failUntilAttempt = parseFailCount(matcher.group(1));
            if (failUntilAttempt != null && notification.getAttemptCount() <= failUntilAttempt) {
                throw new TransientSendException("일시 실패 주입: receiver=" + receiver
                        + " attempt=" + notification.getAttemptCount() + "/" + failUntilAttempt);
            }
        }

        log.info("[EMAIL] 발송 id={} receiver={} type={} ref={}:{} attempt={}",
                notification.getId(), receiver, notification.getType(),
                notification.getRefType(), notification.getRefId(), notification.getAttemptCount());
    }

    /**
     * 실패 주입 횟수 파싱. int 범위를 넘거나 형식이 잘못되면 {@code null}을 반환해
     * 실패 주입 없이 정상 발송되게 한다 (API 입력으로 워커가 죽지 않도록).
     */
    private Integer parseFailCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.debug("실패 주입 횟수 파싱 불가 — 주입 생략: {}", raw);
            return null;
        }
    }
}
