package dev.hauch.restartly.warning;

import dev.hauch.restartly.Restartly;
import dev.hauch.restartly.config.RestartlyConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the configured warning list into a descending timeline for a given
 * countdown and tracks which steps have already fired.
 *
 * <p>A warning whose time is later than the countdown itself (e.g. a
 * "30m" warning on a 10m countdown) is ignored with a single log line
 * instead of crashing the config load.</p>
 */
public final class WarningManager {

    private final List<Entry> timeline = new ArrayList<>();
    private final Set<Long> fired = new HashSet<>();

    /** A warning bound to a remaining-time instant. */
    public record Entry(Duration remaining, RestartlyConfig.WarningEntry warning) {
    }

    public void reset(List<RestartlyConfig.WarningEntry> warnings, Duration countdown) {
        timeline.clear();
        fired.clear();
        Set<Long> seen = new HashSet<>();
        List<Entry> sorted = new ArrayList<>();
        for (RestartlyConfig.WarningEntry warning : warnings) {
            if (warning.time() == null) {
                continue;
            }
            if (warning.time().compareTo(countdown) > 0) {
                Restartly.LOGGER.warn(
                        "[Restartly] Ignoring warning at {}: longer than the countdown of {}.",
                        warning.time(), countdown);
                continue;
            }
            long seconds = warning.time().getSeconds();
            if (seen.add(seconds)) {
                sorted.add(new Entry(warning.time(), warning));
            }
        }
        sorted.sort(Comparator.comparing(Entry::remaining).reversed());
        timeline.addAll(sorted);
    }

    public List<Entry> timeline() {
        return List.copyOf(timeline);
    }

    /**
     * Collects the warnings whose remaining time was reached at the given
     * remaining time, marking them as fired. Each warning fires at most once
     * per countdown.
     */
    public List<Entry> warningsReached(Duration remaining) {
        List<Entry> reached = new ArrayList<>();
        long seconds = Math.max(0, remaining.getSeconds());
        for (Entry entry : timeline) {
            if (entry.remaining().getSeconds() == seconds
                    && fired.add(seconds)) {
                reached.add(entry);
            }
        }
        return reached;
    }

    /**
     * Returns the countdown step seconds in descending order (used by the
     * actionbar/bossbar and status output).
     */
    public List<Long> stepSeconds() {
        return timeline.stream().map(e -> e.remaining().getSeconds()).toList();
    }
}