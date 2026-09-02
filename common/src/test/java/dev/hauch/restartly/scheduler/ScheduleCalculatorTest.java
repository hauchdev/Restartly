package dev.hauch.restartly.scheduler;

import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.config.RestartlyConfig.ScheduleEntry;
import dev.hauch.restartly.config.RestartlyConfig.ScheduleType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleCalculatorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private ScheduleEntry entry(ScheduleType type, String time, String... extras) {
        return new ScheduleEntry("test", type, time, List.of(), Map.of(), List.of(),
                null, null, "", true, 0, null, null, null, null, null, null);
    }

    private Instant now(String raw) {
        return ZonedDateTime.parse(raw + "Z").toInstant();
    }

    @Test
    void dailyNextIsSameDayWhenLaterOrTomorrow() {
        ScheduleEntry daily = entry(ScheduleType.DAILY, "04:00");
        assertEquals(now("2026-03-01T04:00:00"),
                ScheduleCalculator.nextFire(daily, UTC, now("2026-02-28T12:00:00"), null));
        assertEquals(now("2026-03-02T04:00:00"),
                ScheduleCalculator.nextFire(daily, UTC, now("2026-03-01T05:00:00"), null));
    }

    @Test
    void dailyMultipleTimesPicksNext() {
        var daily = new ScheduleEntry("test", ScheduleType.DAILY, null,
                List.of("04:00", "12:00", "20:00"), Map.of(), List.of(), null, null,
                "", true, 0, null, null, null, null, null, null);
        assertEquals(now("2026-03-01T20:00:00"),
                ScheduleCalculator.nextFire(daily, UTC, now("2026-03-01T12:30:00"), null));
        assertEquals(now("2026-03-02T04:00:00"),
                ScheduleCalculator.nextFire(daily, UTC, now("2026-03-01T21:00:00"), null));
    }

    @Test
    void weeklySkipsMissingDays() {
        var weekly = new ScheduleEntry("test", ScheduleType.WEEKLY, null, List.of(),
                Map.of("monday", List.of("04:00"), "friday", List.of("18:00")),
                List.of(), null, null, "", true, 0, null, null, null, null, null, null);
        // Wednesday 2026-03-04 12:00 -> Friday 18:00
        assertEquals(now("2026-03-06T18:00:00"),
                ScheduleCalculator.nextFire(weekly, UTC, now("2026-03-04T12:00:00"), null));
        // Friday 19:00 -> next Monday 04:00
        assertEquals(now("2026-03-09T04:00:00"),
                ScheduleCalculator.nextFire(weekly, UTC, now("2026-03-06T19:00:00"), null));
    }

    @Test
    void intervalAnchorsLastTrigger() {
        ScheduleEntry interval = entry(ScheduleType.INTERVAL, null);
        // raw interval is set via the field, so build manually
        var sixHours = new ScheduleEntry("test", ScheduleType.INTERVAL, null, List.of(),
                Map.of(), List.of(), Duration.ofHours(6), null, "", true, 0, null,
                null, null, null, null, null);
        Instant last = now("2026-03-01T00:00:00");
        assertEquals(now("2026-03-01T06:00:00"),
                ScheduleCalculator.nextFire(sixHours, UTC, now("2026-03-01T01:00:00"), last));
        assertEquals(now("2026-03-01T12:00:00"),
                ScheduleCalculator.nextFire(sixHours, UTC, now("2026-03-01T07:00:00"), last));
    }

    @Test
    void intervalWithoutAnchorCountsFromNow() {
        var sixHours = new ScheduleEntry("test", ScheduleType.INTERVAL, null, List.of(),
                Map.of(), List.of(), Duration.ofHours(6), null, "", true, 0, null,
                null, null, null, null, null);
        Instant now = now("2026-03-01T01:00:00");
        assertEquals(now.plus(Duration.ofHours(6)),
                ScheduleCalculator.nextFire(sixHours, UTC, now, null));
    }

    @Test
    void datesOnlyFutureOccurrences() {
        ScheduleEntry dates = entry(ScheduleType.DATES, null);
        var dated = new ScheduleEntry("test", ScheduleType.DATES, null, List.of(),
                Map.of(), List.of("2026-12-24 04:00"), null, null, "", true, 0, null,
                null, null, null, null, null);
        assertEquals(now("2026-12-24T04:00:00"),
                ScheduleCalculator.nextFire(dated, UTC, now("2026-03-01T10:00:00"), null));
        assertThrows(IllegalStateException.class,
                () -> ScheduleCalculator.nextFire(dated, UTC, now("2026-12-25T00:00:00"), null));
    }

    @Test
    void cronSchedule() {
        ScheduleEntry cron = entry(ScheduleType.CRON, null);
        var cronEntry = new ScheduleEntry("test", ScheduleType.CRON, null, List.of(),
                Map.of(), List.of(), null, "0 4 * * *", "", true, 0, null,
                null, null, null, null, null);
        assertEquals(now("2026-03-02T04:00:00"),
                ScheduleCalculator.nextFire(cronEntry, UTC, now("2026-03-01T12:00:00"), null));
    }

    @Test
    void dstSpringForwardShiftsNonExistentTime() {
        // Europe/Madrid: 2026-03-29 02:00 -> 03:00 (spring forward). The daily
        // 02:30 slot does not exist on the 29th and is shifted deterministically
        // to 03:30 instead of being skipped or throwing.
        ScheduleEntry daily = entry(ScheduleType.DAILY, "02:30");
        Instant result = ScheduleCalculator.nextFire(daily, MADRID,
                ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, MADRID).toInstant(), null);
        ZonedDateTime actual = ZonedDateTime.ofInstant(result, MADRID);
        assertEquals(2026, actual.getYear());
        assertEquals(3, actual.getMonthValue());
        assertEquals(29, actual.getDayOfMonth());
        assertEquals(3, actual.getHour());
        assertEquals(30, actual.getMinute());
    }

    @Test
    void timezoneIsRespected() {
        ScheduleEntry daily = entry(ScheduleType.DAILY, "04:00");
        // At 00:00Z (09:00 in Tokyo) the day's 04:00 JST slot already
        // passed, so Tokyo schedules the next occurrence 15h later than UTC
        // (Japan has no DST: 04:00 JST = 19:00Z the previous day).
        Instant tokyo = ScheduleCalculator.nextFire(daily, ZoneId.of("Asia/Tokyo"),
                now("2026-03-01T00:00:00"), null);
        Instant utc = ScheduleCalculator.nextFire(daily, UTC,
                now("2026-03-01T00:00:00"), null);
        assertEquals(15, Math.abs(Duration.between(tokyo, utc).toHours()));
    }
}