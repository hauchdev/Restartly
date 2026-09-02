package dev.hauch.restartly.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest {

    @Test
    void parsesSeconds() {
        assertEquals(Duration.ofSeconds(30), DurationParser.parse("30s"));
        assertEquals(Duration.ofSeconds(30), DurationParser.parse("30seconds"));
    }

    @Test
    void parsesMinutes() {
        assertEquals(Duration.ofMinutes(1), DurationParser.parse("1m"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5min"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5 minutos"));
    }

    @Test
    void parsesHours() {
        assertEquals(Duration.ofHours(6), DurationParser.parse("6h"));
        assertEquals(Duration.ofHours(2), DurationParser.parse("2 hours"));
    }

    @Test
    void parsesCompound() {
        assertEquals(Duration.ofMinutes(150), DurationParser.parse("2h30m"));
        assertEquals(Duration.ofSeconds(5400 + 47), DurationParser.parse("1h 30m 47s"));
        assertEquals(Duration.ofMinutes(150), DurationParser.parse("2h30m"));
    }

    @Test
    void parsesDays() {
        assertEquals(Duration.ofDays(2), DurationParser.parse("2d"));
        assertEquals(Duration.ofHours(50), DurationParser.parse("2d 2h"));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("30"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("-5m"));
    }

    @Test
    void formatsStable() {
        assertEquals("0s", DurationParser.format(Duration.ZERO));
        assertEquals("30s", DurationParser.format(Duration.ofSeconds(30)));
        assertEquals("5m", DurationParser.format(Duration.ofMinutes(5)));
        assertEquals("1h", DurationParser.format(Duration.ofHours(1)));
        assertEquals("2h 30m", DurationParser.format(Duration.ofMinutes(150)));
        assertEquals("1d 2h", DurationParser.format(Duration.ofHours(26)));
    }

    @Test
    void formatsClock() {
        assertEquals("10:00", DurationParser.formatClock(Duration.ofMinutes(10)));
        assertEquals("00:30", DurationParser.formatClock(Duration.ofSeconds(30)));
        assertEquals("1:05:00", DurationParser.formatClock(Duration.ofHours(1)
                .plusMinutes(5)));
    }

    @Test
    void parsesTimeOfDay() {
        assertEquals(4 * 3600, DurationParser.parseTimeOfDay("04:00"));
        assertEquals(4 * 3600 + 30 * 60, DurationParser.parseTimeOfDay("04:30"));
        assertEquals(24 * 3600 - 1, DurationParser.parseTimeOfDay("23:59:59"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseTimeOfDay("24:00"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseTimeOfDay("12:61"));
    }
}