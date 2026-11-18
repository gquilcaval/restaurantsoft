package com.restos.attendance;

import com.restos.audit.AuditService;
import com.restos.security.CurrentUser;
import com.restos.security.DeviceService;
import com.restos.security.LoginAttemptService;
import com.restos.security.UserRole;
import com.restos.shared.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asistencia del personal. Se marca solo desde una tablet autorizada del local, con nombre y PIN;
 * la hora siempre la pone el servidor. Cada persona pasa por: fuera → trabajando ⇄ refrigerio → fuera.
 * El encargado puede corregir (agregar, cambiar la hora o anular) con motivo; nada se borra y todo queda en auditoría.
 */
@Service
public class AttendanceService {
    /** Una jornada abierta más tiempo que esto se considera «sin salida»: la persona puede volver a marcar entrada. */
    private static final Duration STALE_SHIFT = Duration.ofHours(16);
    /** Hasta cuántos días atrás se puede corregir: lo anterior ya suele estar en planilla. */
    private static final int CORRECTION_DAYS = 31;
    private static final int HISTORY_DAYS = 366;
    private static final String PEOPLE_ROLES = "('BRANCH_MANAGER','WAITER','KITCHEN','STAFF')";
    private static final Map<String, String> KIND_LABELS = Map.of(
            "IN", "Entrada", "BREAK_START", "Refrigerio", "BREAK_END", "Regreso", "OUT", "Salida");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.forLanguageTag("es-PE"));

    private final JdbcClient jdbc;
    private final DeviceService devices;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService attempts;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final ScheduleService schedules;
    private final ZoneId zone;

    public AttendanceService(JdbcClient jdbc, DeviceService devices, PasswordEncoder passwordEncoder,
                             LoginAttemptService attempts, CurrentUser currentUser, AuditService audit, ScheduleService schedules,
                             @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.passwordEncoder = passwordEncoder;
        this.attempts = attempts;
        this.currentUser = currentUser;
        this.audit = audit;
        this.schedules = schedules;
        this.zone = ZoneId.of(zone);
    }

    // ——— Tablet ———

    /** Pantalla de la tablet: personas con PIN de la sucursal y en qué estado está cada una. */
    public Board board(String deviceToken) {
        var device = devices.resolve(deviceToken);
        var enabled = enabled(device.organizationId());
        var people = enabled ? people(device.branchId(), false).stream().filter(Person::hasPin).toList() : List.<Person>of();
        return new Board(device.name(), device.branchName(), enabled, Instant.now(), people);
    }

    @Transactional
    public Receipt mark(String deviceToken, UUID userId, String pin, String kind, String remoteAddress) {
        var device = devices.resolve(deviceToken);
        requireEnabled(device.organizationId());
        // Igual que el ingreso con PIN: se limita por persona y por equipo para que no se puedan probar PINs.
        var userKey = "attendance|" + device.id() + "|" + userId;
        var deviceKey = "attendance-device|" + device.id() + "|" + remoteAddress;
        attempts.assertAllowed(userKey);
        attempts.assertAllowed(deviceKey);
        var person = jdbc.sql("SELECT full_name,pin_hash FROM app_users WHERE id=:user AND branch_id=:branch AND active AND role IN "
                        + PEOPLE_ROLES + " FOR UPDATE").param("user", userId).param("branch", device.branchId())
                .query((rs, row) -> new String[]{rs.getString("full_name"), rs.getString("pin_hash")}).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Persona no encontrada"));
        if (person[1] == null || pin == null || !passwordEncoder.matches(pin, person[1])) {
            attempts.failed(userKey);
            attempts.failed(deviceKey);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PIN incorrecto");
        }
        attempts.succeeded(userKey);
        attempts.succeeded(deviceKey);

        var last = lastMark(userId);
        var state = stateOf(last);
        if (!allowed(state).contains(kind)) throw new ApiException(HttpStatus.CONFLICT, conflictMessage(state));
        // La entrada abre una jornada en el día de hoy (hora del local); lo demás pertenece a la jornada abierta.
        var workDate = kind.equals("IN") || last == null ? LocalDate.now(zone) : last.workDate();
        var id = UUID.randomUUID();
        var now = Instant.now();
        jdbc.sql("""
                INSERT INTO attendance_marks(id,organization_id,branch_id,user_id,kind,marked_at,work_date,source,device_id)
                VALUES (:id,:org,:branch,:user,:kind,:at,:date,'DEVICE',:device)
                """).param("id", id).param("org", device.organizationId()).param("branch", device.branchId())
                .param("user", userId).param("kind", kind).param("at", java.sql.Timestamp.from(now))
                .param("date", workDate).param("device", device.id()).update();
        var worked = workedMinutes(validMarks(userId, workDate), now);
        return new Receipt(person[0], kind, now, stateAfter(kind), worked);
    }

    // ——— Encargado ———

    /** Vista del encargado: cómo va (o cómo fue) un día en su sucursal, con todas las marcas, incluidas las anuladas. */
    public Day day(LocalDate requested) {
        var scope = currentUser.require();
        var today = LocalDate.now(zone);
        var date = requested == null ? today : requested;
        if (date.isAfter(today)) throw new ApiException(HttpStatus.BAD_REQUEST, "No se puede ver un día que aún no llega");
        if (date.isBefore(today.minusDays(HISTORY_DAYS))) throw new ApiException(HttpStatus.BAD_REQUEST, "Solo se guarda el último año");
        if (!enabled(scope.organizationId())) return new Day(false, date, today, false, 0, List.of());
        var tolerance = schedules.tolerance(scope.organizationId());
        var isToday = date.equals(today);
        // Hoy también se miran las marcas de ayer: un turno de noche abierto ayer sigue contando como «presente».
        var byUser = byUser(marksOfBranch(scope.branchId(), isToday ? date.minusDays(1) : date, date));
        // Personas inactivas aparecen solo si marcaron ese día (por ejemplo, alguien que ya se fue).
        var people = people(scope.branchId(), true).stream().filter(person -> person.active()
                || byUser.getOrDefault(person.id(), List.of()).stream().anyMatch(mark -> mark.workDate().equals(date))).toList();
        var rows = rowsFor(scope.branchId(), date, isToday, people, byUser, tolerance, Instant.now(), new HashMap<>());
        return new Day(true, date, today, canCorrect(date), tolerance, rows);
    }

    /**
     * Reporte de un período (máximo 62 días) para la planilla: por persona, días y horas trabajadas,
     * tardanzas, faltas y días por revisar, con el detalle de cada día. Los días que aún no llegan no se evalúan.
     */
    public Report report(LocalDate from, LocalDate to) {
        var scope = currentUser.require();
        var today = LocalDate.now(zone);
        if (from == null || to == null || to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "Período inválido");
        if (to.toEpochDay() - from.toEpochDay() > 61) throw new ApiException(HttpStatus.BAD_REQUEST, "El período puede ser de hasta 2 meses");
        if (from.isAfter(today)) throw new ApiException(HttpStatus.BAD_REQUEST, "Ese período aún no empieza");
        if (from.isBefore(today.minusDays(HISTORY_DAYS))) throw new ApiException(HttpStatus.BAD_REQUEST, "Solo se guarda el último año");
        var branchName = jdbc.sql("SELECT name FROM branches WHERE id=:id").param("id", scope.branchId()).query(String.class).single();
        if (!enabled(scope.organizationId())) return new Report(false, from, to, today, 0, branchName, List.of());
        var tolerance = schedules.tolerance(scope.organizationId());
        var end = to.isAfter(today) ? today : to;
        var byUser = byUser(marksOfBranch(scope.branchId(), from, end));
        var people = people(scope.branchId(), true).stream()
                .filter(person -> person.active() || byUser.containsKey(person.id())).toList();
        var documents = new HashMap<UUID, String>();
        jdbc.sql("SELECT id,document_number FROM app_users WHERE branch_id=:branch AND document_number IS NOT NULL")
                .param("branch", scope.branchId())
                .query((rs, row) -> documents.put(rs.getObject("id", UUID.class), rs.getString("document_number"))).list();
        var days = new LinkedHashMap<UUID, List<ReportDay>>();
        people.forEach(person -> days.put(person.id(), new ArrayList<>()));
        var now = Instant.now();
        var shiftsCache = new HashMap<LocalDate, Map<UUID, ScheduleService.Shift>>();
        for (var date = from; !date.isAfter(end); date = date.plusDays(1)) {
            for (var row : rowsFor(scope.branchId(), date, false, people, byUser, tolerance, now, shiftsCache)) {
                days.get(row.id()).add(new ReportDay(date, row.scheduledStart(), row.scheduledEnd(), row.firstIn(), row.lastOut(),
                        row.workedMinutes(), row.state(), row.verdict(), row.lateMinutes(), row.needsReview()));
            }
        }
        var rows = new ArrayList<ReportRow>();
        for (var person : people) {
            var list = days.get(person.id());
            rows.add(new ReportRow(person.id(), person.fullName(), person.jobTitle(), person.role(), documents.get(person.id()),
                    (int) list.stream().filter(day -> !day.verdict().equals("NO_SCHEDULE") && !day.verdict().equals("REST")).count(),
                    (int) list.stream().filter(day -> !day.state().equals("ABSENT")).count(),
                    list.stream().mapToInt(ReportDay::workedMinutes).sum(),
                    (int) list.stream().filter(day -> day.verdict().equals("LATE")).count(),
                    list.stream().mapToInt(ReportDay::lateMinutes).sum(),
                    (int) list.stream().filter(day -> day.verdict().equals("ABSENT")).count(),
                    (int) list.stream().filter(day -> day.state().equals("NO_OUT")).count(),
                    (int) list.stream().filter(ReportDay::needsReview).count(), list));
        }
        return new Report(true, from, to, today, tolerance, branchName, rows);
    }

    /** Una fila por persona para una fecha: sus marcas, cómo terminó la jornada y cómo le fue contra su horario. */
    private List<DayRow> rowsFor(UUID branchId, LocalDate date, boolean isToday, List<Person> people, Map<UUID, List<MarkView>> byUser,
                                 int tolerance, Instant now, Map<LocalDate, Map<UUID, ScheduleService.Shift>> shiftsCache) {
        var rows = new ArrayList<DayRow>();
        for (var person : people) {
            var all = byUser.getOrDefault(person.id(), List.of());
            var ofDay = all.stream().filter(mark -> mark.workDate().equals(date)).toList();
            if (isToday && ofDay.stream().noneMatch(MarkView::valid)) {
                // Sin marcas hoy pero con jornada de ayer aún abierta: se muestra esa jornada.
                var yesterday = all.stream().filter(mark -> mark.workDate().equals(date.minusDays(1))).toList();
                var state = shiftState(validOnly(yesterday), now);
                if (state.equals("WORKING") || state.equals("ON_BREAK")) ofDay = yesterday;
            }
            var valid = validOnly(ofDay);
            var firstIn = valid.stream().filter(mark -> mark.kind().equals("IN")).map(Mark::at).findFirst().orElse(null);
            var lastOut = valid.isEmpty() || !valid.get(valid.size() - 1).kind().equals("OUT") ? null : valid.get(valid.size() - 1).at();
            var workDate = ofDay.isEmpty() ? date : ofDay.get(0).workDate();
            var shift = shiftsCache.computeIfAbsent(workDate, key -> schedules.shiftsOn(branchId, key)).get(person.id());
            var verdict = verdict(shift, firstIn, now, tolerance);
            rows.add(new DayRow(person.id(), person.fullName(), person.jobTitle(), person.role(), person.hasPin(),
                    shiftState(valid, now), firstIn, lastOut, workedMinutes(valid, now), !inOrder(valid), workDate,
                    verdict.code(), verdict.lateMinutes(), shift == null || shift.rest() ? null : shift.start(),
                    shift == null || shift.rest() ? null : shift.end(), ofDay));
        }
        return rows;
    }

    private static Map<UUID, List<MarkView>> byUser(List<MarkView> marks) {
        var byUser = new LinkedHashMap<UUID, List<MarkView>>();
        marks.forEach(mark -> byUser.computeIfAbsent(mark.userId(), key -> new ArrayList<>()).add(mark));
        return byUser;
    }

    /**
     * Marca manual del encargado: una marca olvidada o, con {@code replaces}, la nueva hora de una marca existente
     * (la anterior queda anulada, no se borra). El día debe quedar en orden: entrada, refrigerio, regreso, salida.
     */
    @Transactional
    public MarkView addMark(UUID userId, LocalDate workDate, LocalTime time, String kind, String note, UUID replaces) {
        var scope = currentUser.require();
        requireEnabled(scope.organizationId());
        var reason = requireReason(note);
        var person = personOf(userId, scope.branchId());
        requireNotSelf(scope, userId);
        MarkView previous = null;
        if (replaces != null) {
            previous = markOf(replaces, scope.branchId());
            if (!previous.valid()) throw new ApiException(HttpStatus.CONFLICT, "Esa marca ya estaba anulada");
            if (!previous.userId().equals(userId)) throw new ApiException(HttpStatus.BAD_REQUEST, "La marca es de otra persona");
            workDate = previous.workDate();
            kind = previous.kind();
        }
        requireCorrectable(workDate);
        var others = validMarks(userId, workDate).stream().filter(mark -> replaces == null || !mark.id().equals(replaces)).toList();
        var at = workDate.atTime(time).atZone(zone).toInstant();
        // Una salida, refrigerio o regreso antes de la primera entrada del día es de la madrugada siguiente (turno de noche).
        var firstIn = others.stream().filter(mark -> mark.kind().equals("IN")).map(Mark::at).min(Comparator.naturalOrder()).orElse(null);
        if (!kind.equals("IN") && firstIn != null && at.isBefore(firstIn)) at = at.plus(Duration.ofDays(1));
        if (at.isAfter(Instant.now().plusSeconds(60))) throw new ApiException(HttpStatus.BAD_REQUEST, "Esa hora todavía no llega");
        if (previous != null && previous.at().equals(at)) throw new ApiException(HttpStatus.BAD_REQUEST, "Es la misma hora que ya tenía");
        var candidate = new ArrayList<>(others);
        candidate.add(new Mark(null, kind, at, workDate));
        candidate.sort(Comparator.comparing(Mark::at));
        if (!inOrder(candidate)) throw new ApiException(HttpStatus.CONFLICT,
                "Con esa hora el día queda en desorden. El orden es entrada, refrigerio, regreso y salida");

        if (previous != null) voidRow(previous.id(), scope.userId(), "Hora corregida: " + reason);
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO attendance_marks(id,organization_id,branch_id,user_id,kind,marked_at,work_date,source,created_by,note)
                VALUES (:id,:org,:branch,:user,:kind,:at,:date,'MANUAL',:by,:note)
                """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("user", userId).param("kind", kind).param("at", java.sql.Timestamp.from(at))
                .param("date", workDate).param("by", scope.userId()).param("note", reason).update();
        var when = at.atZone(zone);
        var summary = previous == null
                ? "Marca agregada: %s de %s · %s %s · %s".formatted(KIND_LABELS.get(kind), person,
                        when.format(DAY_FORMAT), when.format(TIME_FORMAT), reason)
                : "Hora corregida: %s de %s · %s → %s · %s".formatted(KIND_LABELS.get(kind), person,
                        previous.at().atZone(zone).format(TIME_FORMAT), when.format(TIME_FORMAT), reason);
        audit.record(previous == null ? "ATTENDANCE_ADDED" : "ATTENDANCE_CHANGED", summary, null, id);
        return markOf(id, scope.branchId());
    }

    /** Anula una marca (por ejemplo, un toque por error). Queda visible y tachada, con quién y por qué. */
    @Transactional
    public void voidMark(UUID markId, String note) {
        var scope = currentUser.require();
        requireEnabled(scope.organizationId());
        var reason = requireReason(note);
        var mark = markOf(markId, scope.branchId());
        if (!mark.valid()) throw new ApiException(HttpStatus.CONFLICT, "Esa marca ya estaba anulada");
        requireNotSelf(scope, mark.userId());
        requireCorrectable(mark.workDate());
        voidRow(markId, scope.userId(), reason);
        var when = mark.at().atZone(zone);
        audit.record("ATTENDANCE_VOIDED", "Marca anulada: %s de %s · %s %s · %s".formatted(KIND_LABELS.get(mark.kind()),
                personOf(mark.userId(), scope.branchId()), when.format(DAY_FORMAT), when.format(TIME_FORMAT), reason), null, markId);
    }

    public boolean enabled(UUID organizationId) {
        return jdbc.sql("SELECT attendance_enabled FROM organizations WHERE id=:id").param("id", organizationId)
                .query(Boolean.class).optional().orElse(false);
    }

    // ——— Reglas ———

    private void requireEnabled(UUID organizationId) {
        if (!enabled(organizationId)) throw new ApiException(HttpStatus.FORBIDDEN,
                "El módulo de asistencia no está activo para este restaurante");
    }

    private void requireCorrectable(LocalDate workDate) {
        if (!canCorrect(workDate)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "Solo se pueden corregir los últimos " + CORRECTION_DAYS + " días");
    }

    private boolean canCorrect(LocalDate workDate) {
        var today = LocalDate.now(zone);
        return !workDate.isAfter(today) && !workDate.isBefore(today.minusDays(CORRECTION_DAYS));
    }

    /** Un encargado no corrige su propia asistencia: eso lo hace el dueño. */
    private static void requireNotSelf(CurrentUser.Scope scope, UUID userId) {
        if (scope.role() == UserRole.BRANCH_MANAGER && scope.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tus propias marcas las corrige el dueño");
        }
    }

    private static String requireReason(String note) {
        var reason = note == null ? "" : note.trim().replaceAll("\\s+", " ");
        if (reason.length() < 3) throw new ApiException(HttpStatus.BAD_REQUEST, "Escribe el motivo de la corrección");
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    /**
     * Comparación con el horario: sin horario fijo, descanso, a tiempo, tarde (pasada la tolerancia; los minutos
     * se cuentan desde la hora de entrada), aún no es su hora, no llega (ya pasó su entrada) o falta (terminó su turno).
     */
    static Verdict verdict(ScheduleService.Shift shift, Instant firstIn, Instant now, int tolerance) {
        if (shift == null) return new Verdict("NO_SCHEDULE", 0);
        if (shift.rest()) return new Verdict("REST", 0);
        if (firstIn != null) {
            var late = Duration.between(shift.start(), firstIn).toMinutes();
            return late > tolerance ? new Verdict("LATE", (int) late) : new Verdict("ON_TIME", 0);
        }
        if (now.isBefore(shift.start().plus(Duration.ofMinutes(tolerance)))) return new Verdict("PENDING", 0);
        return now.isBefore(shift.end()) ? new Verdict("NOT_YET", 0) : new Verdict("ABSENT", 0);
    }

    /** El día está en orden si, empezando fuera, cada marca es una acción permitida en ese momento. */
    static boolean inOrder(List<Mark> marks) {
        var state = "OUT";
        for (var mark : marks) {
            if (!allowed(state).contains(mark.kind())) return false;
            state = stateAfter(mark.kind());
        }
        return true;
    }

    /** Estado de una jornada: sin marcas, trabajando, en refrigerio, ya salió o quedó sin salida. */
    private static String shiftState(List<Mark> marks, Instant now) {
        if (marks.isEmpty()) return "ABSENT";
        var last = marks.get(marks.size() - 1);
        if (last.kind().equals("OUT")) return "OUT";
        if (Duration.between(last.at(), now).compareTo(STALE_SHIFT) > 0) return "NO_OUT";
        return last.kind().equals("BREAK_START") ? "ON_BREAK" : "WORKING";
    }

    /** Estado según la última marca; una jornada abierta demasiado tiempo cuenta como «fuera» (olvidó marcar salida). */
    private String stateOf(Mark last) {
        if (last == null || last.kind().equals("OUT")) return "OUT";
        if (Duration.between(last.at(), Instant.now()).compareTo(STALE_SHIFT) > 0) return "OUT";
        return last.kind().equals("BREAK_START") ? "ON_BREAK" : "WORKING";
    }

    private static String stateAfter(String kind) {
        return switch (kind) { case "OUT" -> "OUT"; case "BREAK_START" -> "ON_BREAK"; default -> "WORKING"; };
    }

    private static List<String> allowed(String state) {
        return switch (state) {
            case "WORKING" -> List.of("BREAK_START", "OUT");
            case "ON_BREAK" -> List.of("BREAK_END", "OUT");
            default -> List.of("IN");
        };
    }

    private static String conflictMessage(String state) {
        return switch (state) {
            case "WORKING" -> "Ya tienes la entrada registrada. Puedes marcar refrigerio o salida";
            case "ON_BREAK" -> "Estás en refrigerio. Marca tu regreso o tu salida";
            default -> "Primero marca tu entrada";
        };
    }

    /** Minutos trabajados de una jornada: suma los tramos entre entrada (o regreso) y refrigerio o salida. */
    static int workedMinutes(List<Mark> marks, Instant now) {
        long seconds = 0;
        Instant start = null;
        for (var mark : marks) {
            switch (mark.kind()) {
                case "IN", "BREAK_END" -> { if (start == null) start = mark.at(); }
                default -> { if (start != null) { seconds += Duration.between(start, mark.at()).getSeconds(); start = null; } }
            }
        }
        // Tramo aún abierto: cuenta hasta ahora, salvo que la jornada haya quedado sin salida.
        if (start != null && Duration.between(start, now).compareTo(STALE_SHIFT) <= 0) {
            seconds += Duration.between(start, now).getSeconds();
        }
        return (int) (seconds / 60);
    }

    // ——— Datos ———

    private List<Person> people(UUID branchId, boolean includeInactive) {
        return jdbc.sql("""
                        SELECT u.id,u.full_name,u.job_title,u.role,u.active,u.pin_hash IS NOT NULL has_pin,m.kind,m.marked_at,m.work_date
                        FROM app_users u LEFT JOIN LATERAL (
                          SELECT kind,marked_at,work_date FROM attendance_marks
                          WHERE user_id=u.id AND voided_at IS NULL ORDER BY marked_at DESC LIMIT 1) m ON TRUE
                        WHERE u.branch_id=:branch AND (u.active OR :inactive) AND u.role IN %s ORDER BY u.full_name
                        """.formatted(PEOPLE_ROLES)).param("branch", branchId).param("inactive", includeInactive)
                .query((rs, row) -> {
                    var last = rs.getString("kind") == null ? null : new Mark(null, rs.getString("kind"),
                            rs.getTimestamp("marked_at").toInstant(), rs.getObject("work_date", LocalDate.class));
                    return new Person(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("job_title"),
                            rs.getString("role"), rs.getBoolean("has_pin"), rs.getBoolean("active"), stateOf(last),
                            last == null ? null : last.at());
                }).list();
    }

    private String personOf(UUID userId, UUID branchId) {
        return jdbc.sql("SELECT full_name FROM app_users WHERE id=:user AND branch_id=:branch AND role IN " + PEOPLE_ROLES)
                .param("user", userId).param("branch", branchId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Persona no encontrada"));
    }

    private Mark lastMark(UUID userId) {
        return jdbc.sql("""
                        SELECT id,kind,marked_at,work_date FROM attendance_marks
                        WHERE user_id=:user AND voided_at IS NULL ORDER BY marked_at DESC LIMIT 1
                        """).param("user", userId).query((rs, row) -> markFrom(rs)).optional().orElse(null);
    }

    private List<Mark> validMarks(UUID userId, LocalDate workDate) {
        return jdbc.sql("""
                        SELECT id,kind,marked_at,work_date FROM attendance_marks
                        WHERE user_id=:user AND work_date=:date AND voided_at IS NULL ORDER BY marked_at
                        """).param("user", userId).param("date", workDate).query((rs, row) -> markFrom(rs)).list();
    }

    private static final String MARK_VIEW_SELECT = """
            SELECT m.id,m.user_id,m.kind,m.marked_at,m.work_date,m.source,m.note,c.full_name created_by,
                   m.voided_at,m.void_reason,v.full_name voided_by
            FROM attendance_marks m
            LEFT JOIN app_users c ON c.id=m.created_by
            LEFT JOIN app_users v ON v.id=m.voided_by
            """;

    private List<MarkView> marksOfBranch(UUID branchId, LocalDate from, LocalDate to) {
        return jdbc.sql(MARK_VIEW_SELECT + " WHERE m.branch_id=:branch AND m.work_date BETWEEN :from AND :to ORDER BY m.marked_at")
                .param("branch", branchId).param("from", from).param("to", to).query((rs, row) -> markViewFrom(rs)).list();
    }

    private MarkView markOf(UUID markId, UUID branchId) {
        return jdbc.sql(MARK_VIEW_SELECT + " WHERE m.id=:id AND m.branch_id=:branch").param("id", markId).param("branch", branchId)
                .query((rs, row) -> markViewFrom(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Marca no encontrada"));
    }

    private void voidRow(UUID markId, UUID by, String reason) {
        jdbc.sql("UPDATE attendance_marks SET voided_at=now(),voided_by=:by,void_reason=:reason WHERE id=:id AND voided_at IS NULL")
                .param("id", markId).param("by", by).param("reason", reason).update();
    }

    private static List<Mark> validOnly(List<MarkView> marks) {
        return marks.stream().filter(MarkView::valid).map(mark -> new Mark(mark.id(), mark.kind(), mark.at(), mark.workDate())).toList();
    }

    private static Mark markFrom(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Mark(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getTimestamp("marked_at").toInstant(),
                rs.getObject("work_date", LocalDate.class));
    }

    private static MarkView markViewFrom(java.sql.ResultSet rs) throws java.sql.SQLException {
        var voided = rs.getTimestamp("voided_at");
        return new MarkView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("kind"),
                rs.getTimestamp("marked_at").toInstant(), rs.getObject("work_date", LocalDate.class), rs.getString("source"),
                rs.getString("note"), rs.getString("created_by"), voided == null ? null : voided.toInstant(),
                rs.getString("void_reason"), rs.getString("voided_by"));
    }

    record Mark(UUID id, String kind, Instant at, LocalDate workDate) {}
    public record Person(UUID id, String fullName, String jobTitle, String role, boolean hasPin, boolean active,
                         String state, Instant lastAt) {}
    public record Board(String deviceName, String branchName, boolean enabled, Instant serverTime, List<Person> staff) {}
    public record Receipt(String fullName, String kind, Instant markedAt, String state, int workedMinutes) {}
    public record MarkView(UUID id, UUID userId, String kind, Instant at, LocalDate workDate, String source, String note,
                           String createdBy, Instant voidedAt, String voidReason, String voidedBy) {
        public boolean valid() { return voidedAt == null; }
    }
    /** Una fila por persona; {@code needsReview} indica marcas en desorden (por ejemplo, tras anular una). */
    public record DayRow(UUID id, String fullName, String jobTitle, String role, boolean hasPin, String state,
                         Instant firstIn, Instant lastOut, int workedMinutes, boolean needsReview, LocalDate workDate,
                         String verdict, int lateMinutes, Instant scheduledStart, Instant scheduledEnd, List<MarkView> marks) {}
    public record Day(boolean enabled, LocalDate date, LocalDate today, boolean canCorrect, int toleranceMinutes, List<DayRow> people) {}
    record Verdict(String code, int lateMinutes) {}
    public record ReportDay(LocalDate date, Instant scheduledStart, Instant scheduledEnd, Instant firstIn, Instant lastOut,
                            int workedMinutes, String state, String verdict, int lateMinutes, boolean needsReview) {}
    public record ReportRow(UUID id, String fullName, String jobTitle, String role, String documentNumber, int scheduledDays,
                            int daysWorked, int workedMinutes, int lateCount, int lateMinutes, int absences, int noOut,
                            int toReview, List<ReportDay> days) {}
    public record Report(boolean enabled, LocalDate from, LocalDate to, LocalDate today, int toleranceMinutes, String branchName,
                         List<ReportRow> people) {}
}
