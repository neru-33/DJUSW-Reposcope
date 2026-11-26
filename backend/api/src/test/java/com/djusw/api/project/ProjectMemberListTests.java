package com.djusw.api.project;

import com.djusw.api.security.CurrentUser;
import com.djusw.api.support.DatabaseTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class ProjectMemberListTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private long leader, member, removed, outsider, project;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN "
                + "(SELECT id FROM capstone.projects WHERE name = 'member list test')");
        jdbc.update("DELETE FROM capstone.projects WHERE name = 'member list test'");
        jdbc.update("DELETE FROM public.users WHERE github_id BETWEEN 987680001 AND 987680004");
        leader = addUser(987680001L, "leader-test", "10000001");
        member = addUser(987680002L, "member-test", "10000002");
        removed = addUser(987680003L, "removed-test", "10000003");
        outsider = addUser(987680004L, "outsider-test", "10000004");
        project = jdbc.queryForObject("INSERT INTO capstone.projects (name, created_by, visibility) "
                + "VALUES ('member list test', ?, 'STUDENTS') RETURNING id", Long.class, leader);
        jdbc.update("INSERT INTO capstone.project_members (project_id, user_id, role, joined_at, removed_at) VALUES "
                + "(?, ?, 'LEADER', '2026-01-01T00:00:00Z', NULL), "
                + "(?, ?, 'MEMBER', '2026-01-02T00:00:00Z', NULL), "
                + "(?, ?, 'MEMBER', now(), now())", project, leader, project, member, project, removed);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void membersAndStaffSeeActiveMembersWithProfileAndPagination() throws Exception {
        for (var role : List.of(CurrentUser.Role.PROFESSOR, CurrentUser.Role.ADMIN, CurrentUser.Role.SUPER_ADMIN))
            list(outsider, role, project).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        for (long id : List.of(leader, member))
            list(id, CurrentUser.Role.STUDENT, project).andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(50))
                    .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].user_id").value(leader))
                    .andExpect(jsonPath("$.items[0].name").value("학생"))
                    .andExpect(jsonPath("$.items[0].student_id").value("10000001"))
                    .andExpect(jsonPath("$.items[0].login").value("leader-test"))
                    .andExpect(jsonPath("$.items[0].role").value("LEADER"))
                    .andExpect(jsonPath("$.items[0].joined_at").value("2026-01-01T00:00:00Z"))
                    .andExpect(jsonPath("$.items[1].user_id").value(member));
        var auth = authentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentUser(leader, CurrentUser.Role.STUDENT), null, List.of()));
        mvc.perform(get("/api/projects/{id}/members", project).param("page", "2").param("size", "1").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].user_id").value(member));
        mvc.perform(get("/api/projects/{id}/members", project).param("size", "51").with(auth))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void rejectsNonMembersEvenForPublicProjectsAndRequiresAuthentication() throws Exception {
        for (long id : List.of(removed, outsider))
            list(id, CurrentUser.Role.STUDENT, project).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        list(outsider, CurrentUser.Role.GUEST, project).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
        mvc.perform(get("/api/projects/{id}/members", project)).andExpect(status().isUnauthorized());
        list(leader, CurrentUser.Role.STUDENT, Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    private long addUser(long githubId, String login, String studentId) {
        return jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, student_id, role) "
                + "VALUES (?, ?, '학생', ?, 'STUDENT') RETURNING id", Long.class, githubId, login, studentId);
    }

    private ResultActions list(long userId, CurrentUser.Role role, long projectId) throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(userId, role), null, List.of());
        return mvc.perform(get("/api/projects/{id}/members", projectId).with(authentication(auth)));
    }
}
