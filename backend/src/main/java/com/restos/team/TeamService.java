package com.restos.team;

import com.restos.ratings.RatingService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Equipo motivado: meta de ventas del día con proyección y el ranking semanal con medallas. */
@Service
public class TeamService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RatingService ratings;
    private final RealtimePublisher realtime;
    private final ZoneId zone;

    public TeamService(JdbcClient jdbc, CurrentUser currentUser, RatingService ratings, RealtimePublisher realtime,
                       @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.ratings = ratings;
        this.realtime = realtime;
        this.zone = ZoneId.of(zone);
    }

    public List<BigDecimal> goals() {
        var branch = currentUser.require().branchId();
        var stored = jdbc.sql("SELECT weekday,amount FROM sales_goals WHERE branch_id=:branch")
                .param("branch", branch)
                .query((rs, row) -> new Object[]{rs.getInt("weekday"), rs.getBigDecimal("amount")}).list();
        var result = new java.util.ArrayList<BigDecimal>(java.util.Collections.nCopies(7, BigDecimal.ZERO));
        for (var row : stored) result.set((int) row[0] - 1, (BigDecimal) row[1]);
        return result;
    }

    @Transactional
    public List<BigDecimal> saveGoals(List<BigDecimal> amounts) {
        var branch = currentUser.require().branchId();
        for (var day = 1; day <= 7; day++) {
            var amount = amounts.size() >= day && amounts.get(day - 1) != null ? amounts.get(day - 1).max(BigDecimal.ZERO) : BigDecimal.ZERO;
            jdbc.sql("""
                    INSERT INTO sales_goals(branch_id,weekday,amount) VALUES (:branch,:day,:amount)
                    ON CONFLICT (branch_id,weekday) DO UPDATE SET amount=EXCLUDED.amount,updated_at=NOW()
                    """).param("branch", branch).param("day", day).param("amount", amount).update();
        }
        realtime.waiters(branch, "GOAL_UPDATED", java.util.Map.of("at", java.time.Instant.now().toString()));
        return goals();
    }

    /**
     * Progreso de hoy frente a la meta. La proyección usa el ritmo de los últimos cuatro días iguales
     * (qué parte de la venta suele haberse hecho a esta hora); sin historial, no se proyecta.
     */
    public GoalProgress today() {
        var branch = currentUser.require().branchId();
        var now = ZonedDateTime.now(zone);
        var start = now.toLocalDate().atStartOfDay(zone);
        var goal = jdbc.sql("SELECT amount FROM sales_goals WHERE branch_id=:branch AND weekday=:day")
                .param("branch", branch).param("day", now.getDayOfWeek().getValue())
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        var revenue = jdbc.sql("SELECT COALESCE(sum(amount),0) FROM payments WHERE branch_id=:branch AND paid_at>=:start")
                .param("branch", branch).param("start", Timestamp.from(start.toInstant())).query(BigDecimal.class).single();
        var secondsIntoDay = now.toLocalTime().toSecondOfDay();
        var history = jdbc.sql("""
                        SELECT COALESCE(sum(amount),0) total,
                          COALESCE(sum(amount) FILTER (WHERE EXTRACT(EPOCH FROM (paid_at AT TIME ZONE :zone)::time) <= :seconds),0) so_far
                        FROM payments WHERE branch_id=:branch AND paid_at >= :since AND paid_at < :start
                          AND EXTRACT(ISODOW FROM paid_at AT TIME ZONE :zone) = :day
                        """).param("zone", zone.getId()).param("seconds", secondsIntoDay).param("branch", branch)
                .param("since", Timestamp.from(start.minusWeeks(4).toInstant())).param("start", Timestamp.from(start.toInstant()))
                .param("day", now.getDayOfWeek().getValue())
                .query((rs, row) -> new BigDecimal[]{rs.getBigDecimal("total"), rs.getBigDecimal("so_far")}).single();
        BigDecimal projected = null;
        if (history[0].signum() > 0 && history[1].signum() > 0 && revenue.signum() > 0) {
            var fraction = history[1].divide(history[0], 6, RoundingMode.HALF_UP);
            projected = revenue.divide(fraction, 2, RoundingMode.HALF_UP);
        }
        var percent = goal.signum() > 0 ? revenue.multiply(BigDecimal.valueOf(100)).divide(goal, 1, RoundingMode.HALF_UP) : null;
        return new GoalProgress(now.toLocalDate(), goal, revenue, percent, projected, goal.signum() > 0 && revenue.compareTo(goal) >= 0);
    }

    /** Ranking del equipo: hoy, esta semana (lunes a hoy) o la semana pasada. */
    public RatingService.WaiterRanking leaderboard(String period, boolean includeComments) {
        var today = LocalDate.now(zone);
        var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var ranking = switch (period) {
            case "today" -> ratings.ranking(today, today);
            case "lastweek" -> ratings.ranking(monday.minusWeeks(1), monday.minusDays(1));
            default -> ratings.ranking(monday, today);
        };
        return includeComments ? ranking : new RatingService.WaiterRanking(ranking.overall(), ranking.waiters(), List.of());
    }

    public record GoalProgress(LocalDate date, BigDecimal goal, BigDecimal revenue, BigDecimal percent, BigDecimal projected,
                               boolean reached) {}
}
