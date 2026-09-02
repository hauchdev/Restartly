package dev.hauch.restartly.scheduler;

import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.util.TimeFormats;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Drives the configured schedules. Next fire times are recomputed on every
 * poll — the schedule set is small (a handful of entries) and each
 * computation is a few {@code java.time} operations, far cheaper than the
 * per-second cost of caching state that could go stale.
 *
 * <p>When two schedules would fire at the same instant the one with the
 * higher {@code priority} wins; the losing schedule simply waits for its
 * next occurrence. The restart manager itself guarantees that at most one
 * restart session is running.</p>
 */
public final class RestartScheduler {

    /** Fired schedule handed to the restart manager. */
    public record Fire(RestartlyConfig.ScheduleEntry schedule, Instant fireTime) {
    }

    /** Provides the last-trigger anchor for INTERVAL schedules. */
    @FunctionalInterface
    public interface TriggerProvider {
        Instant lastTrigger();
    }

    private final TriggerProvider triggerProvider;
    private volatile List<RestartlyConfig.ScheduleEntry> schedules = List.of();
    private volatile ZoneId globalZone = ZoneId.of("UTC");
    private volatile Instant lastCheck;

    public RestartScheduler(TriggerProvider triggerProvider) {
        this.triggerProvider = triggerProvider;
    }

    public void setSchedules(List<RestartlyConfig.ScheduleEntry> schedules, ZoneId globalZone) {
        this.globalZone = globalZone == null ? ZoneId.of("UTC") : globalZone;
        this.schedules = List.copyOf(schedules);
        this.lastCheck = null;
    }

    public List<RestartlyConfig.ScheduleEntry> schedules() {
        return schedules;
    }

    /**
     * Returns the schedule whose fire time fell inside the current check
     * window {@code (last poll, now]}. Next-fire times are computed relative
     * to the previous check so a restart scheduled at exactly 04:00 fires on
     * the first poll that crosses 04:00 instead of immediately re-scheduling
     * to the following day. {@code null} when nothing is due.
     */
    public Fire poll(Instant now) {
        Instant windowStart = lastCheck;
        lastCheck = now;
        if (windowStart == null) {
            return null; // first evaluation after (re)load: nothing can be due
        }
        Fire best = null;
        for (RestartlyConfig.ScheduleEntry schedule : schedules) {
            if (!schedule.enabled()) {
                continue;
            }
            Instant next;
            try {
                next = nextFireFor(schedule, null, windowStart);
            } catch (IllegalStateException e) {
                continue; // e.g. date schedule exhausted; it will never fire
            }
            if (next.isBefore(windowStart) || next.isAfter(now)) {
                continue;
            }
            if (best == null || isHigherPriority(schedule, best.schedule())) {
                best = new Fire(schedule, next);
            }
        }
        return best;
    }

    private boolean isHigherPriority(RestartlyConfig.ScheduleEntry a,
                                     RestartlyConfig.ScheduleEntry b) {
        if (a.priority() != b.priority()) {
            return a.priority() > b.priority();
        }
        return a.id().compareTo(b.id()) < 0;
    }

    /**
     * Nearest upcoming fire across all enabled schedules (does not consume).
     */
    public Optional<Fire> nearest(Instant now) {
        Fire best = null;
        for (RestartlyConfig.ScheduleEntry schedule : schedules) {
            if (!schedule.enabled()) {
                continue;
            }
            Instant candidate;
            try {
                candidate = nextFireFor(schedule, null, now);
            } catch (IllegalStateException e) {
                continue;
            }
            if (best == null || candidate.isBefore(best.fireTime())) {
                best = new Fire(schedule, candidate);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Formats the nearest fire time for status output.
     */
    public String describeNext(ZoneId zone) {
        Optional<Fire> next = nearest(Instant.now());
        if (next.isEmpty()) {
            return "none";
        }
        return TimeFormats.format(next.get().fireTime(), zone)
                + " (" + next.get().schedule().id() + ")";
    }

    private Instant nextFireFor(RestartlyConfig.ScheduleEntry schedule, Instant after,
                                Instant now) {
        Instant reference = after == null ? now : after;
        ZoneId zone = schedule.timezone() != null ? schedule.timezone() : globalZone;
        Instant lastTrigger = schedule.type() == RestartlyConfig.ScheduleType.INTERVAL
                ? triggerProvider.lastTrigger() : null;
        return ScheduleCalculator.nextFire(schedule, zone, reference, lastTrigger);
    }
}