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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class ProjectRegistrationTests {
    private static final long GITHUB_ID = 987651234L;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private long userId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN "
                + "(SELECT id FROM capstone.projects WHERE created_by IN (SELECT id FROM public.users WHERE github_id = ?))", GITHUB_ID);
        jdbc.update("DELETE FROM capstone.projects WHERE created_by IN (SELECT id FROM public.users WHERE github_id = ?)", GITHUB_ID);
        jdbc.update("DELETE FROM public.users WHERE github_id = ?", GITHUB_ID);
        userId = jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, role) "
                + "VALUES (?, 'project-test', '학생', 'STUDENT') RETURNING id", Long.class, GITHUB_ID);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createsProjectAndLeaderWithDefaultsAndDetailedResponse() throws Exception {
        create(CurrentUser.Role.STUDENT, "{\"name\":\"프로젝트\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("프로젝트"))
                .andExpect(jsonPath("$.status").value("PLANNING"))
                .andExpect(jsonPath("$.visibility").value("TEAM"))
                .andExpect(jsonPath("$.my_role").value("LEADER"))
                .andExpect(jsonPath("$.archived").value(false)).andExpect(jsonPath("$.can_edit").value(true))
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.members[0].user_id").value(userId))
                .andExpect(jsonPath("$.members[0].name").value("학생"))
                .andExpect(jsonPath("$.members[0].login").value("project-test"))
                .andExpect(jsonPath("$.members[0].github_id").value(GITHUB_ID))
                .andExpect(jsonPath("$.members[0].role").value("LEADER"))
                .andExpect(jsonPath("$.members[0].student_id").doesNotExist())
                .andExpect(jsonPath("$.repos").isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.projects p JOIN capstone.project_members m "
                + "ON m.project_id = p.id WHERE p.created_by = ? AND m.user_id = ? AND m.role = 'LEADER'", Integer.class, userId, userId));
    }

    @Test
    void acceptsBoundaryLengthsOptionalFieldsAndPublicVisibility() throws Exception {
        create(CurrentUser.Role.STUDENT, "{\"name\":\"가\"}").andExpect(status().isCreated());
        create(CurrentUser.Role.STUDENT, "{\"name\":\"same day\",\"starts_on\":\"2026-01-01\",\"ends_on\":\"2026-01-01\"}")
                .andExpect(status().isCreated());
        String body = "{\"name\":\"" + "가".repeat(100) + "\",\"team_name\":\"" + "a".repeat(50)
                + "\",\"description\":\"" + "b".repeat(500) + "\",\"starts_on\":\"2026-01-01\","
                + "\"ends_on\":\"2026-01-02\",\"visibility\":\"STUDENTS\"}";
        create(CurrentUser.Role.STUDENT, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.visibility").value("STUDENTS"))
                .andExpect(jsonPath("$.starts_on").value("2026-01-01"))
                .andExpect(jsonPath("$.ends_on").value("2026-01-02"));
    }

    @Test
    void rejectsInvalidInputWithoutSavingAnything() throws Exception {
        for (String body : List.of("{}", "{\"name\":\" \"}", "{\"name\":\"" + "a".repeat(101) + "\"}",
                "{\"name\":\"ok\",\"team_name\":\"" + "a".repeat(51) + "\"}",
                "{\"name\":\"ok\",\"description\":\"" + "a".repeat(501) + "\"}",
                "{\"name\":\"ok\",\"starts_on\":\"2026-01-02\",\"ends_on\":\"2026-01-01\"}",
                "{\"name\":\"ok\",\"starts_on\":\"invalid\"}",
                "{\"name\":\"ok\",\"visibility\":\"PUBLIC\"}")) {
            create(CurrentUser.Role.STUDENT, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capstone.projects WHERE created_by = ?", Integer.class, userId));
    }

    @Test
    void onlyCompletedStudentsCanCreate() throws Exception {
        mvc.perform(post("/api/projects").with(csrf()).contentType("application/json").content("{\"name\":\"ok\"}"))
                .andExpect(status().isUnauthorized());
        create(CurrentUser.Role.GUEST, "{\"name\":\"ok\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
        for (var role : List.of(CurrentUser.Role.ADMIN, CurrentUser.Role.SUPER_ADMIN, CurrentUser.Role.PROFESSOR)) {
            create(role, "{\"name\":\"ok\"}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capstone.projects WHERE created_by = ?", Integer.class, userId));
    }

    @Test
    void rollsBackProjectWhenLeaderInsertionFails() throws Exception {
        jdbc.execute("CREATE FUNCTION capstone.test_reject_leader() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'test membership failure'; END $$");
        try {
            jdbc.execute("CREATE TRIGGER test_reject_leader BEFORE INSERT ON capstone.project_members "
                    + "FOR EACH ROW EXECUTE FUNCTION capstone.test_reject_leader()");
            create(CurrentUser.Role.STUDENT, "{\"name\":\"rollback test\"}").andExpect(status().isInternalServerError());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capstone.projects WHERE created_by = ?", Integer.class, userId));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_leader ON capstone.project_members");
            jdbc.execute("DROP FUNCTION capstone.test_reject_leader()");
        }
    }

    private ResultActions create(CurrentUser.Role role, String body) throws Exception {
        var principal = new CurrentUser(userId, role);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        var session = new MockHttpSession();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String response = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(response).get("csrf_token").asString();
        return mvc.perform(post("/api/projects").session(session).header("X-CSRF-Token", token)
                .contentType("application/json").content(body));
    }
}
