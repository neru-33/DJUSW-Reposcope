package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import com.djusw.api.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectMemberListService {
    private final JdbcTemplate jdbc;
    private final String projects, members, users;

    ProjectMemberListService(JdbcTemplate jdbc, @Value("${spring.flyway.default-schema}") String schema,
                             @Value("${app.ingest-schema}") String ingestSchema) {
        if (ingestSchema.isBlank()) ingestSchema = "public";
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !ingestSchema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid database schema");
        this.jdbc = jdbc;
        projects = "\"" + schema + "\".projects";
        members = "\"" + schema + "\".project_members";
        users = "\"" + ingestSchema + "\".users";
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(CurrentUser user, long projectId, int page, int size) {
        if (page < 1 || size < 1 || size > 50)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "조회 조건을 확인하세요.");
        var project = jdbc.query("SELECT EXISTS (SELECT 1 FROM " + members
                        + " WHERE project_id = p.id AND user_id = ? AND removed_at IS NULL) AS is_member FROM "
                        + projects + " p WHERE p.id = ?",
                (rs, row) -> rs.getBoolean("is_member"), user.id(), projectId);
        if (project.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "프로젝트를 찾을 수 없습니다.");
        boolean staff = user.role() == CurrentUser.Role.PROFESSOR || user.role() == CurrentUser.Role.ADMIN
                || user.role() == CurrentUser.Role.SUPER_ADMIN;
        if (!staff && !(user.role() == CurrentUser.Role.STUDENT && project.getFirst()))
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "접근 권한이 없습니다.");

        String from = " FROM " + members + " m JOIN " + users
                + " u ON u.id = m.user_id WHERE m.project_id = ? AND m.removed_at IS NULL";
        long total = jdbc.queryForObject("SELECT count(*)" + from, Long.class, projectId);
        var items = jdbc.query("SELECT m.user_id, u.name, u.student_id, u.login, m.role, m.joined_at" + from
                        + " ORDER BY CASE WHEN m.role = 'LEADER' THEN 0 ELSE 1 END, u.name, m.user_id LIMIT ? OFFSET ?",
                (rs, row) -> new Member(rs.getLong("user_id"), rs.getString("name"), rs.getString("student_id"),
                        rs.getString("login"), rs.getString("role"), rs.getTimestamp("joined_at").toInstant()),
                projectId, size, ((long) page - 1) * size);
        return new Page(items, page, size, total);
    }

    record Member(long user_id, String name, String student_id, String login, String role, Instant joined_at) {}
    record Page(List<Member> items, int page, int size, long total) {}
}
