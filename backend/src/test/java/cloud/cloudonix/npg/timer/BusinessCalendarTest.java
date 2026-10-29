package cloud.cloudonix.npg.timer;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BusinessCalendarTest {

    private static final ZoneId IL = ZoneId.of("Asia/Jerusalem");
    private final BusinessCalendar calendar = new BusinessCalendar(IL, LocalTime.of(8, 0), LocalTime.of(20, 0),
        Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
        Map.of(LocalDate.of(2026, 10, 7), false, LocalDate.of(2026, 10, 10), true));

    private static Instant at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, IL).toInstant();
    }

    @Test
    void withinTheWorkingDay() {
        // Monday 2026-10-05 10:00 + 1h
        assertEquals(at(10, 5, 11, 0), calendar.addWorkingTime(at(10, 5, 10, 0), Duration.ofHours(1)));
    }

    @Test
    void spillsIntoTheNextWorkingDay() {
        // Monday 19:30 + 1h -> 30 min Monday, 30 min Tuesday from 08:00
        assertEquals(at(10, 6, 8, 30), calendar.addWorkingTime(at(10, 5, 19, 30), Duration.ofHours(1)));
    }

    @Test
    void startsAtOpeningWhenStartedBeforeHours() {
        assertEquals(at(10, 5, 9, 0), calendar.addWorkingTime(at(10, 5, 2, 0), Duration.ofHours(1)));
    }

    @Test
    void skipsWeekendAndHolidaysButHonoursOverrides() {
        // Thursday 2026-10-08 19:00 + 2h: 1h Thursday, Fri/Sat weekend, Sat 10th is an override working day
        assertEquals(at(10, 10, 9, 0), calendar.addWorkingTime(at(10, 8, 19, 0), Duration.ofHours(2)));
        // Tuesday 10-06 19:00 + 2h: Wed 10-07 is a holiday -> Thursday 09:00
        assertEquals(at(10, 8, 9, 0), calendar.addWorkingTime(at(10, 6, 19, 0), Duration.ofHours(2)));
    }

    @Test
    void weekendDetection() {
        assertFalse(calendar.isWorkingDay(LocalDate.of(2026, 10, 9)));  // Friday
        assertTrue(calendar.isWorkingDay(LocalDate.of(2026, 10, 10)));  // override
        assertTrue(calendar.isWorkingDay(LocalDate.of(2026, 10, 11)));  // Sunday is a working day in Israel
    }
}
