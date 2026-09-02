package dev.hauch.restartly.message;

import dev.hauch.restartly.api.PlaceholderContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderRegistryTest {

    private final PlaceholderRegistry registry = new PlaceholderRegistry();

    private PlaceholderContext ctx(Duration remaining) {
        return PlaceholderContext.builder()
                .remaining(remaining)
                .onlinePlayers(7)
                .maxPlayers(100)
                .serverVersion("1.20.1")
                .reason("maintenance")
                .scheduleId("daily")
                .timezone(ZoneId.of("Europe/Madrid"))
                .state("COUNTDOWN")
                .uptime(Duration.ofHours(26))
                .now(Instant.parse("2026-03-01T10:00:00Z"))
                .build();
    }

    @Test
    void resolvesBuiltIns() {
        String resolved = registry.resolve(
                "{time} | {seconds} | {minutes} | {hours} | {players}/{max_players} | "
                        + "{server_version} | {reason} | {schedule} | {timezone} | {state} | "
                        + "{uptime}",
                ctx(Duration.ofMinutes(90)));
        assertEquals("1:30:00 | 5400 | 90 | 1 | 7/100 | 1.20.1 | maintenance | "
                + "daily | Europe/Madrid | COUNTDOWN | 1d 2h", resolved);
    }

    @Test
    void replacesUnknownWithLiteral() {
        assertEquals("value {unknown_placeholder}",
                registry.resolve("value {unknown_placeholder}", ctx(null)));
    }

    @Test
    void registersCustomPlaceholder() {
        assertTrue(registry.register("motd", c -> "restart soon"));
        assertEquals("restart soon!", registry.resolve("{motd}!", ctx(null)));
    }

    @Test
    void builtInNamesAreReserved() {
        assertFalse(registry.register("time", c -> "x"));
        assertEquals("1:30:00", registry.resolve("{time}", ctx(Duration.ofMinutes(90))));
    }

    @Test
    void nullTemplateYieldsEmpty() {
        assertEquals("", registry.resolve(null, ctx(null)));
    }
}