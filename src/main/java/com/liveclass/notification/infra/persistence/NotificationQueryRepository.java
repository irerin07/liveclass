package com.liveclass.notification.infra.persistence;

import static com.liveclass.notification.domain.QNotification.notification;

import com.liveclass.notification.domain.Channel;
import com.liveclass.notification.domain.Notification;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationQueryRepository {

    private final JPAQueryFactory queryFactory;

    public NotificationQueryRepository(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    public Page<Notification> findByReceiver(String receiverId, Boolean read, Pageable pageable) {
        BooleanBuilder conditions = new BooleanBuilder(notification.receiverId.eq(receiverId));
        if (read != null) {
            conditions.and(notification.channel.eq(Channel.IN_APP));
            conditions.and(read ? notification.readAt.isNotNull() : notification.readAt.isNull());
        }

        List<Notification> content = queryFactory.selectFrom(notification)
                .where(conditions)
                .orderBy(notification.createdAt.desc(), notification.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory.select(notification.count())
                .from(notification)
                .where(conditions)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
