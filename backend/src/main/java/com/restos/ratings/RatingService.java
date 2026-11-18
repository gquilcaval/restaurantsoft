package com.restos.ratings;

import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Calificación del servicio al final de la visita y ranking de meseros. */
@Service
public class RatingService {
    private static final Set<String> HIGHLIGHTS = Set.of("FAST", "FRIENDLY", "FOOD", "CLEAN", "ATTENTIVE", "SLOW", "COLD_FOOD", "FORGOTTEN");
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final ZoneId zone;

    public RatingService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime,
                         @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.zone = ZoneId.of(zone);
    }

    /** Datos para invitar a calificar: quién atendió la mesa y si este comensal ya calificó. */
    public RatingPrompt prompt(UUID sessionId, UUID guestToken) {
        var context = context(sessionId, guestToken);
        return new RatingPrompt(context.waiterName(), context.tableLabel(), context.rated());
    }

    @Transactional
    public RatingPrompt rate(UUID sessionId, UUID guestToken, int stars, List<String> highlights, String comment) {
        if (stars < 1 || stars > 5) throw new ApiException(HttpStatus.BAD_REQUEST, "La calificación va de 1 a 5 estrellas");
        var context = context(sessionId, guestToken);
        var tags = highlights == null ? "" : String.join(",", highlights.stream().filter(HIGHLIGHTS::contains).distinct().toList());
        jdbc.sql("""
                INSERT INTO service_ratings(id,organization_id,branch_id,session_id,guest_id,waiter_id,stars,highlights,comment)
                VALUES (:id,:org,:branch,:session,:guest,:waiter,:stars,:highlights,:comment)
                ON CONFLICT (session_id,guest_id) DO UPDATE SET stars=EXCLUDED.stars,highlights=EXCLUDED.highlights,
                  comment=EXCLUDED.comment,created_at=NOW()
                """).param("id", UUID.randomUUID()).param("org", context.organizationId()).param("branch", context.branchId())
                .param("session", sessionId).param("guest", context.guestId()).param("waiter", context.waiterId())
                .param("stars", stars).param("highlights", tags.isBlank() ? null : tags)
                .param("comment", comment == null || comment.isBlank() ? null : comment.trim()).update();
        realtime.waiters(context.branchId(), "SERVICE_RATED",
                new Rated(context.tableLabel(), stars, context.waiterName(), context.waiterId()));
        return new RatingPrompt(context.waiterName(), context.tableLabel(), true);
    }

    private Context context(UUID sessionId, UUID guestToken) {
        return jdbc.sql("""
                        SELECT ts.organization_id,ts.branch_id,g.id guest_id,COALESCE(t.label,ts.label) label,
                          w.id waiter_id,w.full_name waiter_name,
                          EXISTS (SELECT 1 FROM service_ratings r WHERE r.session_id=ts.id AND r.guest_id=g.id) rated
                        FROM table_sessions ts
                        JOIN guests g ON g.session_id=ts.id AND g.guest_token=:guestToken
                        LEFT JOIN dining_tables t ON t.id=ts.table_id
                        LEFT JOIN app_users w ON w.id=COALESCE(ts.waiter_id,
                          (SELECT p.registered_by FROM payments p WHERE p.session_id=ts.id ORDER BY p.paid_at DESC LIMIT 1),
                          ts.opened_by)
                        WHERE ts.id=:session AND (ts.status='OPEN' OR ts.closed_at > NOW() - INTERVAL '24 hours')
                        """).param("session", sessionId).param("guestToken", guestToken)
                .query((rs, row) -> new Context(rs.getObject("organization_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("guest_id", UUID.class), rs.getString("label"), rs.getObject("waiter_id", UUID.class),
                        firstName(rs.getString("waiter_name")), rs.getBoolean("rated")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Esta visita ya no se puede calificar"));
    }

    private String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.trim().split("\\s+")[0];
    }

    /** Ranking de meseros: calificación, propinas y cobros en el período. */
    public WaiterRanking ranking(LocalDate from, LocalDate to) {
        var scope = currentUser.require();
        var branch = scope.branchId();
        var start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        var end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        List<WaiterScore> waiters = jdbc.sql("""
                        SELECT u.id,u.full_name,
                          (SELECT avg(r.stars) FROM service_ratings r WHERE r.waiter_id=u.id AND r.branch_id=:branch
                             AND r.created_at>=:start AND r.created_at<:end) rating,
                          (SELECT count(*) FROM service_ratings r WHERE r.waiter_id=u.id AND r.branch_id=:branch
                             AND r.created_at>=:start AND r.created_at<:end) ratings,
                          COALESCE((SELECT sum(p.tip) FROM payments p WHERE p.registered_by=u.id AND p.branch_id=:branch
                             AND p.paid_at>=:start AND p.paid_at<:end),0) tips,
                          COALESCE((SELECT sum(p.amount) FROM payments p WHERE p.registered_by=u.id AND p.branch_id=:branch
                             AND p.paid_at>=:start AND p.paid_at<:end),0) collected,
                          (SELECT count(DISTINCT p.session_id) FROM payments p WHERE p.registered_by=u.id AND p.branch_id=:branch
                             AND p.paid_at>=:start AND p.paid_at<:end) tables,
                          (SELECT avg(EXTRACT(EPOCH FROM (sr.attended_at-sr.requested_at))/60) FROM service_requests sr
                             WHERE sr.handled_by=u.id AND sr.branch_id=:branch AND sr.attended_at IS NOT NULL
                             AND sr.type IN ('WAITER_CALL','BILL') AND sr.requested_at>=:start AND sr.requested_at<:end) response,
                          (SELECT count(*) FROM service_requests sr WHERE sr.handled_by=u.id AND sr.branch_id=:branch
                             AND sr.attended_at IS NOT NULL AND sr.type IN ('WAITER_CALL','BILL')
                             AND sr.requested_at>=:start AND sr.requested_at<:end) responses
                        FROM app_users u
                        WHERE u.active AND ((u.branch_id=:branch AND u.role IN ('WAITER','BRANCH_MANAGER'))
                          OR EXISTS (SELECT 1 FROM payments p WHERE p.registered_by=u.id AND p.branch_id=:branch
                                     AND p.paid_at>=:start AND p.paid_at<:end))
                        """).param("branch", branch).param("start", start).param("end", end)
                .query((rs, row) -> new WaiterScore(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getObject("rating") == null ? null : Math.round(rs.getDouble("rating") * 10) / 10.0,
                        rs.getInt("ratings"), rs.getBigDecimal("tips"), rs.getBigDecimal("collected"), rs.getInt("tables"),
                        rs.getObject("response") == null ? null : Math.round(rs.getDouble("response") * 10) / 10.0,
                        rs.getInt("responses"), 0, List.of(), rs.getObject("id", UUID.class).equals(scope.userId())))
                .list();
        waiters = scoreAndMedals(waiters);
        var comments = jdbc.sql("""
                        SELECT r.stars,r.highlights,r.comment,r.created_at,u.full_name waiter,COALESCE(t.label,ts.label) label
                        FROM service_ratings r JOIN table_sessions ts ON ts.id=r.session_id
                        LEFT JOIN dining_tables t ON t.id=ts.table_id LEFT JOIN app_users u ON u.id=r.waiter_id
                        WHERE r.branch_id=:branch AND r.created_at>=:start AND r.created_at<:end
                          AND (r.comment IS NOT NULL OR r.highlights IS NOT NULL)
                        ORDER BY r.created_at DESC LIMIT 8
                        """).param("branch", branch).param("start", start).param("end", end)
                .query((rs, row) -> new RatingComment(rs.getInt("stars"),
                        rs.getString("highlights") == null ? List.of() : List.of(rs.getString("highlights").split(",")),
                        rs.getString("comment"), rs.getTimestamp("created_at").toInstant(), rs.getString("waiter"),
                        rs.getString("label"))).list();
        var overall = jdbc.sql("""
                        SELECT avg(stars) FROM service_ratings WHERE branch_id=:branch AND created_at>=:start AND created_at<:end
                        """).param("branch", branch).param("start", start).param("end", end)
                .query(Double.class).optional().orElse(null);
        return new WaiterRanking(overall == null ? null : Math.round(overall * 10) / 10.0, waiters, comments);
    }

    /**
     * Puntaje del ranking (0–100): ventas 40 %, calificación 30 %, rapidez de atención 15 % y propinas 15 %,
     * cada uno relativo al mejor del equipo. Además, una medalla por categoría para el mejor de cada una.
     */
    private List<WaiterScore> scoreAndMedals(List<WaiterScore> list) {
        var maxSales = list.stream().mapToDouble(w -> w.collected().doubleValue()).max().orElse(0);
        var maxTips = list.stream().mapToDouble(w -> w.tips().doubleValue()).max().orElse(0);
        var fastest = list.stream().filter(w -> w.responseMinutes() != null && w.responses() >= 2)
                .mapToDouble(WaiterScore::responseMinutes).min().orElse(0);
        var salesKing = best(list, w -> w.collected().doubleValue(), w -> w.collected().signum() > 0);
        var tipsKing = best(list, w -> w.tips().doubleValue(), w -> w.tips().signum() > 0);
        var starKing = best(list, w -> w.rating() == null ? 0 : w.rating() * 100 + w.ratings(), w -> w.rating() != null && w.ratings() >= 2);
        var fastKing = best(list, w -> w.responseMinutes() == null ? 0 : -w.responseMinutes(), w -> w.responseMinutes() != null && w.responses() >= 2);
        var tablesKing = best(list, w -> (double) w.tables(), w -> w.tables() > 0);
        return list.stream().map(w -> {
            var sales = maxSales > 0 ? w.collected().doubleValue() / maxSales : 0;
            var rating = w.rating() == null ? 0.5 : (w.rating() - 1) / 4.0;
            var speed = w.responseMinutes() == null || w.responseMinutes() <= 0 ? 0.5
                    : Math.min(1, Math.max(fastest, 0.5) / w.responseMinutes());
            var tips = maxTips > 0 ? w.tips().doubleValue() / maxTips : 0;
            var score = (int) Math.round((sales * 0.40 + rating * 0.30 + speed * 0.15 + tips * 0.15) * 100);
            var medals = new java.util.ArrayList<String>();
            if (w.userId().equals(salesKing)) medals.add("SALES");
            if (w.userId().equals(starKing)) medals.add("STARS");
            if (w.userId().equals(fastKing)) medals.add("FAST");
            if (w.userId().equals(tipsKing)) medals.add("TIPS");
            if (w.userId().equals(tablesKing)) medals.add("TABLES");
            return new WaiterScore(w.userId(), w.name(), w.rating(), w.ratings(), w.tips(), w.collected(), w.tables(),
                    w.responseMinutes(), w.responses(), score, medals, w.me());
        }).filter(w -> w.score() > 0 || w.tables() > 0 || w.ratings() > 0 || w.responses() > 0 || w.me())
                .sorted((a, b) -> Integer.compare(b.score(), a.score())).toList();
    }

    private UUID best(List<WaiterScore> list, java.util.function.ToDoubleFunction<WaiterScore> value,
                      java.util.function.Predicate<WaiterScore> eligible) {
        return list.stream().filter(eligible).max(java.util.Comparator.comparingDouble(value)).map(WaiterScore::userId).orElse(null);
    }

    private record Context(UUID organizationId, UUID branchId, UUID guestId, String tableLabel, UUID waiterId,
                           String waiterName, boolean rated) {}
    public record RatingPrompt(String waiterName, String tableLabel, boolean rated) {}
    public record Rated(String tableLabel, int stars, String waiterName, UUID waiterId) {}
    public record WaiterScore(UUID userId, String name, Double rating, int ratings, BigDecimal tips, BigDecimal collected,
                              int tables, Double responseMinutes, int responses, int score, List<String> medals,
                              boolean me) {}
    public record RatingComment(int stars, List<String> highlights, String comment, Instant createdAt, String waiter,
                                String tableLabel) {}
    public record WaiterRanking(Double overall, List<WaiterScore> waiters, List<RatingComment> comments) {}
}
