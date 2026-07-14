package com.liveclass.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;

class IdempotencyKeyGeneratorTest {

    private final IdempotencyKeyGenerator generator = new IdempotencyKeyGenerator();

    private RegisterNotificationCommand command(String receiverId, NotificationType type,
                                                Channel channel, String refType, String refId) {
        return new RegisterNotificationCommand(receiverId, type, channel, refType, refId, null);
    }

    private RegisterNotificationCommand sample() {
        return command("student-1", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL,
                "ENROLLMENT", "enrollment-42");
    }

    @Test
    void 같은_내용은_같은_키를_만든다() {
        assertThat(generator.generate(null, sample()))
                .isEqualTo(generator.generate(null, sample()));
    }

    @Test
    void 저장_키는_64자_해시다() {
        assertThat(generator.generate(null, sample())).hasSize(64);
    }

    @Test
    void 채널만_달라도_다른_키가_된다() {
        String email = generator.generate(null,
                command("student-1", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", "e-42"));
        String inApp = generator.generate(null,
                command("student-1", NotificationType.PAYMENT_CONFIRMED, Channel.IN_APP, "ENROLLMENT", "e-42"));

        assertThat(email).isNotEqualTo(inApp);
    }

    @Test
    void 참조_대상이_다르면_다른_키가_된다() {
        String a = generator.generate(null,
                command("student-1", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", "e-1"));
        String b = generator.generate(null,
                command("student-1", NotificationType.PAYMENT_CONFIRMED, Channel.EMAIL, "ENROLLMENT", "e-2"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 명시적_헤더_키가_있으면_내용과_무관하게_우선한다() {
        String withHeader = generator.generate("client-key-123", sample());
        String differentContentSameHeader = generator.generate("client-key-123",
                command("other-student", NotificationType.COURSE_D1, Channel.IN_APP, "COURSE", "c-9"));

        assertThat(withHeader).isEqualTo(differentContentSameHeader);
        assertThat(withHeader).isNotEqualTo(generator.generate(null, sample()));
    }

    @Test
    void 최대_길이_입력도_64자_해시로_컬럼_길이를_넘지_않는다() {
        RegisterNotificationCommand maxLength = command(
                "r".repeat(50), NotificationType.ENROLLMENT_COMPLETED, Channel.IN_APP,
                "t".repeat(50), "i".repeat(100));

        assertThat(generator.generate(null, maxLength)).hasSize(64);
    }

    @Test
    void 논리_키는_사람이_읽을_수_있는_조합이다() {
        assertThat(generator.logicalKey(null, sample()))
                .isEqualTo("PAYMENT_CONFIRMED:ENROLLMENT:enrollment-42:student-1:EMAIL");
    }
}
