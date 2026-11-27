package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import com.djusw.api.security.CurrentUser;
import com.djusw.api.security.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/invites")
class InviteController {
    private final CurrentUserService users;
    private final ProjectService projects;

    InviteController(CurrentUserService users, ProjectService projects) {
        this.users = users;
        this.projects = projects;
    }

    @PostMapping("/accept")
    ProjectService.InviteAccepted accept(@RequestBody Map<String, JsonNode> request) {
        var user = users.requireRole(CurrentUser.Role.STUDENT);
        JsonNode code = request == null ? null : request.get("code");
        if (code == null || !code.isTextual())
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "초대 코드를 입력하세요.");
        return projects.acceptInvite(user, code.asString().strip());
    }
}
