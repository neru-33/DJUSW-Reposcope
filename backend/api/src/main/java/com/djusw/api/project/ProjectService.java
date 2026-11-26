package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import com.djusw.api.security.CurrentUser;
import com.djusw.api.security.GithubUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.JsonNode;

@Service
class ProjectService {
    private final JdbcTemplate jdbc;
    private final GithubUserRepository users;
    private final String projects;
    private final String members;
    private final String projectRepos;
    private final String accountUsers;
    private final String ingestRepos;
    private final String auditLogs;

    ProjectService(JdbcTemplate jdbc, GithubUserRepository users,
                   @Value("${spring.flyway.default-schema}") String schema,
                   @Value("${app.ingest-schema}") String ingestSchema) {
        if (ingestSchema.isBlank()) ingestSchema = "public";
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !ingestSchema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid database schema");
        this.jdbc = jdbc;
        this.users = users;
        this.projects = "\"" + schema + "\".projects";
        this.members = "\"" + schema + "\".project_members";
        this.projectRepos = "\"" + schema + "\".project_repos";
        this.accountUsers = "\"" + ingestSchema + "\".users";
        this.ingestRepos = "\"" + ingestSchema + "\".repos";
        this.auditLogs = "\"" + schema + "\".audit_logs";
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectDetail detail(CurrentUser user, long id) {
        var matches = jdbc.query("SELECT p.*, m.role AS my_role FROM " + projects + " p LEFT JOIN " + members
                        + " m ON m.project_id = p.id AND m.user_id = ? AND m.removed_at IS NULL WHERE p.id = ?",
                (rs, row) -> new ProjectRow(rs.getLong("id"), rs.getString("name"), rs.getString("team_name"),
                        rs.getString("description"), rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class), rs.getString("status"), rs.getString("visibility"),
                        rs.getString("my_role"), rs.getTimestamp("archived_at") != null,
                        rs.getTimestamp("created_at").toInstant()), user.id(), id);
        if (matches.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "프로젝트를 찾을 수 없습니다.");
        var p = matches.getFirst();
        boolean staff = user.role() == CurrentUser.Role.ADMIN || user.role() == CurrentUser.Role.SUPER_ADMIN;
        boolean professor = user.role() == CurrentUser.Role.PROFESSOR;
        boolean student = user.role() == CurrentUser.Role.STUDENT;
        if (!staff && !professor && !(student && (p.myRole() != null || "STUDENTS".equals(p.visibility())))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "접근 권한이 없습니다.");
        }
        boolean canEdit = !p.archived() && (staff || (student && "LEADER".equals(p.myRole())));
        var projectMembers = jdbc.query("SELECT m.user_id, u.name, u.login, u.github_id, m.role FROM " + members
                        + " m JOIN " + accountUsers + " u ON u.id = m.user_id WHERE m.project_id = ? AND m.removed_at IS NULL"
                        + " ORDER BY CASE WHEN m.role = 'LEADER' THEN 0 ELSE 1 END, u.name, m.user_id",
                (rs, row) -> new Member(rs.getLong("user_id"), rs.getString("name"), rs.getString("login"),
                        rs.getObject("github_id", Long.class), rs.getString("role")), id);
        var repos = jdbc.query("SELECT r.repo_id, r.full_name, r.is_private, pr.is_primary FROM " + projectRepos
                        + " pr JOIN " + ingestRepos + " r ON r.repo_id = pr.github_repo_id"
                        + " WHERE pr.project_id = ? AND pr.unlinked_at IS NULL ORDER BY pr.linked_at, pr.id",
                (rs, row) -> new Repo(rs.getLong("repo_id"), rs.getString("full_name"),
                        rs.getBoolean("is_private"), rs.getBoolean("is_primary")), id);
        return new ProjectDetail(p.id(), p.name(), p.teamName(), p.description(), p.startsOn(), p.endsOn(),
                p.status(), p.visibility(), p.myRole(), p.archived(), canEdit, p.createdAt(), projectMembers, repos);
    }

    @Transactional
    public ProjectDetail create(CurrentUser user, ProjectController.CreateProject request) {
        validate(request);
        var profile = users.profile(user.id());
        String visibility = request.visibility() == null ? "TEAM" : request.visibility();
        var project = jdbc.queryForObject("INSERT INTO " + projects
                        + " (name, team_name, description, starts_on, ends_on, visibility, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id, status, created_at",
                (rs, row) -> new CreatedProject(rs.getLong("id"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()),
                request.name(), request.team_name(), request.description(), request.starts_on(), request.ends_on(),
                visibility, user.id());
        jdbc.update("INSERT INTO " + members + " (project_id, user_id, role) VALUES (?, ?, 'LEADER')",
                project.id(), user.id());
        return new ProjectDetail(project.id(), request.name(), request.team_name(), request.description(),
                request.starts_on(), request.ends_on(), project.status(), visibility, "LEADER", false, true,
                project.createdAt(), List.of(new Member(user.id(), profile.name(), profile.login(),
                profile.githubId(), "LEADER")), List.of());
    }

    @Transactional
    public ProjectDetail update(CurrentUser user, long id, Map<String, JsonNode> fields) {
        var current = lockEditableProject(user, id);
        if (fields == null || !Set.of("name", "team_name", "description", "starts_on", "ends_on", "visibility")
                .containsAll(fields.keySet())) throw invalidRequest();
        String name = textField(fields, "name", current.name());
        String teamName = textField(fields, "team_name", current.teamName());
        String description = textField(fields, "description", current.description());
        String visibility = textField(fields, "visibility", current.visibility());
        LocalDate startsOn = dateField(fields, "starts_on", current.startsOn());
        LocalDate endsOn = dateField(fields, "ends_on", current.endsOn());
        var updated = new ProjectController.CreateProject(name, teamName, description, startsOn, endsOn, visibility);
        if (visibility == null) throw invalidRequest();
        validate(updated);
        jdbc.update("UPDATE " + projects + " SET name = ?, team_name = ?, description = ?, starts_on = ?, "
                        + "ends_on = ?, visibility = ?, updated_at = now() WHERE id = ?",
                name, teamName, description, startsOn, endsOn, visibility, id);
        auditOperator(user, "PROJECT_UPDATE", id);
        return detail(user, id);
    }

    @Transactional
    public void archive(CurrentUser user, long id) {
        lockEditableProject(user, id);
        jdbc.update("UPDATE " + projects + " SET archived_at = now(), updated_at = now() WHERE id = ?", id);
        auditOperator(user, "PROJECT_ARCHIVE", id);
    }

    private ProjectRow lockEditableProject(CurrentUser user, long id) {
        var rows = jdbc.query("SELECT p.* FROM " + projects + " p WHERE p.id = ? FOR UPDATE",
                (rs, row) -> new ProjectRow(rs.getLong("id"), rs.getString("name"), rs.getString("team_name"),
                        rs.getString("description"), rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class), rs.getString("status"), rs.getString("visibility"),
                        null, rs.getTimestamp("archived_at") != null, rs.getTimestamp("created_at").toInstant()), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "프로젝트를 찾을 수 없습니다.");
        boolean staff = user.role() == CurrentUser.Role.ADMIN || user.role() == CurrentUser.Role.SUPER_ADMIN;
        boolean leader = user.role() == CurrentUser.Role.STUDENT && !jdbc.query("SELECT role FROM " + members
                        + " WHERE project_id = ? AND user_id = ? AND role = 'LEADER' AND removed_at IS NULL FOR SHARE",
                (rs, row) -> rs.getString("role"), id, user.id()).isEmpty();
        if (!staff && !leader) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "접근 권한이 없습니다.");
        var project = rows.getFirst();
        if (project.archived()) throw new ApiException(HttpStatus.CONFLICT, "project_archived", "보관한 프로젝트는 수정할 수 없습니다.");
        return project;
    }

    private void auditOperator(CurrentUser user, String action, long id) {
        if (user.role() == CurrentUser.Role.ADMIN || user.role() == CurrentUser.Role.SUPER_ADMIN) {
            jdbc.update("INSERT INTO " + auditLogs + " (actor_user_id, action, target, at) VALUES (?, ?, ?, now())",
                    user.id(), action, "project:" + id);
        }
    }

    private static String textField(Map<String, JsonNode> fields, String key, String oldValue) {
        if (!fields.containsKey(key)) return oldValue;
        JsonNode value = fields.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalidRequest();
        return value.asString();
    }

    private static LocalDate dateField(Map<String, JsonNode> fields, String key, LocalDate oldValue) {
        if (!fields.containsKey(key)) return oldValue;
        String value = textField(fields, key, null);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalidRequest();
        }
    }

    private static ApiException invalidRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "프로젝트 정보를 확인하세요.");
    }

    private static void validate(ProjectController.CreateProject request) {
        if (request == null || request.name() == null || request.name().isBlank()
                || length(request.name()) > 100 || length(request.team_name()) > 50
                || length(request.description()) > 500
                || (request.starts_on() != null && request.ends_on() != null
                    && !request.ends_on().isAfter(request.starts_on()))
                || (request.visibility() != null && !List.of("TEAM", "STUDENTS").contains(request.visibility()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "프로젝트 정보를 확인하세요.");
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    record CreatedProject(long id, String status, Instant createdAt) {}
    record ProjectRow(long id, String name, String teamName, String description, LocalDate startsOn,
                      LocalDate endsOn, String status, String visibility, String myRole, boolean archived,
                      Instant createdAt) {}
    record Member(long user_id, String name, String login, Long github_id, String role) {}
    record Repo(long repo_id, String full_name, boolean is_private, boolean is_primary) {}
    record ProjectDetail(long id, String name, String team_name, String description, LocalDate starts_on,
                         LocalDate ends_on, String status, String visibility, String my_role, boolean archived,
                         boolean can_edit, Instant created_at, List<Member> members, List<Repo> repos) {}
}
