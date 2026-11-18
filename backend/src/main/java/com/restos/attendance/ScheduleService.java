package com.restos.attendance;

import com.restos.audit.AuditService;
import com.restos.security.CurrentUser;
import com.restos.security.UserRole;
import com.restos.shared.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Horario semanal de cada persona y tolerancia de tardanza. Cada cambio crea una versión que rige desde hoy,
 * así las tardanzas y faltas de días pasados no cambian cuando se edita un horario.
 */
@Service
public class ScheduleService {
    private static final String PEOPLE_ROLES = "('BRANCH_MANAGER','WAITER','KITCHEN','STAFF')";
    private static final String[] DAY_NAMES = {"lun", "mar", "mié", "jue", "vie", "sáb", "dom"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("es-PE"));

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final ZoneId zone;

    public ScheduleService(JdbcClient jdbc, CurrentUser currentUser, AuditService audit,
                           @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
        this.zone = ZoneId.of(zone);
    }

    /** Horarios vigentes de toda la sucursal y la tolerancia del restaurante. */
    public Schedules list() {
        var scope = currentUser.require();
        var today = LocalDate.now(zone);
        var people = jdbc.sql("""
                        SELECT id,full_name,job_title,role,pin_hash IS NOT NULL has_pin FROM app_users
                        WHERE branch_id=:branch AND active AND role IN %s ORDER BY full_name
                        """.formatted(PEOPLE_ROLES)).param("branch", scope.branchId())
                .query((rs, row) -> new String[]{rs.getString("id"), rs.getString("full_name"), rs.getString("job_title"),
                        rs.getString("role"), String.valueOf(rs.getBoolean("has_pin"))}).list();
        var weeks = weeksOf(scope.branchId(), today);
        var rows = new ArrayList<PersonSchedule>();
        for (var person : people) {
            var id = UUID.fromString(person[0]);
            var week = weeks.get(id);
            rows.add(new PersonSchedule(id, person[1], person[2], person[3], Boolean.parseBoolean(person[4]),
                    week == null ? null : week.validFrom(), week == null ? emptyWeek() : week.days()));
        }
        return new Schedules(tolerance(scope.organizationId()), scope.role() == UserRole.ORGANIZATION_ADMIN, rows);
    }

    /** Guarda el horario semanal de una persona; rige desde hoy (si ya se cambió hoy, se reemplaza). */
    @Transactional
    public PersonSchedule save(UUID userId, List<DayShift> days) {
        var scope = currentUser.require();
        if (scope.role() == UserRole.BRANCH_MANAGER && scope.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tu propio horario lo define el dueño");
        }
        var name = jdbc.sql("SELECT full_name FROM app_users WHERE id=:user AND branch_id=:branch AND role IN " + PEOPLE_ROLES)
                .param("user", userId).param("branch", scope.branchId()).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Persona no encontrada"));
        var byDay = new HashMap<Integer, DayShift>();
        for (var day : days) {
            if (day.weekday() < 1 || day.weekday() > 7 || byDay.put(day.weekday(), day) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Horario inválido");
            }
            if ((day.start() == null) != (day.end() == null)) throw new ApiException(HttpStatus.BAD_REQUEST, "Falta la hora de entrada o de salida");
            if (day.start() != null && day.start().equals(day.end())) throw new ApiException(HttpStatus.BAD_REQUEST, "La entrada y la salida no pueden ser a la misma hora");
        }
        var today = LocalDate.now(zone);
        jdbc.sql("DELETE FROM staff_schedule_days WHERE user_id=:user AND valid_from=:today")
                .param("user", userId).param("today", today).update();
        for (int weekday = 1; weekday <= 7; weekday++) {
            var day = byDay.get(weekday);
            jdbc.sql("""
                    INSERT INTO staff_schedule_days(user_id,valid_from,weekday,starts_at,ends_at,created_by)
                    VALUES (:user,:from,:weekday,:start,:end,:by)
                    """).param("user", userId).param("from", today).param("weekday", weekday)
                    .param("start", day == null ? null : day.start()).param("end", day == null ? null : day.end())
                    .param("by", scope.userId()).update();
        }
        audit.record("SCHEDULE_CHANGED", "Horario de " + name + ": " + describe(byDay), null, userId);
        return list().people().stream().filter(person -> person.id().equals(userId)).findFirst().orElseThrow();
    }

    /** Solo el dueño cambia la tolerancia: afecta las tardanzas de todo el personal. */
    @Transactional
    public int saveTolerance(int minutes) {
        var scope = currentUser.require();
        if (scope.role() != UserRole.ORGANIZATION_ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "Solo el dueño cambia la tolerancia");
        if (minutes < 0 || minutes > 60) throw new ApiException(HttpStatus.BAD_REQUEST, "La tolerancia va de 0 a 60 minutos");
        var before = tolerance(scope.organizationId());
        jdbc.sql("UPDATE organizations SET attendance_tolerance_minutes=:minutes WHERE id=:org")
                .param("minutes", minutes).param("org", scope.organizationId()).update();
        if (before != minutes) audit.record("ATTENDANCE_SETTINGS", "Tolerancia de tardanza: " + before + " → " + minutes + " min", null, null);
        return minutes;
    }

