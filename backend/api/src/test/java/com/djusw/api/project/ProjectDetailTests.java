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
class ProjectDetailTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private long leader, member, removed, outsider, project;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM capstone.project_repos WHERE github_repo_id IN (987660001, 987660002)");
        jdbc.update("DELETE FROM public.repos WHERE repo_id IN (987660001, 987660002)");
        jdbc.update("DELETE FROM public.installations WHERE installation_id = 987660001");
        jdbc.update("DELETE FROM capstone.project_members WHERE project_id IN "
                + "(SELECT id FROM capstone.projects WHERE name = 'detail test')");
        jdbc.update("DELETE FROM capstone.projects WHERE name = 'detail test'");
        jdbc.update("DELETE FROM public.users WHERE github_id BETWEEN 987660001 AND 987660004");
        leader = addUser(987660001L, "leader", "다람쥐");
        member = addUser(987660002L, "member", "가람");
        removed = addUser(987660003L, "removed", "나래");
        outsider = addUser(987660004L, "outsider", "타인");
        project = jdbc.queryForObject("INSERT INTO capstone.projects (name, created_by) VALUES ('detail test', ?) RETURNING id", Long.class, leader);
        jdbc.update("INSERT INTO capstone.project_members (project_id, user_id, role, removed_at) VALUES "
                + "(?, ?, 'LEADER', NULL), (?, ?, 'MEMBER', NULL), (?, ?, 'MEMBER', now())",
                project, leader, project, member, project, removed);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void teamProjectRestrictsAccessAndHonorsRoles() throws Exception {
        detail(leader, CurrentUser.Role.STUDENT, project).andExpect(status().isOk())
                .andExpect(jsonPath("$.my_role").value("LEADER")).andExpect(jsonPath("$.can_edit").value(true));
        detail(member, CurrentUser.Role.STUDENT, project).andExpect(status().isOk())
                .andExpect(jsonPath("$.my_role").value("MEMBER")).andExpect(jsonPath("$.can_edit").value(false));
        for (long id : List.of(removed, outsider)) {
            detail(id, CurrentUser.Role.STUDENT, project).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }
        for (var role : List.of(CurrentUser.Role.ADMIN, CurrentUser.Role.SUPER_ADMIN)) {
            detail(outsider, role, project).andExpect(status().isOk())
                    .andExpect(jsonPath("$.my_role").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.can_edit").value(true));
        }
        detail(outsider, CurrentUser.Role.PROFESSOR, project).andExpect(status().isOk())
                .andExpect(jsonPath("$.can_edit").value(false));
    }

    @Test
    void publicDetailsContainOnlyActiveMembersAndLinkedRepos() throws Exception {
        jdbc.update("UPDATE capstone.projects SET visibility = 'STUDENTS' WHERE id = ?", project);
        jdbc.update("INSERT INTO public.installations (installation_id, account_login, account_id, account_type, "
                + "repo_selection, status, installed_at) VALUES (987660001, 'team', 1, 'User', 'selected', 'ACTIVE', now())");
        jdbc.update("INSERT INTO public.repos (repo_id, installation_id, full_name, is_private, status) VALUES "
                + "(987660001, 987660001, 'team/private', true, 'LINKED'), "
                + "(987660002, 987660001, 'team/old', false, 'LINKED')");
        jdbc.update("INSERT INTO capstone.project_repos (project_id, github_repo_id, linked_by, is_primary, unlinked_at) "
                + "VALUES (?, 987660001, ?, true, NULL), (?, 987660002, ?, false, now())", project, leader, project, leader);
        detail(outsider, CurrentUser.Role.STUDENT, project).andExpect(status().isOk())
                .andExpect(jsonPath("$.my_role").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.can_edit").value(false))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].user_id").value(leader))
                .andExpect(jsonPath("$.members[1].user_id").value(member))
                .andExpect(jsonPath("$.members[0].student_id").doesNotExist())
                .andExpect(jsonPath("$.repos.length()").value(1))
                .andExpect(jsonPath("$.repos[0].repo_id").value(987660001L))
                .andExpect(jsonPath("$.repos[0].full_name").value("team/private"))
                .andExpect(jsonPath("$.repos[0].is_private").value(true))
                .andExpect(jsonPath("$.repos[0].is_primary").value(true));
    }

    @Test
    void archivedProjectsAreReadOnlyAndMissingProjectsReturn404() throws Exception {
        jdbc.update("UPDATE capstone.projects SET archived_at = now() WHERE id = ?", project);
        detail(leader, CurrentUser.Role.STUDENT, project).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true)).andExpect(jsonPath("$.can_edit").value(false));
        detail(outsider, CurrentUser.Role.ADMIN, project).andExpect(status().isOk())
                .andExpect(jsonPath("$.can_edit").value(false));
        detail(leader, CurrentUser.Role.STUDENT, Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void guestAndUnauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/projects/{id}", project)).andExpect(status().isUnauthorized());
        detail(outsider, CurrentUser.Role.GUEST, project).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
    }

    private long addUser(long githubId, String login, String name) {
        return jdbc.queryForObject("INSERT INTO public.users (github_id, login, name, role) VALUES (?, ?, ?, 'STUDENT') RETURNING id",
                Long.class, githubId, login, name);
    }

    private ResultActions detail(long id, CurrentUser.Role role, long projectId) throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(id, role), null, List.of());
        return mvc.perform(get("/api/projects/{id}", projectId).with(authentication(auth)));
    }
}
