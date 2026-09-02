package dev.hauch.restartly.warning;

import dev.hauch.restartly.config.RestartlyConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarningManagerTest {

    private static RestartlyConfig.WarningEntry warning(String time, String message) {
        return RestartlyConfig.WarningEntry.chatOnly(
                dev.hauch.restartly.util.DurationParser.parse(time), message);
    }

    @Test
    void timelineIsSortedDescending() {
        WarningManager manager = new WarningManager();
        manager.reset(List.of(
                warning("1m", "one"),
                warning("30s", "thirty"),
                warning("5m", "five")), Duration.ofMinutes(10));
        List<WarningManager.Entry> timeline = manager.timeline();
        assertEquals(3, timeline.size());
        assertEquals(Duration.ofMinutes(5), timeline.get(0).remaining());
        assertEquals(Duration.ofMinutes(1), timeline.get(1).remaining());
        assertEquals(Duration.ofSeconds(30), timeline.get(2).remaining());
    }

    @Test
    void eachStepFiresExactlyOnce() {
        WarningManager manager = new WarningManager();
        manager.reset(List.of(
                warning("1m", "one"),
                warning("30s", "thirty")), Duration.ofMinutes(10));

        // remaining 61s: nothing
        assertEquals(0, manager.warningsReached(Duration.ofSeconds(61)).size());
        // remaining 60s: "1m"
        List<WarningManager.Entry> first = manager.warningsReached(Duration.ofSeconds(60));
        assertEquals(1, first.size());
        assertEquals("one", first.get(0).warning().chat().message());
        // reaching 60s again (floating clock) must not re-fire
        assertEquals(0, manager.warningsReached(Duration.ofSeconds(60)).size());
        // 30s fires the second step
        List<WarningManager.Entry> second = manager.warningsReached(Duration.ofSeconds(30));
        assertEquals(1, second.size());
        assertEquals("thirty", second.get(0).warning().chat().message());
        // clock drifts backward and forward without dupes
        assertEquals(0, manager.warningsReached(Duration.ofSeconds(31)).size());
        assertEquals(0, manager.warningsReached(Duration.ofSeconds(29)).size());
    }

    @Test
    void duplicatesAreRemoved() {
        WarningManager manager = new WarningManager();
        manager.reset(List.of(
                warning("1m", "a"),
                warning("1m", "b")), Duration.ofMinutes(10));
        assertEquals(1, manager.timeline().size());
    }

    @Test
    void warningLongerThanCountdownIsIgnored() {
        WarningManager manager = new WarningManager();
        manager.reset(List.of(
                warning("30m", "too late"),
                warning("5m", "ok")), Duration.ofMinutes(10));
        assertEquals(1, manager.timeline().size());
        assertEquals("ok", manager.timeline().get(0).warning().chat().message());
    }

}