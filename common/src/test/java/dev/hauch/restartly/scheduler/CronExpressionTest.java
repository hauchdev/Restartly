package dev.hauch.restartly.scheduler;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private ZonedDateTime at(String raw) {
        return ZonedDateTime.parse(raw + "Z");
    }

    @Test
    void dailyAtFour() {
        CronExpression cron = CronExpression.parse("0 4 * * *");
        ZonedDateTime now = at("2026-01-01T10:00:00");
        assertEquals(at("2026-01-02T04:00:00"), cron.nextAfter(now));
    }

    @Test
    void everyHalfHour() {
        CronExpression cron = CronExpression.parse("*/30 * * * *");
        assertEquals(at("2026-01-01T10:30:00"), cron.nextAfter(at("2026-01-01T10:05:00")));
    }

    @Test
    void weekdayWindow() {
        CronExpression cron = CronExpression.parse("0 6-8 * * mon-fri");
        // Friday 2026-01-02 09:00 -> saturday/sunday skipped -> monday 06:00
        assertEquals(at("2026-01-05T06:00:00"),
                cron.nextAfter(at("2026-01-02T09:00:00")));
    }

    @Test
    void monthNamesAndRanges() {
        CronExpression cron = CronExpression.parse("30 4 1,15 * *");
        assertEquals(at("2026-01-15T04:30:00"),
                cron.nextAfter(at("2026-01-01T05:00:00")));
    }

    @Test
    void sundayBothForms() {
        CronExpression cron = CronExpression.parse("0 0 * * sun");
        assertTrue(cron.matches(at("2026-01-04T00:00:00"))); // Sunday
        CronExpression zeroBased = CronExpression.parse("0 0 * * 0");
        assertTrue(zeroBased.matches(at("2026-01-04T00:00:00")));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("61 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * 32 * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* foo * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("*/0 * * * *"));
    }
}