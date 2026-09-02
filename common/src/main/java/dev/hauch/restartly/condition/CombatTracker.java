package dev.hauch.restartly.condition;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks players currently tagged as "in combat". Loader damage events call
 * {@link #flag} with the player UUID; the flag expires automatically after
 * the configured timeout so stale entries never block a restart forever.
 */
public final class CombatTracker {

    private final Map<UUID, Long> until = new ConcurrentHashMap<>();

    public void flag(UUID player, long timeoutMillis) {
        if (player == null) {
            return;
        }
        this.until.put(player, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis)));
    }

    public boolean isInCombat(UUID player) {
        if (player == null) {
            return false;
        }
        Long deadline = until.get(player);
        return deadline != null && deadline > System.nanoTime();
    }

    public boolean anyInCombat(Iterable<UUID> online) {
        if (until.isEmpty()) {
            return false;
        }
        long now = System.nanoTime();
        for (UUID player : online) {
            Long deadline = until.get(player);
            if (deadline != null && deadline > now) {
                return true;
            }
        }
        return false;
    }

    public int activeCount() {
        long now = System.nanoTime();
        int count = 0;
        for (Long deadline : until.values()) {
            if (deadline > now) {
                count++;
            }
        }
        return count;
    }

    public void clear() {
        until.clear();
    }

    public static CombatTracker create() {
        return new CombatTracker();
    }
}