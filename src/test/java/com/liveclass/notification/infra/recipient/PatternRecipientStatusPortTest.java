package com.liveclass.notification.infra.recipient;

import static org.assertj.core.api.Assertions.assertThat;

import com.liveclass.notification.application.recipient.RecipientStatus;
import com.liveclass.notification.domain.Channel;
import org.junit.jupiter.api.Test;

class PatternRecipientStatusPortTest {

    private final PatternRecipientStatusPort port = new PatternRecipientStatusPort();

    @Test
    void withdrawn_접두어는_WITHDRAWN이다() {
        assertThat(port.check("withdrawn-1", Channel.EMAIL)).isEqualTo(RecipientStatus.WITHDRAWN);
    }

    @Test
    void ghost_접두어는_NOT_FOUND다() {
        assertThat(port.check("ghost-1", Channel.EMAIL)).isEqualTo(RecipientStatus.NOT_FOUND);
    }

    @Test
    void 그_외_수신자는_ACTIVE다() {
        assertThat(port.check("student-1", Channel.IN_APP)).isEqualTo(RecipientStatus.ACTIVE);
    }
}
