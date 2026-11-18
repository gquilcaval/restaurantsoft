package com.restos.live;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Modo TV para dueños y encargados. */
@RestController
@RequestMapping("/api/admin/live")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class LiveController {
    private final LiveService live;

    public LiveController(LiveService live) {
        this.live = live;
    }

    @GetMapping("/summary")
    LiveService.LiveSummary summary() { return live.summary(); }

    @GetMapping("/heat")
    List<LiveService.TableHeat> heat(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var end = to.isBefore(from) ? from : to;
        return live.heat(from, end.isAfter(from.plusDays(92)) ? from.plusDays(92) : end);
    }

    @GetMapping("/replay")
    LiveService.DayReplay replay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return live.replay(date);
    }
}
