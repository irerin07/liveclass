package com.liveclass.notification.infra.persistence;

import com.liveclass.notification.domain.NotificationAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, Long> {

    List<NotificationAttempt> findByNotificationIdOrderByAttemptNo(Long notificationId);
}
