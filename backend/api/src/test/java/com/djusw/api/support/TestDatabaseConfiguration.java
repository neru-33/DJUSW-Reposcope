package com.djusw.api.support;

import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트용 PostgreSQL 컨테이너. 로컬 실행 환경(infra/local/compose.yml)과 같은 이미지를 쓴다.
 *
 * <p>수집·계정 테이블(backend/migrations)은 capstone 마이그레이션보다 먼저 public 스키마에 적용한다.
 * 운영에서는 worker가 적용 이력을 worker_schema_migrations에 남기며 적용하고, 여기서는 Flyway로 같은 파일을
 * 같은 순서로 적용한다. 테스트 DB는 매번 빈 상태에서 시작하므로 만들어지는 테이블은 같다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfiguration {
    private static final String IMAGE = "postgres:17-alpine";
    private static final String INGEST_MIGRATIONS = "filesystem:../migrations";
    private static final String INGEST_SCHEMA = "public";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE);
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations(INGEST_MIGRATIONS)
                .schemas(INGEST_SCHEMA)
                .table("ingest_schema_history")
                .failOnMissingLocations(true)
                .load()
                .migrate();
        return container;
    }
}
