package com.liveclass.notification.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * MySQL 통합 테스트 베이스. 동시성·락(SKIP LOCKED) 검증은 H2로 불가능하므로
 * 모든 통합 테스트는 실제 MySQL 컨테이너 위에서 수행한다 (spec NFR-6).
 *
 * <p><b>싱글턴 컨테이너 패턴</b>: 컨테이너를 static 블록에서 1회 시작하고 정지시키지
 * 않는다(JVM 종료 시 Ryuk가 회수). {@code @Testcontainers} + 베이스 클래스의
 * {@code @Container}를 쓰면 각 서브클래스 종료 시 공유 컨테이너가 정지되어, 뒤에
 * 실행되는 클래스가 죽은 컨테이너에 붙는 문제가 있어 이 패턴을 쓴다.
 *
 * <p>컨테이너를 테스트 전체가 공유하므로, 멱등성 키(내용 기반) 충돌을 막기 위해
 * 각 테스트 전에 데이터를 정리한다. FK 순서를 지켜 attempts → notifications 순으로 삭제.
 */
@SpringBootTest
public abstract class IntegrationTestSupport {

    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM notification_attempts");
        jdbcTemplate.execute("DELETE FROM notifications");
    }
}
