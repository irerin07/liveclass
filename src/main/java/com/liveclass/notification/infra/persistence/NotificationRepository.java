package com.liveclass.notification.infra.persistence;

import com.liveclass.notification.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /**
     * 발송 가능한 알림을 배치로 <b>클레임</b>한다 (spec §5.1).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}: 다른 인스턴스·워커가 이미 잠근 행은 건너뛰므로,
     * 여러 폴러가 동시에 실행돼도 각 알림은 정확히 한 워커에만 선점된다. 분산 락 없이
     * 행 단위로 배타성을 보장한다 (MySQL 8+, spec FR-8).
     *
     * <p>반드시 트랜잭션 안에서 호출해야 잠금이 유지된다 — 호출자({@code NotificationTransactionService})가
     * {@code @Transactional} 경계를 제공한다.
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'PROCESSING' AND processing_started_at < :threshold
            ORDER BY processing_started_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findStuck(@Param("threshold") Instant threshold,
                                 @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
               SET n.status = 'SENT', n.sentAt = :now, n.processingStartedAt = null,
                   n.claimToken = null, n.updatedAt = :now
             WHERE n.id = :id AND n.status = 'PROCESSING'
               AND n.claimToken = :claimToken
            """)
    int markSentIfCurrent(@Param("id") Long id, @Param("claimToken") String claimToken,
                          @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
               SET n.status = 'PENDING', n.nextAttemptAt = :nextAttemptAt,
                   n.lastError = :error, n.processingStartedAt = null,
                   n.claimToken = null, n.updatedAt = :now
             WHERE n.id = :id AND n.status = 'PROCESSING'
               AND n.claimToken = :claimToken
            """)
    int scheduleRetryIfCurrent(@Param("id") Long id, @Param("claimToken") String claimToken,
                               @Param("nextAttemptAt") Instant nextAttemptAt,
                               @Param("error") String error, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
               SET n.status = 'FAILED', n.lastError = :error,
                   n.processingStartedAt = null, n.claimToken = null, n.updatedAt = :now
             WHERE n.id = :id AND n.status = 'PROCESSING'
               AND n.claimToken = :claimToken
            """)
    int markFailedIfCurrent(@Param("id") Long id, @Param("claimToken") String claimToken,
                            @Param("error") String error, @Param("now") Instant now);
}
