package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import com.djusw.api.security.CurrentUser;
import com.djusw.api.support.DatabaseTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class ProjectMemberChangeTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    private MockMvc mvc;
    private long leader, member, removed, outsider, project;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.activities WHERE repo_id = 987700001");
        jdbc.update("DELETE FROM capstone.project_repos WHERE github_repo_id = 987700001");
        jdbc.update("DELETE FROM public.repos WHERE repo_id = 987700001");
        jdbc.update("DELETE FROM public.installations WHERE installation_id = 987700001");
        jdbc.update("DELETE FROM capstone.audit_logs WHERE target LIKE 'project:%/member:%' AND actor_user_id IN (SELECT id FROM public.users WHERE github_id BETWEEN 987700001 AND 987700004)");
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN (SELECT id FROM capstone.projects WHERE name = 'member change test')");
        jdbc.update("DELETE FROM capstone.projects WHERE name = 'member change test'");
        jdbc.update("DELETE FROM public.users WHERE github_id BETWEEN 987700001 AND 987700004");
        leader = addUser(987700001L);
        member = addUser(987700002L);
        removed = addUser(987700003L);
        outsider = addUser(987700004L);
        project = jdbc.queryForObject("INSERT INTO capstone.projects (name, created_by) VALUES ('member change test', ?) RETURNING id", Long.class, leader);
        jdbc.update("INSERT INTO capstone.project_members (project_id, user_id, role, removed_at) VALUES "
                + "(?, ?, 'LEADER', NULL), (?, ?, 'MEMBER', NULL), (?, ?, 'LEADER', now())",
                project, leader, project, member, project, removed);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void managesRolesAndProtectsLastLeader() throws Exception {
        patch(leader, CurrentUser.Role.STUDENT, leader, "{\"role\":\"MEMBER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("last_leader"));
        remove(outsider, CurrentUser.Role.ADMIN, leader).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("last_leader"));
        patch(leader, CurrentUser.Role.STUDENT, leader, "{\"role\":\"LEADER\"}").andExpect(status().isOk());
        patch(leader, CurrentUser.Role.STUDENT, member, "{\"role\":\"LEADER\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.user_id").value(member)).andExpect(jsonPath("$.role").value("LEADER"));
        patch(outsider, CurrentUser.Role.ADMIN, leader, "{\"role\":\"MEMBER\"}").andExpect(status().isOk());
        assertEquals(1, activeLeaders());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.audit_logs WHERE actor_user_id = ? AND action = 'PROJECT_MEMBER_ROLE_UPDATE' AND target = ?", Integer.class, outsider, "project:" + project + "/member:" + leader));
        remove(outsider, CurrentUser.Role.SUPER_ADMIN, leader).andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.audit_logs WHERE actor_user_id = ? AND action = 'PROJECT_MEMBER_REMOVE' AND target = ?", Integer.class, outsider, "project:" + project + "/member:" + leader));
    }

    @Test
    void removalRetainsMembershipAndActivityAndRevokesPrivateAccess() throws Exception {
        jdbc.update("INSERT INTO public.installations (installation_id, account_login, account_id, account_type, repo_selection, status, installed_at) VALUES (987700001, 'test', 1, 'User', 'selected', 'ACTIVE', now())");
        jdbc.update("INSERT INTO public.repos (repo_id, installation_id, full_name, is_private, status) VALUES (987700001, 987700001, 'test/repo', true, 'LINKED')");
        jdbc.update("INSERT INTO capstone.project_repos (project_id, github_repo_id, linked_by) VALUES (?, 987700001, ?)", project, leader);
        jdbc.update("INSERT INTO public.activities (repo_id, kind, ref, actor_github_id, occurred_at) VALUES (987700001, 'ISSUE', '1', 987700002, now())");
        remove(leader, CurrentUser.Role.STUDENT, member).andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.project_members WHERE project_id = ? AND user_id = ? AND removed_at IS NOT NULL", Integer.class, project, member));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.activities WHERE repo_id = 987700001 AND actor_github_id = 987700002", Integer.class));
        var access = access(member, CurrentUser.Role.STUDENT);
        mvc.perform(get("/api/projects/{id}", project).session(access.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects/{id}/members", project).session(access.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects").param("q", "member change test").session(access.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        jdbc.update("UPDATE capstone.projects SET visibility = 'STUDENTS' WHERE id = ?", project);
        mvc.perform(get("/api/projects/{id}", project).session(access.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.my_role").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.can_edit").value(false));
        mvc.perform(get("/api/projects/{id}/members", project).session(access.session())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        patch(member, CurrentUser.Role.STUDENT, leader, "{\"role\":\"MEMBER\"}").andExpect(status().isForbidden());
        remove(member, CurrentUser.Role.STUDENT, leader).andExpect(status().isForbidden());
    }

    @Test
    void validatesPermissionsInputsAndArchivedProjects() throws Exception {
        for (long id : List.of(member, removed, outsider)) {
            patch(id, CurrentUser.Role.STUDENT, member, "{\"role\":\"LEADER\"}").andExpect(status().isForbidden());
            remove(id, CurrentUser.Role.STUDENT, member).andExpect(status().isForbidden());
        }
        patch(outsider, CurrentUser.Role.PROFESSOR, member, "{\"role\":\"LEADER\"}").andExpect(status().isForbidden());
        remove(outsider, CurrentUser.Role.PROFESSOR, member).andExpect(status().isForbidden());
        patch(outsider, CurrentUser.Role.GUEST, member, "{\"role\":\"LEADER\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("profile_required"));
        mvc.perform(delete("/api/projects/{id}/members/{user_id}", project, member)).andExpect(status().isUnauthorized());
        for (String body : List.of("null", "{}", "{\"role\":null}", "{\"role\":123}", "{\"role\":\"invalid\"}", "{\"role\":\"MEMBER\",\"extra\":true}"))
            patch(leader, CurrentUser.Role.STUDENT, member, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        patch(leader, CurrentUser.Role.STUDENT, removed, "{\"role\":\"MEMBER\"}").andExpect(status().isNotFound());
        remove(leader, CurrentUser.Role.STUDENT, outsider).andExpect(status().isNotFound());
        var access = access(leader, CurrentUser.Role.STUDENT);
        mvc.perform(delete("/api/projects/{id}/members/{user_id}", project, member).session(access.session())).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/{id}/members/{user_id}", project, member)
                .session(access.session()).contentType("application/json").content("{\"role\":\"LEADER\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(delete("/api/projects/{id}/members/{user_id}", Long.MAX_VALUE, member).session(access.session())
                .header("X-CSRF-Token", access.token())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
        jdbc.update("UPDATE capstone.projects SET archived_at = now() WHERE id = ?", project);
        patch(leader, CurrentUser.Role.STUDENT, member, "{\"role\":\"LEADER\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("project_archived"));
        remove(leader, CurrentUser.Role.STUDENT, member).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("project_archived"));
    }

    @Test
    void concurrentChangesCannotRemoveBothLeaders() throws Exception {
        jdbc.update("UPDATE capstone.project_members SET role = 'LEADER' WHERE project_id = ? AND user_id = ?", project, member);
        var admin = new CurrentUser(outsider, CurrentUser.Role.ADMIN);
        var body = Map.of("role", new ObjectMapper().readTree("\"MEMBER\""));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); try { projects.updateMemberRole(admin, project, leader, body); return "changed"; } catch (ApiException e) { return e.error(); } });
            var second = executor.submit(() -> { start.await(); try { projects.removeMember(admin, project, member); return "changed"; } catch (ApiException e) { return e.error(); } });
            start.countDown();
            assertEquals(Set.of("changed", "last_leader"), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            assertEquals(1, activeLeaders());
        }
    }

    private int activeLeaders() {
        return jdbc.queryForObject("SELECT count(*) FROM capstone.project_members WHERE project_id = ? AND role = 'LEADER' AND removed_at IS NULL", Integer.class, project);
    }

    private long addUser(long githubId) {
        return jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, role) VALUES (?, ?, '학생', 'STUDENT') RETURNING id", Long.class, githubId, "member-change-" + githubId);
    }

    private ResultActions patch(long actor, CurrentUser.Role role, long target, String body) throws Exception {
        var access = access(actor, role);
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/{id}/members/{user_id}", project, target)
                .session(access.session()).header("X-CSRF-Token", access.token()).contentType("application/json").content(body));
    }

    private ResultActions remove(long actor, CurrentUser.Role role, long target) throws Exception {
        var access = access(actor, role);
        return mvc.perform(delete("/api/projects/{id}/members/{user_id}", project, target).session(access.session()).header("X-CSRF-Token", access.token()));
    }

    private Access access(long id, CurrentUser.Role role) throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(id, role), null, List.of());
        var session = new MockHttpSession();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String response = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Access(session, new ObjectMapper().readTree(response).get("csrf_token").asString());
    }

    record Access(MockHttpSession session, String token) {}
}
