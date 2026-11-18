package com.restos.waiters;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ver y cambiar el mesero a cargo de una mesa. */
@RestController
@PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
public class WaiterAssignmentController {
    private final WaiterAssignmentService assignments;

    public WaiterAssignmentController(WaiterAssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/api/staff/waiters")
    List<WaiterAssignmentService.WaiterOption> waiters() {
        return assignments.waiters();
    }

    @PutMapping("/api/staff/sessions/{sessionId}/waiter")
    WaiterAssignmentService.Assignment reassign(@PathVariable UUID sessionId, @RequestBody ReassignRequest request) {
        return assignments.reassign(sessionId, request.waiterId());
    }

    record ReassignRequest(UUID waiterId) {}
}
