package com.djusw.api.project;

import com.djusw.api.security.CurrentUser;
import com.djusw.api.support.DatabaseTest;
import java.util.List;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class ProjectModificationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private long leader, member, removed, outsider, project;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM capstone.audit_logs WHERE target IN (SELECT 'project:' || id FROM capstone.projects WHERE name LIKE 'edit test%')");
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN (SELECT id FROM capstone.projects WHERE name LIKE 'edit test%')");
        jdbc.update("DELETE FROM capstone.projects WHERE name LIKE 'edit test%'");
        jdbc.update("DELETE FROM public.users WHERE github_id BETWEEN 987670001 AND 987670004");
        leader = addUser(987670001L);
        member = addUser(987670002L);
        removed = addUser(987670003L);
        outsider = addUser(987670004L);
        project = jdbc.queryForObject("INSERT INTO capstone.projects (name, created_by, team_name, starts_on, ends_on) "
                + "VALUES ('edit test', ?, 'team', '2026-01-01', '2026-02-01') RETURNING id", Long.class, leader);
        jdbc.update("INSERT INTO capstone.project_members (project_id, user_id, role, removed_at) VALUES "
                + "(?, ?, 'LEADER', NULL), (?, ?, 'MEMBER', NULL), (?, ?, 'LEADER', now())",
                project, leader, project, member, project, removed);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void leaderAndOperatorsCanUpdateFieldsAndClearOptionalValues() throws Exception {
        patch(leader, CurrentUser.Role.STUDENT, "{\"visibility\":\"STUDENTS\",\"starts_on\":\"2026-01-02\",\"ends_on\":\"2026-03-01\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("STUDENTS"))
                .andExpect(jsonPath("$.starts_on").value("2026-01-02")).andExpect(jsonPath("$.ends_on").value("2026-03-01"))
                .andExpect(jsonPath("$.team_name").value("team"));
        patch(outsider, CurrentUser.Role.ADMIN, "{\"visibility\":\"TEAM\",\"starts_on\":\"2026-01-03\",\"ends_on\":\"2026-04-01\",\"team_name\":null}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("TEAM"))
                .andExpect(jsonPath("$.starts_on").value("2026-01-03")).andExpect(jsonPath("$.ends_on").value("2026-04-01"))
                .andExpect(jsonPath("$.team_name").value(org.hamcrest.Matchers.nullValue()));
        patch(outsider, CurrentUser.Role.SUPER_ADMIN, "{\"ends_on\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ends_on").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(2, auditCount("PROJECT_UPDATE", outsider));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM capstone.audit_logs WHERE target = ? AND action = 'PROJECT_UPDATE'", Integer.class, "project:" + project));
    }

    @Test
    void validatesNewAndExistingValuesTogether() throws Exception {
        for (String body : List.of("{\"name\":null}", "{\"name\":\" \"}",
                "{\"name\":\"" + "a".repeat(101) + "\"}",
                "{\"team_name\":\"" + "a".repeat(51) + "\"}",
                "{\"description\":\"" + "a".repeat(501) + "\"}",
                "{\"starts_on\":\"2026-02-01\"}", "{\"ends_on\":\"2025-12-31\"}",
                "{\"ends_on\":\"invalid\"}", "{\"visibility\":null}", "{\"visibility\":\"bad\"}",
                "{\"name\":123}", "{\"status\":\"DONE\"}")) {
            patch(leader, CurrentUser.Role.STUDENT, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
        assertEquals("edit test", jdbc.queryForObject("SELECT name FROM capstone.projects WHERE id = ?", String.class, project));
        patch(leader, CurrentUser.Role.STUDENT, "{\"starts_on\":\"2026-04-01\",\"ends_on\":\"2026-04-02\"}")
                .andExpect(status().isOk());
    }

    @Test
    void deniesUnauthorizedUpdatesAndArchival() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/{id}", project)
                .contentType("application/json").content("{\"name\":\"denied\"}"))
                .andExpect(status().isUnauthorized());
        for (long id : List.of(member, removed, outsider)) {
            patch(id, CurrentUser.Role.STUDENT, "{\"name\":\"edit test denied\"}").andExpect(status().isForbidden());
            archive(id, CurrentUser.Role.STUDENT).andExpect(status().isForbidden());
        }
        patch(outsider, CurrentUser.Role.PROFESSOR, "{\"name\":\"edit test denied\"}").andExpect(status().isForbidden());
        archive(outsider, CurrentUser.Role.PROFESSOR).andExpect(status().isForbidden());
        jdbc.update("UPDATE capstone.projects SET visibility = 'STUDENTS' WHERE id = ?", project);
        var outsideAccess = access(outsider, CurrentUser.Role.STUDENT);
        mvc.perform(get("/api/projects/{id}", project).session(outsideAccess.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.can_edit").value(false));
        patch(outsider, CurrentUser.Role.STUDENT, "{\"name\":\"edit test denied\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertEquals("edit test", jdbc.queryForObject("SELECT name FROM capstone.projects WHERE id = ?", String.class, project));
        patch(outsider, CurrentUser.Role.GUEST, "{\"name\":\"edit test denied\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
        archive(outsider, CurrentUser.Role.GUEST).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capstone.projects WHERE id = ? AND archived_at IS NOT NULL", Integer.class, project));
    }

    @Test
    void archiveIsSoftAndBlocksFurtherWrites() throws Exception {
        jdbc.update("UPDATE capstone.projects SET visibility = 'STUDENTS' WHERE id = ?", project);
        var student = access(leader, CurrentUser.Role.STUDENT);
        mvc.perform(get("/api/projects").param("q", "edit test").session(student.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/projects/public").param("q", "edit test").session(student.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        archive(outsider, CurrentUser.Role.ADMIN).andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.projects WHERE id = ? AND archived_at IS NOT NULL", Integer.class, project));
        assertEquals(1, auditCount("PROJECT_ARCHIVE", outsider));
        patch(leader, CurrentUser.Role.STUDENT, "{\"name\":\"edit test changed\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("project_archived"));
        archive(leader, CurrentUser.Role.STUDENT).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("project_archived"));
        mvc.perform(get("/api/projects").param("q", "edit test").session(student.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/projects/public").param("q", "edit test").session(student.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/projects").param("q", "edit test").param("include_archived", "true").session(student.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void leaderCanArchive() throws Exception {
        archive(leader, CurrentUser.Role.STUDENT).andExpect(status().isNoContent());
        assertEquals(0, auditCount("PROJECT_ARCHIVE", leader));
    }

    private int auditCount(String action, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM capstone.audit_logs WHERE actor_user_id = ? AND action = ? AND target = ?",
                Integer.class, id, action, "project:" + project);
    }

    private long addUser(long githubId) {
        return jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, role) VALUES (?, ?, '학생', 'STUDENT') RETURNING id",
                Long.class, githubId, "test-" + githubId);
    }

    private ResultActions patch(long id, CurrentUser.Role role, String body) throws Exception {
        var access = access(id, role);
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/{id}", project).session(access.session())
                .header("X-CSRF-Token", access.token()).contentType("application/json").content(body));
    }

    private ResultActions archive(long id, CurrentUser.Role role) throws Exception {
        var access = access(id, role);
        return mvc.perform(post("/api/projects/{id}/archive", project).session(access.session()).header("X-CSRF-Token", access.token()));
    }

    private Access access(long id, CurrentUser.Role role) throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(id, role), null, List.of());
        var session = new MockHttpSession();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String response = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Access(session, new ObjectMapper().readTree(response).get("csrf_token").asString());
    }

    record Access(MockHttpSession session, String token) {}
}
