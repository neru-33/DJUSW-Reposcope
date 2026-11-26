package com.djusw.api;

import com.djusw.api.support.DatabaseTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@DatabaseTest
class OperatorBootstrapMigrationOrderTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path migrations;
    private String schema;

    @BeforeEach
    void setUp() throws IOException {
        schema = "migration_order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        copyResource("V1__create_capstone_tables.sql");
    }

    private void copyResource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(input);
            Files.copy(input, migrations.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void projectMigrations() throws IOException {
        Files.writeString(migrations.resolve("V2__project_registration.sql"),
                "ALTER TABLE ${flyway:defaultSchema}.projects ADD COLUMN migration_order_team_name text;\n");
        Files.writeString(migrations.resolve("V3__project_primary_repository.sql"),
                "ALTER TABLE ${flyway:defaultSchema}.projects ADD COLUMN migration_order_primary_repo text;\n");
    }

    private void migrate() {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + migrations).failOnMissingLocations(true).load().migrate();
    }

    private void assertBothProjectMigrationsApplied() {
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version IN ('2', '3') AND success", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'projects' AND column_name IN ('migration_order_team_name', 'migration_order_primary_repo')", Integer.class, schema));
    }

    @Test
    void operatorMigrationFirstPreservesMarkerWhenProjectVersionsArrive() throws Exception {
        copyResource("R__operator_bootstrap.sql");
        migrate();
        jdbc.update("INSERT INTO " + schema + ".operator_bootstrap (singleton) VALUES (true)");
        projectMigrations();
        migrate();
        assertBothProjectMigrationsApplied();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".operator_bootstrap WHERE singleton", Integer.class));
    }

    @Test
    void operatorMigrationAppliesAfterProjectVersionsAndRepeatedRunsPreserveMarker() throws Exception {
        projectMigrations();
        migrate();
        assertBothProjectMigrationsApplied();
        copyResource("R__operator_bootstrap.sql");
        migrate();
        jdbc.update("INSERT INTO " + schema + ".operator_bootstrap (singleton) VALUES (true)");
        migrate();
        Path repeatable = migrations.resolve("R__operator_bootstrap.sql");
        Files.writeString(repeatable, Files.readString(repeatable, StandardCharsets.UTF_8) + "\n-- 반복 적용 검사\n", StandardCharsets.UTF_8);
        migrate();
        assertBothProjectMigrationsApplied();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".operator_bootstrap WHERE singleton", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE type = 'SQL' AND version IS NULL AND success", Integer.class));
    }
}
