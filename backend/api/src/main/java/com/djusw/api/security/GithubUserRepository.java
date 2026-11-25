package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GithubUserRepository {
    private final JdbcTemplate jdbc;
    private final String table;
    private final String reposTable;

    GithubUserRepository(JdbcTemplate jdbc, @Value("${app.ingest-schema}") String schema) {
        if (schema.isBlank()) schema = "public";
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid INGEST_SCHEMA");
        }
        this.jdbc = jdbc;
        this.table = "\"" + schema + "\".users";
        this.reposTable = "\"" + schema + "\".user_repos";
    }

    @Transactional
    public CurrentUser saveLogin(GithubIdentityClient.GithubIdentity identity) {
        var users = jdbc.query("INSERT INTO " + table + " (github_id, login, role) VALUES (?, ?, 'GUEST') "
                        + "ON CONFLICT (github_id) DO UPDATE SET login = EXCLUDED.login "
                        + "WHERE " + table + ".disabled_at IS NULL RETURNING id, role",
                (rs, row) -> new CurrentUser(rs.getLong("id"), CurrentUser.Role.valueOf(rs.getString("role"))),
                identity.id(), identity.login());
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "비활성화된 계정입니다.");
        }
        CurrentUser user = users.getFirst();
        jdbc.update("DELETE FROM " + reposTable + " WHERE user_id = ?", user.id());
        jdbc.batchUpdate("INSERT INTO " + reposTable + " (user_id, repo_id, checked_at) VALUES (?, ?, now())",
                identity.repositoryIds(), 500, (statement, repoId) -> {
                    statement.setLong(1, user.id());
                    statement.setLong(2, repoId);
                });
        return user;
    }

    public UserProfile profile(long id) {
        var profiles = jdbc.query("SELECT id, github_id, login, name, consented_at FROM " + table
                        + " WHERE id = ? AND disabled_at IS NULL",
                (rs, row) -> new UserProfile(rs.getLong("id"), rs.getObject("github_id", Long.class),
                        rs.getString("login"), rs.getString("name"), rs.getObject("consented_at") != null), id);
        if (profiles.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        return profiles.getFirst();
    }

    public void completeProfile(long id, String name, String studentId, String department) {
        try {
            int changed = jdbc.update("UPDATE " + table + " SET name = ?, student_id = ?, department = ?, "
                            + "consented_at = now(), registered_at = now(), role = 'STUDENT' "
                            + "WHERE id = ? AND github_id IS NOT NULL AND role = 'GUEST' AND registered_at IS NULL AND disabled_at IS NULL",
                    name, studentId, department, id);
            if (changed != 1) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "가입 정보를 저장할 수 없습니다.");
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate_student_id", "이미 등록된 학번입니다.");
        }
    }

    public record UserProfile(long id, Long githubId, String login, String name, boolean consented) {}
}
