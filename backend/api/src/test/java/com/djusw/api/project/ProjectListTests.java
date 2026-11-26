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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class ProjectListTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private long student, other, alpha, beta, gamma, archived, privateProject;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.activities WHERE repo_id BETWEEN 987680001 AND 987680003");
        jdbc.update("DELETE FROM capstone.project_repos WHERE github_repo_id BETWEEN 987680001 AND 987680003");
        jdbc.update("DELETE FROM public.repos WHERE repo_id BETWEEN 987680001 AND 987680003");
        jdbc.update("DELETE FROM public.installations WHERE installation_id = 987680001");
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN (SELECT id FROM capstone.projects WHERE name LIKE 'ListTest %')");
        jdbc.update("DELETE FROM capstone.projects WHERE name LIKE 'ListTest %'");
        jdbc.update("DELETE FROM public.users WHERE github_id BETWEEN 987680001 AND 987680002");
        student = addUser(987680001L, "Leader");
        other = addUser(987680002L, "Other");
        alpha = addProject("ListTest Alpha", "alpha-team", "red bird", "STUDENTS", "PLANNING", false, "2026-01-01");
        beta = addProject("ListTest Beta", "beta-team", "blue fish", "STUDENTS", "DEVELOPMENT", false, "2026-02-01");
        gamma = addProject("ListTest Gamma", "gamma-team", "green_100%", "STUDENTS", "DONE", false, "2026-03-01");
        archived = addProject("ListTest Archived", "archive", "old", "STUDENTS", "PLANNING", true, "2026-04-01");
        privateProject = addProject("ListTest Private", "private", "secret", "TEAM", "PLANNING", false, "2026-05-01");
        jdbc.update("INSERT INTO capstone.project_members (project_id, user_id, role, removed_at) VALUES "
                + "(?, ?, 'LEADER', NULL), (?, ?, 'MEMBER', NULL), (?, ?, 'LEADER', NULL), "
                + "(?, ?, 'LEADER', NULL), (?, ?, 'LEADER', NULL), (?, ?, 'LEADER', NULL), (?, ?, 'MEMBER', now())",
                alpha, student, alpha, other, beta, other, gamma, other, archived, student, privateProject, student, gamma, student);
        jdbc.update("INSERT INTO public.installations (installation_id, account_login, account_id, account_type, repo_selection, status, installed_at) "
                + "VALUES (987680001, 'list', 1, 'User', 'selected', 'ACTIVE', now())");
        jdbc.update("INSERT INTO public.repos (repo_id, installation_id, full_name, is_private, status) VALUES "
                + "(987680001, 987680001, 'list/a', false, 'LINKED'), (987680002, 987680001, 'list/b', false, 'LINKED'), "
                + "(987680003, 987680001, 'list/c', false, 'LINKED')");
        jdbc.update("INSERT INTO capstone.project_repos (project_id, github_repo_id, linked_by, unlinked_at) VALUES "
                + "(?, 987680001, ?, NULL), (?, 987680002, ?, NULL), (?, 987680003, ?, '2026-02-01')",
                alpha, student, beta, other, gamma, other);
        jdbc.update("INSERT INTO public.activities (repo_id, kind, ref, occurred_at) VALUES "
                + "(987680001, 'COMMIT', 'a', '2026-03-01T00:00:00Z'), (987680002, 'COMMIT', 'b', '2026-04-01T00:00:00Z'), "
                + "(987680003, 'COMMIT', 'c', '2026-01-01T00:00:00Z'), (987680003, 'COMMIT', 'd', '2026-05-01T00:00:00Z')");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void studentOwnListAndStaffListRespectArchiveAndMembership() throws Exception {
        list(student, CurrentUser.Role.STUDENT, "/api/projects?q=ListTest").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].id").value(privateProject))
                .andExpect(jsonPath("$.items[1].id").value(alpha)).andExpect(jsonPath("$.items[1].my_role").value("LEADER"));
        list(student, CurrentUser.Role.STUDENT, "/api/projects?q=ListTest&include_archived=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
        for (var role : List.of(CurrentUser.Role.ADMIN, CurrentUser.Role.SUPER_ADMIN, CurrentUser.Role.PROFESSOR)) {
            list(student, role, "/api/projects?q=ListTest").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(4))
                    .andExpect(jsonPath("$.items[0].my_role").value(org.hamcrest.Matchers.nullValue()));
        }
        list(student, CurrentUser.Role.ADMIN, "/api/projects?q=ListTest&include_archived=true&status=PLANNING")
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void publicListOrdersByActivityAndShowsCorrectFields() throws Exception {
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=ListTest").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.items[0].id").value(beta)).andExpect(jsonPath("$.items[1].id").value(alpha))
                .andExpect(jsonPath("$.items[2].id").value(gamma)).andExpect(jsonPath("$.items[2].last_activity_at").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[1].leader_name").value("Leader")).andExpect(jsonPath("$.items[1].member_count").value(2))
                .andExpect(jsonPath("$.items[1].is_member").value(true)).andExpect(jsonPath("$.items[0].is_member").value(false))
                .andExpect(jsonPath("$.items[2].is_member").value(false)).andExpect(jsonPath("$.items[1].weekly_activity").isEmpty())
                .andExpect(jsonPath("$.items[1].my_role").doesNotHaveJsonPath());
    }

    @Test
    void searchStatusSortAndPaginationWork() throws Exception {
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=ListTest%20Alpha").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(alpha));
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=beta-team").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(beta));
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=blue%20fish").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(beta));
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=ListTest&status=DONE").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(gamma));
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=ListTest&sort=name&page=2&size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.items[0].id").value(beta));
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=ListTest&sort=created").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(gamma));
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=ListTest&sort=recent_activity").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(beta));
        list(student, CurrentUser.Role.STUDENT, "/api/projects/public?q=%25").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(gamma));
    }

    @Test
    void rejectsInvalidConditionsAndUnauthorizedUsers() throws Exception {
        for (String path : List.of("/api/projects?page=0", "/api/projects?size=51", "/api/projects?size=0",
                "/api/projects?status=NOPE", "/api/projects/public?sort=NOPE")) {
            list(student, CurrentUser.Role.STUDENT, path).andExpect(status().isBadRequest());
        }
        list(student, CurrentUser.Role.GUEST, "/api/projects").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
        list(student, CurrentUser.Role.GUEST, "/api/projects/public").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
        list(student, CurrentUser.Role.ADMIN, "/api/projects/public").andExpect(status().isForbidden());
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }

    private long addUser(long githubId, String name) {
        return jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, role) VALUES (?, ?, ?, 'STUDENT') RETURNING id",
                Long.class, githubId, "list-" + githubId, name);
    }

    private long addProject(String name, String team, String description, String visibility, String status, boolean archive, String created) {
        return jdbc.queryForObject("INSERT INTO capstone.projects (name, team_name, description, visibility, status, created_by, archived_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END, CAST(? AS timestamptz)) RETURNING id",
                Long.class, name, team, description, visibility, status, student, archive, created);
    }

    private ResultActions list(long id, CurrentUser.Role role, String uri) throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(id, role), null, List.of());
        return mvc.perform(get(java.net.URI.create(uri)).with(authentication(auth)));
    }
}
