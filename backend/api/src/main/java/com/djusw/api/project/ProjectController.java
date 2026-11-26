package com.djusw.api.project;

import com.djusw.api.security.CurrentUser;
import com.djusw.api.security.CurrentUserService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/projects")
class ProjectController {
    private final CurrentUserService users;
    private final ProjectService projects;
    private final ProjectListService lists;

    ProjectController(CurrentUserService users, ProjectService projects, ProjectListService lists) {
        this.users = users;
        this.projects = projects;
        this.lists = lists;
    }

    @GetMapping
    ProjectListService.Page list(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "false", name = "include_archived") boolean includeArchived,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        var user = users.requireRole(CurrentUser.Role.STUDENT, CurrentUser.Role.PROFESSOR,
                CurrentUser.Role.ADMIN, CurrentUser.Role.SUPER_ADMIN);
        return lists.list(user, false, q, status, includeArchived, null, page, size);
    }

    @GetMapping("/public")
    ProjectListService.Page publicList(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "recent_activity") String sort,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        var user = users.requireRole(CurrentUser.Role.STUDENT);
        return lists.list(user, true, q, status, false, sort, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectService.ProjectDetail create(@RequestBody CreateProject request) {
        var user = users.requireRole(CurrentUser.Role.STUDENT);
        return projects.create(user, request);
    }

    @GetMapping("/{id}")
    ProjectService.ProjectDetail detail(@PathVariable long id) {
        return projects.detail(users.requireCompletedProfile(), id);
    }

    @PatchMapping("/{id}")
    ProjectService.ProjectDetail update(@PathVariable long id, @RequestBody Map<String, JsonNode> fields) {
        return projects.update(users.requireCompletedProfile(), id, fields);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable long id) {
        projects.archive(users.requireCompletedProfile(), id);
    }

    record CreateProject(String name, String team_name, String description, LocalDate starts_on,
                         LocalDate ends_on, String visibility) {}
}
