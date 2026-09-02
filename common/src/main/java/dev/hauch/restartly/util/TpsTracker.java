package dev.hauch.restartly.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Measures server TPS from tick intervals using {@link System#nanoTime()} —
 * no threads, no blocking, negligible cost. Call {@link #recordTick()} on
 * every server tick, ideally at the start of the tick cycle.
 */
public final class TpsTracker {

    private static final int SAMPLES = 20;

    private final Deque<Long> intervals = new ArrayDeque<>(SAMPLES);
    private long lastNanos = System.nanoTime();
    private double currentTps = 20.0;

    public void recordTick() {
        long now = System.nanoTime();
        long interval = now - lastNanos;
        lastNanos = now;
        intervals.add(interval);
        if (intervals.size() > SAMPLES) {
            intervals.removeFirst();
        }
        long sum = 0;
        for (long value : intervals) {
            sum += value;
        }
        double avgMillis = (sum / (double) intervals.size()) / 1_000_000.0;
        currentTps = avgMillis <= 0 ? 20.0 : Math.min(20.0, 1_000.0 / avgMillis);
    }

    /**
     * Rolling TPS between 0 and 20 (20 while the server runs fine).
     */
    public double currentTps() {
        return currentTps;
    }
}