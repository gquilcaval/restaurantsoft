package com.restos.ratings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** El comensal califica la atención desde su celular; la administración ve el ranking de meseros. */
@RestController
public class RatingController {
    private final RatingService ratings;

    public RatingController(RatingService ratings) {
        this.ratings = ratings;
    }

    @GetMapping("/api/public/sessions/{sessionId}/rating")
    RatingService.RatingPrompt prompt(@PathVariable UUID sessionId, @RequestHeader("X-Guest-Token") UUID guestToken) {
        return ratings.prompt(sessionId, guestToken);
    }

    @PostMapping("/api/public/sessions/{sessionId}/rating")
    RatingService.RatingPrompt rate(@PathVariable UUID sessionId, @RequestHeader("X-Guest-Token") UUID guestToken,
                                    @Valid @RequestBody RateRequest request) {
        return ratings.rate(sessionId, guestToken, request.stars(), request.highlights(), request.comment());
    }

    @GetMapping("/api/admin/reports/waiters")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    RatingService.WaiterRanking ranking(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ratings.ranking(from, to.isBefore(from) ? from : to);
    }

    record RateRequest(@Min(1) @Max(5) int stars, @Size(max = 6) List<String> highlights, @Size(max = 500) String comment) {}
}
