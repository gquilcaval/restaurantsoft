package com.restos.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Marcación desde la tablet autorizada (sin sesión: equipo + PIN), vista del día y correcciones del encargado. */
@RestController
public class AttendanceController {
    private final AttendanceService attendance;
    private final ScheduleService schedules;
    private final AttendanceExcel excel;
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public AttendanceController(AttendanceService attendance, ScheduleService schedules, AttendanceExcel excel) {
        this.attendance = attendance;
        this.schedules = schedules;
        this.excel = excel;
    }

    @PostMapping("/api/auth/device/attendance")
    AttendanceService.Board board(@Valid @RequestBody DeviceTokenRequest request) {
        return attendance.board(request.deviceToken());
    }

    @PostMapping("/api/auth/device/attendance/mark")
    AttendanceService.Receipt mark(@Valid @RequestBody MarkRequest request, HttpServletRequest http) {
        return attendance.mark(request.deviceToken(), request.userId(), request.pin(), request.kind(), http.getRemoteAddr());
    }

    @GetMapping({"/api/admin/attendance/day", "/api/admin/attendance/today"})
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    AttendanceService.Day day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendance.day(date);
    }

    @PostMapping("/api/admin/attendance/marks")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    AttendanceService.MarkView addMark(@Valid @RequestBody ManualMarkRequest request) {
        return attendance.addMark(request.userId(), request.date(), request.time(), request.kind(), request.note(), request.replaces());
    }

    @PostMapping("/api/admin/attendance/marks/{id}/void")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    void voidMark(@PathVariable UUID id, @Valid @RequestBody VoidRequest request) {
        attendance.voidMark(id, request.note());
    }

    @GetMapping("/api/admin/attendance/report")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    AttendanceService.Report report(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendance.report(from, to);
    }

    @GetMapping("/api/admin/attendance/report.xlsx")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    ResponseEntity<byte[]> reportExcel(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var report = attendance.report(from, to);
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"asistencia-" + from + "-al-" + to + ".xlsx\"")
                .body(excel.build(report));
    }

    @GetMapping("/api/admin/attendance/schedules")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    ScheduleService.Schedules schedules() {
        return schedules.list();
    }

    @PutMapping("/api/admin/attendance/schedules/{userId}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    ScheduleService.PersonSchedule saveSchedule(@PathVariable UUID userId, @Valid @RequestBody ScheduleRequest request) {
        return schedules.save(userId, request.days());
    }

    @PutMapping("/api/admin/attendance/settings")
    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    Map<String, Integer> saveSettings(@Valid @RequestBody SettingsRequest request) {
        return Map.of("toleranceMinutes", schedules.saveTolerance(request.toleranceMinutes()));
    }

    record DeviceTokenRequest(@NotBlank @Size(max = 200) String deviceToken) {}
    record MarkRequest(@NotBlank @Size(max = 200) String deviceToken, @NotNull UUID userId,
                       @NotBlank @Size(max = 6) String pin,
                       @NotBlank @Pattern(regexp = "IN|BREAK_START|BREAK_END|OUT") String kind) {}
    /** Sin {@code replaces}: marca nueva. Con {@code replaces}: nueva hora para esa marca (tipo y día se toman de ella). */
    record ManualMarkRequest(@NotNull UUID userId, @NotNull LocalDate date, @NotNull LocalTime time,
                             @NotBlank @Pattern(regexp = "IN|BREAK_START|BREAK_END|OUT") String kind,
                             @NotBlank @Size(max = 200) String note, UUID replaces) {}
    record VoidRequest(@NotBlank @Size(max = 200) String note) {}
    /** Días sin horas (o que no vienen) son de descanso; los 7 sin horas = sin horario fijo. */
    record ScheduleRequest(@NotNull @Size(max = 7) List<ScheduleService.DayShift> days) {}
    record SettingsRequest(@Min(0) @Max(60) int toleranceMinutes) {}
}
