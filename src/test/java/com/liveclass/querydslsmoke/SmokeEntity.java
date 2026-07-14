package com.liveclass.querydslsmoke;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Querydsl 스모크 테스트 전용 엔티티 (테스트 소스에만 존재).
 * 애플리케이션 베이스 패키지(com.liveclass.notification) 밖에 두어
 * 실제 애플리케이션 컨텍스트의 엔티티 스캔·스키마 검증에 영향을 주지 않는다.
 * Spring Boot 4.x(Hibernate 7) 환경에서 Q클래스 생성과 쿼리 실행이
 * 동작하는지 검증한다 (spec R-1, tasks T0.5~T0.6).
 */
@Entity
@Table(name = "querydsl_smoke")
public class SmokeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected SmokeEntity() {
    }

    public SmokeEntity(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
