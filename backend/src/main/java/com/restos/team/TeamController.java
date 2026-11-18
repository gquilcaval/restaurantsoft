package com.restos.team;

import com.restos.ratings.RatingService;
import com.restos.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Metas del día y ranking del equipo, visibles para el salón y configurables por la administración. */
@RestController
public class TeamController {
    private final TeamService team;
    private final CurrentUser currentUser;

    public TeamController(TeamService team, CurrentUser currentUser) {
        this.team = team;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/staff/team/goal")
    @PreAuthorize("hasAnyRole('WAITER','KITCHEN','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    TeamService.GoalProgress goal() { return team.today(); }

    @GetMapping("/api/staff/team/leaderboard")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    RatingService.WaiterRanking leaderboard(@RequestParam(defaultValue = "week") String period) {
        var manager = !currentUser.require().role().name().equals("WAITER");
        return team.leaderboard(period, manager);
    }

    @GetMapping("/api/admin/team/goals")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<BigDecimal> goals() { return team.goals(); }

    @PutMapping("/api/admin/team/goals")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<BigDecimal> saveGoals(@Valid @RequestBody GoalsRequest request) { return team.saveGoals(request.amounts()); }

    record GoalsRequest(@NotNull @Size(min = 7, max = 7) List<BigDecimal> amounts) {}
}
