package com.djusw.api.project;

import com.djusw.api.security.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/projects/{projectId}/deliverables")
class DeliverableController {
    private final CurrentUserService users;
    private final DeliverableService deliverables;

    DeliverableController(CurrentUserService users, DeliverableService deliverables) {
        this.users = users;
        this.deliverables = deliverables;
    }

    @GetMapping
    DeliverableService.Items list(@PathVariable long projectId, @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return deliverables.list(users.requireCompletedProfile(), projectId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DeliverableService.Item create(@PathVariable long projectId, @RequestBody Map<String, JsonNode> fields) {
        return deliverables.create(users.requireCompletedProfile(), projectId, fields);
    }

    @PatchMapping("/{deliverableId}")
    DeliverableService.Item update(@PathVariable long projectId, @PathVariable long deliverableId,
                                   @RequestBody Map<String, JsonNode> fields) {
        return deliverables.update(users.requireCompletedProfile(), projectId, deliverableId, fields);
    }

    @DeleteMapping("/{deliverableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long projectId, @PathVariable long deliverableId) {
        deliverables.delete(users.requireCompletedProfile(), projectId, deliverableId);
    }
}
