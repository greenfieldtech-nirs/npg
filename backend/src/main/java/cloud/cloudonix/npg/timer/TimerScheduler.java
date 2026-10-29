package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.workflow.Workflow;
import io.vertx.core.Future;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Starts and stops durable workflow timers inside the caller's transaction. Durations come from
 * np_system_parameters ({@code timer.<NAME>.seconds}); whether a timer counts only working time
 * comes from the timer matrix (docs/protocol/timer-matrix.yml) and the working calendar.
 *
 * <p>Each timer records the workflow state it guards ({@code expectedState}); when it fires in any
 * other state the workflow has moved on and the timer is a no-op, which makes firing idempotent.
 */
public class TimerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimerScheduler.class);
    static final long DEFAULT_SECONDS = 3600;

    private final Set<String> workingHoursTimers;

    public TimerScheduler(ProtocolCatalogLoader catalog) {
        this.workingHoursTimers = catalog.loadTimerMatrix().getJsonArray("timers", new JsonArray()).stream()
            .map(o -> (JsonObject) o)
            .filter(t -> Boolean.TRUE.equals(t.getBoolean("working_hours_sensitive")))
            .map(t -> t.getString("name"))
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Starts the named protocol timer, guarding the workflow's current state. */
    public Future<Long> start(SqlClient tx, Workflow wf, String timerName, String action) {
        return dueAt(tx, timerName).compose(due -> startAt(tx, wf, timerName, action, due));
    }

    /** Starts a timer that fires at an absolute UTC time (e.g. the approved port time). */
    public Future<Long> startAt(SqlClient tx, Workflow wf, String timerName, String action, LocalDateTime dueUtc) {
        JsonObject actionJson = new JsonObject().put("action", action).put("expectedState", wf.state());
        return new TimerService(tx).createTimer(wf.id(), null, timerName, action, dueUtc, actionJson);
    }

    /** Cancels the workflow's pending timers with this name (no-op when there are none). */
    public Future<Void> cancel(SqlClient tx, long workflowId, String timerName) {
        return tx.preparedQuery("""
                UPDATE np_timers SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                WHERE workflow_id = ? AND timer_name = ? AND status IN ('PENDING', 'CLAIMED')
                """)
            .execute(Tuple.of(workflowId, timerName))
            .mapEmpty();
    }

    public Future<Void> cancelAll(SqlClient tx, long workflowId) {
        return tx.preparedQuery("""
                UPDATE np_timers SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                WHERE workflow_id = ? AND status IN ('PENDING', 'CLAIMED')
                """)
            .execute(Tuple.of(workflowId))
            .mapEmpty();
    }

    /** When a timer started now would be due, in UTC. */
    public Future<LocalDateTime> dueAt(SqlClient tx, String timerName) {
        return tx.query("SELECT param_key, param_value FROM np_system_parameters "
                + "WHERE param_key LIKE 'timer.%' OR param_key LIKE 'calendar.%'")
            .execute()
            .compose(rows -> {
                Map<String, String> params = new HashMap<>();
                rows.forEach(r -> params.put(r.getString("param_key"), r.getString("param_value")));
                Duration duration = Duration.ofSeconds(seconds(params, timerName));
                Instant now = Instant.now();
                if (!workingHoursTimers.contains(timerName)) {
                    return Future.succeededFuture(LocalDateTime.ofInstant(now.plus(duration), ZoneOffset.UTC));
                }
                return calendar(tx, params).map(cal ->
                    LocalDateTime.ofInstant(cal.addWorkingTime(now, duration), ZoneOffset.UTC));
            });
    }

    private static long seconds(Map<String, String> params, String timerName) {
        String value = params.get("timer." + timerName + ".seconds");
        if (value == null) {
            log.warn("No duration configured for timer " + timerName + "; using " + DEFAULT_SECONDS + "s");
            return DEFAULT_SECONDS;
        }
        return Long.parseLong(value.trim());
    }

    private Future<BusinessCalendar> calendar(SqlClient tx, Map<String, String> params) {
        ZoneId zone = ZoneId.of(params.getOrDefault("calendar.zone", "Asia/Jerusalem"));
        LocalTime start = LocalTime.parse(params.getOrDefault("calendar.workday.start", "08:00"));
        LocalTime end = LocalTime.parse(params.getOrDefault("calendar.workday.end", "20:00"));
        Set<DayOfWeek> weekend = Arrays.stream(params.getOrDefault("calendar.weekend", "FRIDAY,SATURDAY").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).map(DayOfWeek::valueOf).collect(Collectors.toSet());
        return tx.query("SELECT calendar_date, is_working_day FROM np_working_calendar "
                + "WHERE calendar_date >= CURRENT_DATE - INTERVAL 1 DAY")
            .execute()
            .map(rows -> {
                Map<LocalDate, Boolean> overrides = new HashMap<>();
                for (Row row : rows) {
                    overrides.put(row.getLocalDate("calendar_date"), Boolean.TRUE.equals(row.getBoolean("is_working_day")));
                }
                return new BusinessCalendar(zone, start, end, weekend, overrides);
            });
    }
}
