package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import com.djusw.api.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
class DeliverableService {
    private static final Set<String> FIELDS = Set.of("kind", "title", "url");
    private final JdbcTemplate jdbc;
    private final String projects, members, deliverables, accounts, auditLogs;

    DeliverableService(JdbcTemplate jdbc, @Value("${spring.flyway.default-schema}") String schema,
                       @Value("${app.ingest-schema}") String ingestSchema) {
        if (ingestSchema.isBlank()) ingestSchema = "public";
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !ingestSchema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid database schema");
        this.jdbc = jdbc;
        projects = "\"" + schema + "\".projects";
        members = "\"" + schema + "\".project_members";
        deliverables = "\"" + schema + "\".deliverables";
        auditLogs = "\"" + schema + "\".audit_logs";
        accounts = "\"" + ingestSchema + "\".users";
    }

    @Transactional(readOnly = true)
    public Items list(CurrentUser user, long projectId, int page, int size) {
        Access access = access(user, projectId, false);
        if (page < 1 || size < 1 || size > 50) throw invalid();
        long total = jdbc.queryForObject("SELECT count(*) FROM " + deliverables + " WHERE project_id = ?", Long.class, projectId);
        return new Items(jdbc.query(select() + " WHERE d.project_id = ? ORDER BY d.created_at, d.id LIMIT ? OFFSET ?",
                (rs, row) -> item(rs, user, access), projectId, size, (long) (page - 1) * size), page, size, total);
    }

    @Transactional
    public Item create(CurrentUser user, long projectId, Map<String, JsonNode> fields) {
        Access access = access(user, projectId, true);
        if (!access.staff() && access.memberRole() == null) throw forbidden();
        checkArchived(access);
        DeliverableInput input = input(fields, null);
        Long id = jdbc.queryForObject("INSERT INTO " + deliverables
                        + " (project_id, kind, title, url, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, projectId, input.kind(), input.title(), input.url(), user.id());
        audit(user, "DELIVERABLE_CREATE", id);
        return find(projectId, id, user, access);
    }

    @Transactional
    public Item update(CurrentUser user, long projectId, long id, Map<String, JsonNode> fields) {
        Access access = access(user, projectId, true);
        Item current = find(projectId, id, user, access);
        authorizeChange(user, access, current);
        checkArchived(access);
        DeliverableInput input = input(fields, current);
        jdbc.update("UPDATE " + deliverables + " SET kind = ?, title = ?, url = ?, updated_at = now()"
                + " WHERE project_id = ? AND id = ?", input.kind(), input.title(), input.url(), projectId, id);
        audit(user, "DELIVERABLE_UPDATE", id);
        return find(projectId, id, user, access);
    }

    @Transactional
    public void delete(CurrentUser user, long projectId, long id) {
        Access access = access(user, projectId, true);
        Item current = find(projectId, id, user, access);
        authorizeChange(user, access, current);
        checkArchived(access);
        jdbc.update("DELETE FROM " + deliverables + " WHERE project_id = ? AND id = ?", projectId, id);
        audit(user, "DELIVERABLE_DELETE", id);
    }

    private Access access(CurrentUser user, long projectId, boolean write) {
        var rows = jdbc.query("SELECT archived_at IS NOT NULL AS archived FROM " + projects + " WHERE id = ?"
                        + (write ? " FOR UPDATE" : ""), (rs, row) -> rs.getBoolean("archived"), projectId);
        if (rows.isEmpty()) throw notFound("프로젝트를 찾을 수 없습니다.");
        boolean staff = user.role() == CurrentUser.Role.ADMIN || user.role() == CurrentUser.Role.SUPER_ADMIN;
        String role = null;
        if (user.role() == CurrentUser.Role.STUDENT) {
            var roles = jdbc.query("SELECT role FROM " + members
                            + " WHERE project_id = ? AND user_id = ? AND removed_at IS NULL" + (write ? " FOR SHARE" : ""),
                    (rs, row) -> rs.getString(1), projectId, user.id());
            if (!roles.isEmpty()) role = roles.getFirst();
        }
        if (!staff && role == null && !(user.role() == CurrentUser.Role.PROFESSOR && !write)) throw forbidden();
        return new Access(staff, role, rows.getFirst());
    }

    private void authorizeChange(CurrentUser user, Access access, Item item) {
        if (!canChange(user, access, item.created_by())) throw forbidden();
    }

    private static boolean canChange(CurrentUser user, Access access, long createdBy) {
        return access.staff() || (access.memberRole() != null
                && ("LEADER".equals(access.memberRole()) || createdBy == user.id()));
    }

    private static void checkArchived(Access access) {
        if (access.archived()) throw new ApiException(HttpStatus.CONFLICT, "project_archived", "보관한 프로젝트는 수정할 수 없습니다.");
    }

    private Item find(long projectId, long id, CurrentUser user, Access access) {
        var items = jdbc.query(select() + " WHERE d.project_id = ? AND d.id = ?", (rs, row) -> item(rs, user, access), projectId, id);
        if (items.isEmpty()) throw notFound("결과물을 찾을 수 없습니다.");
        return items.getFirst();
    }

    private String select() {
        return "SELECT d.id, d.kind, d.title, d.url, d.created_by, COALESCE(u.name, u.login) AS created_by_name, d.created_at"
                + " FROM " + deliverables + " d LEFT JOIN " + accounts + " u ON u.id = d.created_by";
    }

    private static Item item(java.sql.ResultSet rs, CurrentUser user, Access access) throws java.sql.SQLException {
        long createdBy = rs.getLong("created_by");
        return new Item(rs.getLong("id"), rs.getString("kind"), rs.getString("title"), rs.getString("url"),
                createdBy, rs.getString("created_by_name"), rs.getTimestamp("created_at").toInstant(),
                !access.archived() && canChange(user, access, createdBy));
    }

    private static DeliverableInput input(Map<String, JsonNode> fields, Item current) {
        if (fields == null || !FIELDS.containsAll(fields.keySet())) throw invalid();
        var input = new DeliverableInput(field(fields, "kind", current == null ? null : current.kind()),
                field(fields, "title", current == null ? null : current.title()),
                field(fields, "url", current == null ? null : current.url()));
        input.validate();
        return input;
    }

    private static String field(Map<String, JsonNode> fields, String key, String fallback) {
        if (!fields.containsKey(key)) return fallback;
        JsonNode value = fields.get(key);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asString();
    }

    private void audit(CurrentUser user, String action, long id) {
        if (user.role() == CurrentUser.Role.ADMIN || user.role() == CurrentUser.Role.SUPER_ADMIN)
            jdbc.update("INSERT INTO " + auditLogs + " (actor_user_id, action, target, at) VALUES (?, ?, ?, now())",
                    user.id(), action, "deliverable:" + id);
    }

    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "결과물 정보를 확인하세요."); }
    private static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "접근 권한이 없습니다."); }
    private static ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, "not_found", message); }

    record Access(boolean staff, String memberRole, boolean archived) {}
    record Item(long id, String kind, String title, String url, @com.fasterxml.jackson.annotation.JsonIgnore long created_by,
                String created_by_name, Instant created_at, boolean can_edit) {}
    record Items(List<Item> items, int page, int size, long total) {}
}
