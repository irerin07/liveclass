package com.liveclass.querydslsmoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Boot 4.x(Hibernate 7) + Querydsl 호환성 스모크 (spec R-1).
 * Q클래스 생성(컴파일 시점)과 쿼리 "실행"(런타임)까지 모두 확인해야
 * 호환성이 검증된 것이다. 전용 설정(SmokeConfig)으로 애플리케이션
 * 컨텍스트와 완전히 분리해 실행한다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class QuerydslSmokeTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = SmokeEntity.class)
    static class SmokeConfig {
    }

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8");

    @Autowired
    EntityManager em;

    @Test
    void q클래스_생성과_쿼리_실행이_동작한다() {
        em.persist(new SmokeEntity("hello"));
        em.flush();
        em.clear();

        JPAQueryFactory queryFactory = new JPAQueryFactory(em);
        QSmokeEntity smoke = QSmokeEntity.smokeEntity;

        List<SmokeEntity> found = queryFactory
                .selectFrom(smoke)
                .where(smoke.name.eq("hello"))
                .fetch();

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getName()).isEqualTo("hello");
    }
}
