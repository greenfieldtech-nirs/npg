package cloud.cloudonix.npg.timer;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Working-time arithmetic for protocol deadlines that only run during business hours. Working
 * days are weekdays outside {@code weekend}; {@code overrides} (np_working_calendar) mark holidays
 * (false) or extra working days (true).
 */
public record BusinessCalendar(ZoneId zone, LocalTime dayStart, LocalTime dayEnd, Set<DayOfWeek> weekend,
                               Map<LocalDate, Boolean> overrides) {

    private static final int MAX_DAYS_SCANNED = 3660;

    public boolean isWorkingDay(LocalDate date) {
        Boolean override = overrides.get(date);
        return override != null ? override : !weekend.contains(date.getDayOfWeek());
    }

    /** {@code start} plus {@code amount} of working time. */
    public Instant addWorkingTime(Instant start, Duration amount) {
        ZonedDateTime cursor = start.atZone(zone);
        Duration remaining = amount;
        for (int day = 0; day < MAX_DAYS_SCANNED; day++) {
            LocalDate date = cursor.toLocalDate();
            ZonedDateTime open = date.atTime(dayStart).atZone(zone);
            ZonedDateTime close = date.atTime(dayEnd).atZone(zone);
            if (isWorkingDay(date) && cursor.isBefore(close)) {
                ZonedDateTime from = cursor.isBefore(open) ? open : cursor;
                Duration available = Duration.between(from, close);
                if (remaining.compareTo(available) <= 0) {
                    return from.plus(remaining).toInstant();
                }
                remaining = remaining.minus(available);
            }
            cursor = date.plusDays(1).atTime(dayStart).atZone(zone);
        }
        throw new IllegalStateException("No working time found within " + MAX_DAYS_SCANNED + " days");
    }
}
