package com.djusw.api;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_CONNECTION_TEST", matches = "true")
class MigrationTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.flyway.default-schema}")
    private String schema;

    @Test
    void createsCapstoneTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_name <> 'flyway_schema_history'
                ORDER BY table_name""", String.class, schema);
        assertEquals(List.of("audit_logs", "deliverables", "project_invites", "project_members",
                "project_repos", "project_status_history", "projects"), tables);
    }

    @Test
    void allowsOneActiveInvitePerProject() {
        long projectId = insertProject();
        String sql = "INSERT INTO " + schema + ".project_invites (project_id, code_hash, created_by, expires_at)"
                + " VALUES (?, ?, 1, now() + interval '7 days')";
        jdbc.update(sql, projectId, "hash-1");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, projectId, "hash-2"));
    }

    @Test
    void allowsOneActiveLinkPerRepository() {
        long first = insertProject();
        long second = insertProject();
        String sql = "INSERT INTO " + schema + ".project_repos (project_id, github_repo_id, linked_by)"
                + " VALUES (?, 30000001, 1)";
        jdbc.update(sql, first);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, second));
    }

    private long insertProject() {
        return jdbc.queryForObject("INSERT INTO " + schema + ".projects (name, created_by)"
                + " VALUES ('test project', 1) RETURNING id", Long.class);
    }
}
