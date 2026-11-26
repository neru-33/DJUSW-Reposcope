package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import com.djusw.api.security.CurrentUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectListService {
    private final JdbcTemplate jdbc;
    private final String projects, members, users, projectRepos, activities;

    ProjectListService(JdbcTemplate jdbc, @Value("${spring.flyway.default-schema}") String schema,
                       @Value("${app.ingest-schema}") String ingestSchema) {
        if (ingestSchema.isBlank()) ingestSchema = "public";
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !ingestSchema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid database schema");
        this.jdbc = jdbc;
        projects = "\"" + schema + "\".projects";
        members = "\"" + schema + "\".project_members";
        users = "\"" + ingestSchema + "\".users";
        projectRepos = "\"" + schema + "\".project_repos";
        activities = "\"" + ingestSchema + "\".activities";
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(CurrentUser user, boolean publicList, String q, String status, boolean includeArchived,
                     String sort, int page, int size) {
        if (page < 1 || size < 1 || size > 50
                || (status != null && !Set.of("PLANNING", "DEVELOPMENT", "VERIFICATION", "DONE").contains(status))
                || (publicList && !Set.of("recent_activity", "name", "created").contains(sort))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "조회 조건을 확인하세요.");
        }
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (publicList) where.append(" AND p.visibility = 'STUDENTS' AND p.archived_at IS NULL");
        else {
            if (!includeArchived) where.append(" AND p.archived_at IS NULL");
            if (user.role() == CurrentUser.Role.STUDENT) {
                where.append(" AND EXISTS (SELECT 1 FROM ").append(members)
                        .append(" own WHERE own.project_id = p.id AND own.user_id = ? AND own.removed_at IS NULL)");
                args.add(user.id());
            }
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (p.name ILIKE ? ESCAPE '!' OR p.team_name ILIKE ? ESCAPE '!' OR p.description ILIKE ? ESCAPE '!')");
            String pattern = "%" + q.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(pattern); args.add(pattern); args.add(pattern);
        }
        if (status != null) { where.append(" AND p.status = ?"); args.add(status); }
        long total = jdbc.queryForObject("SELECT count(*) FROM " + projects + " p" + where, Long.class, args.toArray());
        String order = !publicList || "created".equals(sort) ? "p.created_at DESC, p.id DESC"
                : "name".equals(sort) ? "p.name ASC, p.id ASC" : "last_activity_at DESC NULLS LAST, p.id ASC";
        String sql = "SELECT p.id, p.name, p.team_name, p.description, p.status, p.visibility, p.archived_at, "
                + "own.role AS my_role, (SELECT u.name FROM " + members + " lm JOIN " + users
                + " u ON u.id = lm.user_id WHERE lm.project_id = p.id AND lm.role = 'LEADER' AND lm.removed_at IS NULL "
                + "ORDER BY u.name, lm.user_id LIMIT 1) AS leader_name, "
                + "(SELECT count(*) FROM " + members + " cm WHERE cm.project_id = p.id AND cm.removed_at IS NULL) AS member_count, "
                + "(SELECT max(a.occurred_at) FROM " + projectRepos + " pr JOIN " + activities
                + " a ON a.repo_id = pr.github_repo_id WHERE pr.project_id = p.id "
                + "AND (pr.unlinked_at IS NULL OR a.occurred_at <= pr.unlinked_at)) AS last_activity_at "
                + "FROM " + projects + " p LEFT JOIN " + members
                + " own ON own.project_id = p.id AND own.user_id = ? AND own.removed_at IS NULL"
                + where + " ORDER BY " + order + " LIMIT ? OFFSET ?";
        List<Object> queryArgs = new ArrayList<>();
        queryArgs.add(user.id()); queryArgs.addAll(args); queryArgs.add(size); queryArgs.add((long) (page - 1) * size);
        var rows = jdbc.query(sql, (rs, n) -> new Row(rs.getLong("id"), rs.getString("name"), rs.getString("team_name"),
                rs.getString("description"), rs.getString("status"), rs.getString("visibility"), rs.getString("my_role"),
                rs.getString("leader_name"), rs.getLong("member_count"),
                rs.getTimestamp("last_activity_at") == null ? null : rs.getTimestamp("last_activity_at").toInstant(),
                rs.getTimestamp("archived_at") != null), queryArgs.toArray());
        List<?> items = publicList ? rows.stream().map(r -> new PublicItem(r.id(), r.name(), r.teamName(), r.description(),
                r.status(), r.visibility(), r.myRole() != null, r.leaderName(), r.memberCount(), r.lastActivityAt(), List.of(), r.archived())).toList()
                : rows.stream().map(r -> new Item(r.id(), r.name(), r.teamName(), r.description(), r.status(), r.visibility(),
                user.role() == CurrentUser.Role.STUDENT ? r.myRole() : null, r.leaderName(), r.memberCount(),
                r.lastActivityAt(), List.of(), r.archived())).toList();
        return new Page(items, page, size, total);
    }

    record Row(long id, String name, String teamName, String description, String status, String visibility,
               String myRole, String leaderName, long memberCount, Instant lastActivityAt, boolean archived) {}
    record Item(long id, String name, String team_name, String description, String status, String visibility,
                String my_role, String leader_name, long member_count, Instant last_activity_at,
                List<Integer> weekly_activity, boolean archived) {}
    record PublicItem(long id, String name, String team_name, String description, String status, String visibility,
                      boolean is_member, String leader_name, long member_count, Instant last_activity_at,
                      List<Integer> weekly_activity, boolean archived) {}
    record Page(List<?> items, int page, int size, long total) {}
}
