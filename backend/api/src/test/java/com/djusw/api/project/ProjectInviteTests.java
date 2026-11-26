package com.djusw.api.project;

import com.djusw.api.security.CurrentUser;
import com.djusw.api.error.ApiException;
import com.djusw.api.support.DatabaseTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class ProjectInviteTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    private MockMvc mvc;
    private long leader, member, removed, outsider, project;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM capstone.audit_logs WHERE target IN (SELECT 'project:' || id FROM capstone.projects WHERE name = 'invite test')");
        jdbc.update("DELETE FROM capstone.project_invites WHERE project_id IN (SELECT id FROM capstone.projects WHERE name = 'invite test')");
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN (SELECT id FROM capstone.projects WHERE name = 'invite test')");
        jdbc.update("DELETE FROM capstone.projects WHERE name = 'invite test'");
        jdbc.update("DELETE FROM public.users WHERE github_id BETWEEN 987690001 AND 987690004");
        leader = addUser(987690001L);
        member = addUser(987690002L);
        removed = addUser(987690003L);
        outsider = addUser(987690004L);
        project = jdbc.queryForObject("INSERT INTO capstone.projects (name, created_by) VALUES ('invite test', ?) RETURNING id", Long.class, leader);
        jdbc.update("INSERT INTO capstone.project_members (project_id, user_id, role, removed_at) VALUES "
                + "(?, ?, 'LEADER', NULL), (?, ?, 'MEMBER', NULL), (?, ?, 'LEADER', now())",
                project, leader, project, member, project, removed);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void issuesAndReissuesHashedCodesWithSevenDayLifetime() throws Exception {
        var firstResponse = issue(leader, CurrentUser.Role.STUDENT).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString()).andExpect(jsonPath("$.expires_at").isString())
                .andReturn().getResponse().getContentAsString();
        var first = new ObjectMapper().readTree(firstResponse);
        String firstCode = first.get("code").asString();
        var row = jdbc.queryForMap("SELECT code_hash, created_at, expires_at FROM capstone.project_invites WHERE project_id = ?", project);
        assertNotEquals(firstCode, row.get("code_hash"));
        assertEquals(InviteCode.hash(firstCode), row.get("code_hash"));
        var created = ((java.sql.Timestamp) row.get("created_at")).toInstant();
        var expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        assertEquals(Duration.ofDays(7), Duration.between(created, expires));
        assertEquals(expires, java.time.Instant.parse(first.get("expires_at").asString()));

        String secondResponse = issue(outsider, CurrentUser.Role.ADMIN).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondCode = new ObjectMapper().readTree(secondResponse).get("code").asString();
        assertNotEquals(firstCode, secondCode);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.project_invites WHERE project_id = ? AND code_hash = ? AND revoked_at IS NOT NULL", Integer.class, project, InviteCode.hash(firstCode)));
        assertEquals(1, activeCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.audit_logs WHERE actor_user_id = ? AND action = 'PROJECT_INVITE_CREATE' AND target = ?", Integer.class, outsider, "project:" + project));
        issue(outsider, CurrentUser.Role.SUPER_ADMIN).andExpect(status().isCreated());
    }

    @Test
    void enforcesAuthenticationPermissionsAndArchivedState() throws Exception {
        for (long id : List.of(member, removed, outsider))
            issue(id, CurrentUser.Role.STUDENT).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden"));
        issue(outsider, CurrentUser.Role.PROFESSOR).andExpect(status().isForbidden());
        issue(outsider, CurrentUser.Role.GUEST).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("profile_required"));
        mvc.perform(post("/api/projects/{id}/invites", project)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE capstone.projects SET archived_at = now() WHERE id = ?", project);
        issue(leader, CurrentUser.Role.STUDENT).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("project_archived"));
        assertEquals(0, activeCount());
    }

    @Test
    void concurrentIssuanceLeavesOneActiveCode() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return projects.issueInvite(new CurrentUser(leader, CurrentUser.Role.STUDENT), project); });
            var second = executor.submit(() -> { start.await(); return projects.issueInvite(new CurrentUser(leader, CurrentUser.Role.STUDENT), project); });
            start.countDown();
            var one = first.get(10, TimeUnit.SECONDS);
            var two = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(one.code(), two.code());
            assertEquals(1, activeCount());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM capstone.project_invites WHERE project_id = ?", Integer.class, project));
        }
    }

    @Test
    void failedReissueRollsBackRevocation() {
        var user = new CurrentUser(leader, CurrentUser.Role.STUDENT);
        var original = projects.issueInvite(user, project);
        jdbc.execute("CREATE FUNCTION capstone.test_reject_invite() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'test rejection'; END $$");
        jdbc.execute("CREATE TRIGGER test_reject_invite BEFORE INSERT ON capstone.project_invites "
                + "FOR EACH ROW EXECUTE FUNCTION capstone.test_reject_invite()");
        try {
            assertThrows(RuntimeException.class, () -> projects.issueInvite(user, project));
            assertEquals(1, activeCount());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.project_invites "
                    + "WHERE project_id = ? AND code_hash = ? AND revoked_at IS NULL", Integer.class,
                    project, InviteCode.hash(original.code())));
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_invite ON capstone.project_invites");
            jdbc.execute("DROP FUNCTION capstone.test_reject_invite()");
        }
    }

    @Test
    void acceptsValidCodeAndReactivatesRemovedMember() throws Exception {
        String code = projects.issueInvite(new CurrentUser(leader, CurrentUser.Role.STUDENT), project).code();
        accept(outsider, CurrentUser.Role.STUDENT, "{\"code\":\"" + code + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.project_id").value(project));
        assertEquals("MEMBER", jdbc.queryForObject("SELECT role FROM capstone.project_members WHERE project_id = ? AND user_id = ? AND removed_at IS NULL", String.class, project, outsider));
        accept(outsider, CurrentUser.Role.STUDENT, "{\"code\":\"" + code + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("already_member"));
        jdbc.update("UPDATE capstone.project_members SET joined_at = '2020-01-01T00:00:00Z' WHERE project_id = ? AND user_id = ?", project, removed);
        accept(removed, CurrentUser.Role.STUDENT, "{\"code\":\"" + code + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.project_id").value(project));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.project_members WHERE project_id = ? AND user_id = ? AND role = 'MEMBER' AND removed_at IS NULL AND joined_at > '2020-01-01T00:00:00Z'", Integer.class, project, removed));
    }

    @Test
    void rejectsExpiredRevokedUnknownAndArchivedInvitations() throws Exception {
        var leaderUser = new CurrentUser(leader, CurrentUser.Role.STUDENT);
        String old = projects.issueInvite(leaderUser, project).code();
        String current = projects.issueInvite(leaderUser, project).code();
        for (String code : List.of(old, "unknown", ""))
            accept(outsider, CurrentUser.Role.STUDENT, "{\"code\":\"" + code + "\"}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("invalid_invite"));
        jdbc.update("UPDATE capstone.project_invites SET expires_at = clock_timestamp() - interval '1 second' WHERE code_hash = ?", InviteCode.hash(current));
        accept(outsider, CurrentUser.Role.STUDENT, "{\"code\":\"" + current + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("invalid_invite"));
        String fresh = projects.issueInvite(leaderUser, project).code();
        jdbc.update("UPDATE capstone.projects SET archived_at = now() WHERE id = ?", project);
        accept(outsider, CurrentUser.Role.STUDENT, "{\"code\":\"" + fresh + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("project_archived"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capstone.project_members WHERE project_id = ? AND user_id = ?", Integer.class, project, outsider));
    }

    @Test
    void acceptsBeforeExpiryAndRejectsAtOrAfterExpiry() throws Exception {
        String code = projects.issueInvite(new CurrentUser(leader, CurrentUser.Role.STUDENT), project).code();
        String body = "{\"code\":\"" + code + "\"}";
        jdbc.update("UPDATE capstone.project_invites SET expires_at = clock_timestamp() + interval '1 minute' WHERE code_hash = ?", InviteCode.hash(code));
        accept(outsider, CurrentUser.Role.STUDENT, body).andExpect(status().isOk());
        jdbc.update("UPDATE capstone.project_invites SET expires_at = clock_timestamp() WHERE code_hash = ?", InviteCode.hash(code));
        accept(removed, CurrentUser.Role.STUDENT, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_invite"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.project_members WHERE project_id = ? AND user_id = ? AND removed_at IS NOT NULL", Integer.class, project, removed));
    }

    @Test
    void acceptanceRequiresCompletedStudentProfileAndCsrf() throws Exception {
        String code = projects.issueInvite(new CurrentUser(leader, CurrentUser.Role.STUDENT), project).code();
        String body = "{\"code\":\"" + code + "\"}";
        accept(outsider, CurrentUser.Role.GUEST, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("profile_required"));
        for (var role : List.of(CurrentUser.Role.PROFESSOR, CurrentUser.Role.ADMIN, CurrentUser.Role.SUPER_ADMIN))
            accept(outsider, role, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden"));
        accept(outsider, CurrentUser.Role.STUDENT, "{\"code\":123}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        accept(outsider, CurrentUser.Role.STUDENT, "null").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/api/invites/accept").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        var access = access(outsider, CurrentUser.Role.STUDENT);
        mvc.perform(post("/api/invites/accept").session(access.session()).contentType("application/json").content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
    }

    @Test
    void concurrentAcceptanceBySameStudentOnlyJoinsOnce() throws Exception {
        String code = projects.issueInvite(new CurrentUser(leader, CurrentUser.Role.STUDENT), project).code();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                try {
                    projects.acceptInvite(new CurrentUser(outsider, CurrentUser.Role.STUDENT), code);
                    return "joined";
                } catch (ApiException exception) {
                    return exception.error();
                }
            })).toList();
            start.countDown();
            assertEquals(java.util.Set.of("joined", "already_member"),
                    java.util.Set.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS)));
        }
    }

    private int activeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM capstone.project_invites WHERE project_id = ? AND revoked_at IS NULL", Integer.class, project);
    }

    private long addUser(long githubId) {
        return jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, role) VALUES (?, ?, '학생', 'STUDENT') RETURNING id", Long.class, githubId, "invite-" + githubId);
    }

    private ResultActions issue(long id, CurrentUser.Role role) throws Exception {
        var access = access(id, role);
        return mvc.perform(post("/api/projects/{id}/invites", project).session(access.session()).header("X-CSRF-Token", access.token()));
    }

    private ResultActions accept(long id, CurrentUser.Role role, String body) throws Exception {
        var access = access(id, role);
        return mvc.perform(post("/api/invites/accept").session(access.session()).header("X-CSRF-Token", access.token())
                .contentType("application/json").content(body));
    }

    private Access access(long id, CurrentUser.Role role) throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(id, role), null, List.of());
        var session = new MockHttpSession();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String response = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(response).get("csrf_token").asString();
        return new Access(session, token);
    }

    record Access(MockHttpSession session, String token) {}
}