    public int tolerance(UUID organizationId) {
        return jdbc.sql("SELECT attendance_tolerance_minutes FROM organizations WHERE id=:id").param("id", organizationId)
                .query(Integer.class).optional().orElse(10);
    }

    /**
     * Turno que tenía cada persona de la sucursal en una fecha, según el horario vigente ese día.
     * Sin clave = sin horario fijo; {@code rest} = día de descanso.
     */
    public Map<UUID, Shift> shiftsOn(UUID branchId, LocalDate date) {
        var result = new HashMap<UUID, Shift>();
        weeksOf(branchId, date).forEach((userId, week) -> {
            if (week.days().stream().allMatch(day -> day.start() == null)) return;
            var day = week.days().get(date.getDayOfWeek().getValue() - 1);
            if (day.start() == null) { result.put(userId, Shift.REST); return; }
            var start = date.atTime(day.start()).atZone(zone).toInstant();
            // Turno de noche: si la salida es antes que la entrada, termina al día siguiente.
            var endDate = day.end().isAfter(day.start()) ? date : date.plusDays(1);
            result.put(userId, new Shift(false, start, endDate.atTime(day.end()).atZone(zone).toInstant()));
        });
        return result;
    }

    /** Versión de horario vigente en una fecha para cada persona de la sucursal. */
    private Map<UUID, Week> weeksOf(UUID branchId, LocalDate date) {
        var rows = jdbc.sql("""
                        WITH v AS (
                          SELECT DISTINCT ON (s.user_id) s.user_id,s.valid_from FROM staff_schedule_days s
                          JOIN app_users u ON u.id=s.user_id
                          WHERE u.branch_id=:branch AND s.valid_from<=:date ORDER BY s.user_id,s.valid_from DESC)
                        SELECT d.user_id,d.valid_from,d.weekday,d.starts_at,d.ends_at FROM v
                        JOIN staff_schedule_days d ON d.user_id=v.user_id AND d.valid_from=v.valid_from
                        ORDER BY d.user_id,d.weekday
                        """).param("branch", branchId).param("date", date)
                .query((rs, row) -> new VersionDay(rs.getObject("user_id", UUID.class), rs.getObject("valid_from", LocalDate.class),
                        new DayShift(rs.getInt("weekday"), rs.getObject("starts_at", LocalTime.class), rs.getObject("ends_at", LocalTime.class))))
                .list();
        var weeks = new LinkedHashMap<UUID, Week>();
        for (var row : rows) {
            var week = weeks.computeIfAbsent(row.userId(), key -> new Week(row.validFrom(), new ArrayList<>(emptyWeek())));
            week.days().set(row.day().weekday() - 1, row.day());
        }
        return weeks;
    }

    private static List<DayShift> emptyWeek() {
        var days = new ArrayList<DayShift>();
        for (int weekday = 1; weekday <= 7; weekday++) days.add(new DayShift(weekday, null, null));
        return days;
    }

    /** Resumen para la auditoría: «lun–vie 9:00 a. m.–6:00 p. m. · sáb 10:00 a. m.–2:00 p. m.». */
    private static String describe(Map<Integer, DayShift> byDay) {
        var parts = new ArrayList<String>();
        int weekday = 1;
        while (weekday <= 7) {
            var day = byDay.get(weekday);
            if (day == null || day.start() == null) { weekday++; continue; }
            int last = weekday;
            while (last < 7 && byDay.get(last + 1) != null && day.start().equals(byDay.get(last + 1).start())
                    && day.end().equals(byDay.get(last + 1).end())) last++;
            var days = last == weekday ? DAY_NAMES[weekday - 1] : DAY_NAMES[weekday - 1] + "–" + DAY_NAMES[last - 1];
            parts.add(days + " " + day.start().format(TIME) + "–" + day.end().format(TIME));
            weekday = last + 1;
        }
        return parts.isEmpty() ? "sin horario fijo" : String.join(" · ", parts);
    }

    public record DayShift(int weekday, LocalTime start, LocalTime end) {}
    record Week(LocalDate validFrom, List<DayShift> days) {}
    private record VersionDay(UUID userId, LocalDate validFrom, DayShift day) {}
    public record PersonSchedule(UUID id, String fullName, String jobTitle, String role, boolean hasPin,
                                 LocalDate validFrom, List<DayShift> days) {}
    public record Schedules(int toleranceMinutes, boolean canEditTolerance, List<PersonSchedule> people) {}
    /** Turno de un día: descanso, o entrada y salida exactas (la salida puede caer al día siguiente). */
    public record Shift(boolean rest, Instant start, Instant end) {
        static final Shift REST = new Shift(true, null, null);
    }
}
