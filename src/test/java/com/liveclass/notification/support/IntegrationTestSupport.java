package com.liveclass.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MySQL 통합 테스트 베이스. 동시성·락(SKIP LOCKED) 검증은 H2로 불가능하므로
 * 모든 통합 테스트는 실제 MySQL 컨테이너 위에서 수행한다 (spec NFR-6).
 */
@SpringBootTest
@Testcontainers
public abstract class IntegrationTestSupport {

    @Container
    @ServiceConnection
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8");
}
