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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class DeliverableApiTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    long project, otherProject, leader, author, member, removed, outsider, operator;
    static final String VALID = "{\"kind\":\"SLIDES\",\"title\":\"발표자료\",\"url\":\"https://example.com/slides\"}";

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM capstone.audit_logs WHERE target IN (SELECT 'deliverable:' || id FROM capstone.deliverables WHERE project_id IN (SELECT id FROM capstone.projects WHERE name LIKE 'deliverable-test%'))");
        jdbc.update("DELETE FROM capstone.deliverables WHERE project_id IN (SELECT id FROM capstone.projects WHERE name LIKE 'deliverable-test%')");
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN (SELECT id FROM capstone.projects WHERE name LIKE 'deliverable-test%')");
        jdbc.update("DELETE FROM capstone.projects WHERE name LIKE 'deliverable-test%'");
        jdbc.update("DELETE FROM public.users WHERE github_id BETWEEN 987680001 AND 987680006");
        leader = user(987680001); author = user(987680002); member = user(987680003);
        removed = user(987680004); outsider = user(987680005); operator = user(987680006);
        project = jdbc.queryForObject("INSERT INTO capstone.projects (name, created_by, visibility) VALUES ('deliverable-test', ?, 'STUDENTS') RETURNING id", Long.class, leader);
        otherProject = jdbc.queryForObject("INSERT INTO capstone.projects (name, created_by) VALUES ('deliverable-test-other', ?) RETURNING id", Long.class, leader);
        jdbc.update("INSERT INTO capstone.project_members (project_id, user_id, role, removed_at) VALUES (?, ?, 'LEADER', NULL), (?, ?, 'MEMBER', NULL), (?, ?, 'MEMBER', NULL), (?, ?, 'MEMBER', now()), (?, ?, 'LEADER', NULL)",
                project, leader, project, author, project, member, project, removed, otherProject, leader);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createListUpdateDeleteAndIdentity() throws Exception {
        call(post(path(project)), author, CurrentUser.Role.STUDENT, VALID).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SLIDES")).andExpect(jsonPath("$.created_by_name").value("학생"))
                .andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.title").value("발표자료"))
                .andExpect(jsonPath("$.url").value("https://example.com/slides"))
                .andExpect(jsonPath("$.created_at").isString()).andExpect(jsonPath("$.created_by").doesNotExist());
        long id = jdbc.queryForObject("SELECT id FROM capstone.deliverables WHERE project_id = ?", Long.class, project);
        assertEquals(author, jdbc.queryForObject("SELECT created_by FROM capstone.deliverables WHERE id = ?", Long.class, id));
        call(get(path(project)), member, CurrentUser.Role.STUDENT, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].kind").value("SLIDES"))
                .andExpect(jsonPath("$.items[0].title").value("발표자료"))
                .andExpect(jsonPath("$.items[0].url").value("https://example.com/slides"))
                .andExpect(jsonPath("$.items[0].created_by_name").value("학생"))
                .andExpect(jsonPath("$.items[0].created_at").isString());
        call(patch(path(project) + "/" + id), author, CurrentUser.Role.STUDENT, "{\"title\":\"새 제목\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("새 제목"));
        call(delete(path(project) + "/" + id), author, CurrentUser.Role.STUDENT, null).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capstone.deliverables WHERE id = ?", Integer.class, id));
    }

    @Test
    void validatesBothWritesAndRejectsImpersonation() throws Exception {
        for (String body : List.of("{}", "{\"kind\":\"WRONG\",\"title\":\"a\",\"url\":\"https://example.com\"}",
                "{\"kind\":\"OTHER\",\"title\":\"a\",\"url\":\"http://example.com\"}",
                "{\"kind\":\"OTHER\",\"title\":\"a\",\"url\":\"https://example.com\",\"created_by\":1}"))
            call(post(path(project)), author, CurrentUser.Role.STUDENT, body).andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capstone.deliverables WHERE project_id = ?", Integer.class, project));
        long id = createRow();
        for (String body : List.of("{\"url\":\"http://example.com\"}", "{\"title\":null}", "{\"kind\":\"WRONG\"}", "{\"created_by\":1}"))
            call(patch(path(project) + "/" + id), author, CurrentUser.Role.STUDENT, body).andExpect(status().isBadRequest());
        assertEquals("https://example.com", jdbc.queryForObject("SELECT url FROM capstone.deliverables WHERE id = ?", String.class, id));
    }

    @Test
    void rejectsInvalidUrlsOnBothCreateAndUpdateWithoutSaving() throws Exception {
        long id = createRow();
        for (String url : List.of("http://example.com", "", "https://", "https://example.com bad")) {
            String create = "{\"kind\":\"DOCUMENT\",\"title\":\"문서\",\"url\":\"" + url + "\"}";
            String update = "{\"url\":\"" + url + "\"}";
            call(post(path(project)), author, CurrentUser.Role.STUDENT, create).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
            call(patch(path(project) + "/" + id), author, CurrentUser.Role.STUDENT, update).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.deliverables WHERE project_id = ?", Integer.class, project));
        assertEquals("https://example.com", jdbc.queryForObject("SELECT url FROM capstone.deliverables WHERE id = ?", String.class, id));
    }

    @Test
    void checksProjectMembershipRolesAndProjectOwnership() throws Exception {
        long id = createRow();
        for (long user : List.of(outsider, removed)) {
            call(get(path(project)), user, CurrentUser.Role.STUDENT, null).andExpect(status().isForbidden());
            call(post(path(project)), user, CurrentUser.Role.STUDENT, VALID).andExpect(status().isForbidden());
            call(patch(path(project) + "/" + id), user, CurrentUser.Role.STUDENT, "{\"title\":\"x\"}").andExpect(status().isForbidden());
        }
        call(get(path(project)), outsider, CurrentUser.Role.PROFESSOR, null).andExpect(status().isOk());
        call(post(path(project)), outsider, CurrentUser.Role.PROFESSOR, VALID).andExpect(status().isForbidden());
        call(patch(path(project) + "/" + id), outsider, CurrentUser.Role.PROFESSOR, "{\"title\":\"x\"}").andExpect(status().isForbidden());
        call(delete(path(project) + "/" + id), outsider, CurrentUser.Role.PROFESSOR, null).andExpect(status().isForbidden());
        call(patch(path(project) + "/" + id), member, CurrentUser.Role.STUDENT, "{\"title\":\"x\"}").andExpect(status().isForbidden());
        call(delete(path(project) + "/" + id), member, CurrentUser.Role.STUDENT, null).andExpect(status().isForbidden());
        call(patch(path(otherProject) + "/" + id), leader, CurrentUser.Role.STUDENT, "{\"title\":\"x\"}").andExpect(status().isNotFound());
        call(delete(path(otherProject) + "/" + id), leader, CurrentUser.Role.STUDENT, null).andExpect(status().isNotFound());
        call(patch(path(project) + "/0"), leader, CurrentUser.Role.STUDENT, "{\"title\":\"x\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
        call(delete(path(project) + "/0"), leader, CurrentUser.Role.STUDENT, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
        call(patch(path(project) + "/" + id), leader, CurrentUser.Role.STUDENT, "{\"title\":\"leader\"}").andExpect(status().isOk());
        call(patch(path(project) + "/" + id), operator, CurrentUser.Role.ADMIN, "{\"title\":\"admin\"}").andExpect(status().isOk());
        call(delete(path(project) + "/" + id), operator, CurrentUser.Role.ADMIN, null).andExpect(status().isNoContent());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM capstone.audit_logs WHERE actor_user_id = ? AND target = ?", Integer.class, operator, "deliverable:" + id));
        call(post(path(project)), operator, CurrentUser.Role.ADMIN, VALID).andExpect(status().isCreated());
        long operatorId = jdbc.queryForObject("SELECT id FROM capstone.deliverables WHERE project_id = ?", Long.class, project);
        call(delete(path(project) + "/" + operatorId), leader, CurrentUser.Role.STUDENT, null).andExpect(status().isNoContent());
    }

    @Test
    void archivedProjectIsReadOnly() throws Exception {
        long id = createRow();
        jdbc.update("UPDATE capstone.projects SET archived_at = now() WHERE id = ?", project);
        call(get(path(project)), author, CurrentUser.Role.STUDENT, null).andExpect(status().isOk());
        call(post(path(project)), author, CurrentUser.Role.STUDENT, VALID).andExpect(status().isConflict());
        call(patch(path(project) + "/" + id), author, CurrentUser.Role.STUDENT, "{\"title\":\"x\"}").andExpect(status().isConflict());
        call(delete(path(project) + "/" + id), leader, CurrentUser.Role.STUDENT, null).andExpect(status().isConflict());
        call(post(path(project)), operator, CurrentUser.Role.ADMIN, VALID).andExpect(status().isConflict());
        call(patch(path(project) + "/" + id), operator, CurrentUser.Role.ADMIN, "{\"title\":\"x\"}").andExpect(status().isConflict());
        call(delete(path(project) + "/" + id), operator, CurrentUser.Role.ADMIN, null).andExpect(status().isConflict());
    }

    @Test
    void excludedAuthorCannotChangeTheirOwnDeliverable() throws Exception {
        long id = createRow();
        jdbc.update("UPDATE capstone.project_members SET removed_at = now() WHERE project_id = ? AND user_id = ?", project, author);
        call(get(path(project)), author, CurrentUser.Role.STUDENT, null).andExpect(status().isForbidden());
        call(patch(path(project) + "/" + id), author, CurrentUser.Role.STUDENT, "{\"title\":\"x\"}").andExpect(status().isForbidden());
        call(delete(path(project) + "/" + id), author, CurrentUser.Role.STUDENT, null).andExpect(status().isForbidden());
        assertEquals("title", jdbc.queryForObject("SELECT title FROM capstone.deliverables WHERE id = ?", String.class, id));
    }

    private long user(long githubId) {
        return jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, role) VALUES (?, ?, '학생', 'STUDENT') RETURNING id", Long.class, githubId, "deliverable-" + githubId);
    }
    private long createRow() {
        return jdbc.queryForObject("INSERT INTO capstone.deliverables (project_id, kind, title, url, created_by) VALUES (?, 'OTHER', 'title', 'https://example.com', ?) RETURNING id", Long.class, project, author);
    }
    private String path(long id) { return "/api/projects/" + id + "/deliverables"; }
    private ResultActions call(MockHttpServletRequestBuilder request, long id, CurrentUser.Role role, String body) throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(id, role), null, List.of());
        var session = new MockHttpSession();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String response = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(response).get("csrf_token").asString();
        request.session(session).header("X-CSRF-Token", token);
        if (body != null) request.contentType("application/json").content(body);
        return mvc.perform(request);
    }
}
