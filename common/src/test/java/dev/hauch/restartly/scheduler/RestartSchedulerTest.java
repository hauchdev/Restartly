package dev.hauch.restartly.scheduler;

import dev.hauch.restartly.config.RestartlyConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartSchedulerTest {

    private Instant at(String raw) {
        return ZonedDateTime.parse(raw + "Z").toInstant();
    }

    private RestartlyConfig.ScheduleEntry dateSchedule(String id, String date, int priority) {
        return new RestartlyConfig.ScheduleEntry(id, RestartlyConfig.ScheduleType.DATES,
                null, List.of(), Map.of(), List.of(date), null, null, "", true,
                priority, null, null, null, null, null, null);
    }

    private RestartlyConfig.ScheduleEntry daily(String id, String time, int priority) {
        return new RestartlyConfig.ScheduleEntry(id, RestartlyConfig.ScheduleType.DAILY,
                time, List.of(), Map.of(), List.of(), null, null, "", true,
                priority, null, null, null, null, null, null);
    }

    @Test
    void pollFiresDueScheduleAndConsumesIt() {
        RestartScheduler scheduler = new RestartScheduler(() -> null);
        scheduler.setSchedules(List.of(dateSchedule("once", "2026-06-01 04:00", 0)),
                ZoneId.of("UTC"));

        assertNull(scheduler.poll(at("2026-05-31T00:00:00")));
        RestartScheduler.Fire fire = scheduler.poll(at("2026-06-01T05:00:00"));
        assertNotNull(fire);
        assertEquals("once", fire.schedule().id());
        // Exhausted: no second poll.
        assertNull(scheduler.poll(at("2026-06-01T06:00:00")));
    }

    @Test
    void highestPriorityWinsWhenSimultaneous() {
        RestartScheduler scheduler = new RestartScheduler(() -> null);
        scheduler.setSchedules(List.of(
                dateSchedule("low", "2026-06-01 04:00", 0),
                dateSchedule("high", "2026-06-01 04:00", 10)),
                ZoneId.of("UTC"));
        assertNull(scheduler.poll(at("2026-06-01T03:59:00"))); // before the fire
        RestartScheduler.Fire fire = scheduler.poll(at("2026-06-01T04:01:00"));
        assertNotNull(fire);
        assertEquals("high", fire.schedule().id());
    }

    @Test
    void nearestReportsClosestFireWithoutConsuming() {
        RestartScheduler scheduler = new RestartScheduler(() -> null);
        scheduler.setSchedules(List.of(
                dateSchedule("later", "2026-08-01 04:00", 0),
                daily("sooner", "04:00", 0)),
                ZoneId.of("UTC"));
        var nearest = scheduler.nearest(at("2026-06-01T03:00:00"));
        assertTrue(nearest.isPresent());
        assertEquals("sooner", nearest.get().schedule().id());
        assertEquals(at("2026-06-01T04:00:00"), nearest.get().fireTime());
        // polling does not change because date is not due yet
        assertNull(scheduler.poll(at("2026-06-01T03:30:00")));
    }

    @Test
    void disabledSchedulesNeverFire() {
        var disabled = new RestartlyConfig.ScheduleEntry("off", RestartlyConfig.ScheduleType.DAILY,
                "04:00", List.of(), Map.of(), List.of(), null, null, "", false, 0,
                null, null, null, null, null, null);
        RestartScheduler scheduler = new RestartScheduler(() -> null);
        scheduler.setSchedules(List.of(disabled), ZoneId.of("UTC"));
        assertNull(scheduler.poll(at("2026-06-01T05:00:00")));
        assertTrue(scheduler.nearest(at("2026-06-01T05:00:00")).isEmpty());
    }
}