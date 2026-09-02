package dev.hauch.restartly.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final String MINIMAL = """
            version: 1
            general:
              timezone: "Europe/Madrid"
            countdown:
              default: "10m"
              steps: ["5m", "1m"]
            schedule:
              - id: daily
                type: daily
                time: "04:00"
            """;

    @Test
    void parsesMinimalConfig() {
        RestartlyConfig config = ConfigLoader.parse(MINIMAL);
        assertEquals(1, config.version());
        assertEquals("Europe/Madrid", config.zone().getId());
        assertEquals(Duration.ofMinutes(10), config.countdown().defaultDuration());
        assertEquals(1, config.schedules().size());
        assertEquals("daily", config.schedules().get(0).id());
        assertTrue(config.chat().message("restart_started", "").startsWith("{prefix}"));
    }

    @Test
    void bundledDefaultsAreValid() {
        RestartlyConfig config = ConfigLoader.parseDefaults();
        assertEquals(1, config.version());
        assertTrue(config.schedules().size() >= 1);
        assertNotNull(config.countdown().defaultDuration());
        assertTrue(config.warnings().size() >= 1);
        assertTrue(config.chat().prefix().contains("Restartly"));
    }

    @Test
    void invalidDailyScheduleIsReported() {
        String yaml = """
                version: 1
                countdown:
                  default: "10m"
                schedule:
                  - id: broken
                    type: daily
                """;
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("time")),
                "expected a 'time' problem, got: " + e.problems());
    }

    @Test
    void invalidTimezoneIsReported() {
        String yaml = """
                version: 1
                general:
                  timezone: "Not/AZone"
                """;
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("timezone")));
    }

    @Test
    void invalidYamlSyntaxThrows() {
        assertThrows(ConfigException.class, () -> ConfigLoader.parse("version: 1\n  bad indent: ["));
    }

    @Test
    void invalidDurationInStepsIsReported() {
        String yaml = """
                version: 1
                countdown:
                  default: "10m"
                  steps: ["5m", "nonsense"]
                """;
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("steps")));
    }

    @Test
    void perScheduleWarningsOverrideGlobal() {
        String yaml = """
                version: 1
                countdown:
                  default: "10m"
                  steps: ["5m"]
                warnings:
                  - time: "5m"
                    chat:
                      enabled: true
                      message: "global"
                schedule:
                  - id: custom
                    type: daily
                    time: "04:00"
                    countdown: "30m"
                    warnings:
                      - time: "5m"
                        chat:
                          enabled: true
                          message: "per-schedule"
                """;
        RestartlyConfig config = ConfigLoader.parse(yaml);
        assertEquals(Duration.ofMinutes(30),
                config.countdownFor(config.schedules().get(0)));
        assertEquals(1, config.warningsFor(config.schedules().get(0)).size());
        assertEquals("per-schedule",
                config.warningsFor(config.schedules().get(0)).get(0).chat().message());
    }

    @Test
    void schedulesWithoutConditionsInheritGlobal() {
        String yaml = """
                version: 1
                countdown:
                  default: "10m"
                conditions:
                  require_empty: true
                schedule:
                  - id: nightly
                    type: daily
                    time: "04:00"
                """;
        RestartlyConfig config = ConfigLoader.parse(yaml);
        var schedule = config.schedules().get(0);
        assertNull(schedule.conditions(), "per-schedule conditions should stay null");
        assertTrue(config.conditionsFor(schedule).requireEmpty());
    }

    @Test
    void duplicateScheduleIdsAreRejected() {
        String yaml = """
                version: 1
                countdown:
                  default: "10m"
                schedule:
                  - id: same
                    type: daily
                    time: "04:00"
                  - id: same
                    type: daily
                    time: "06:00"
                """;
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("duplicated")));
    }
}