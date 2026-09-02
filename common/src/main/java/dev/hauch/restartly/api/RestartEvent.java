package dev.hauch.restartly.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Marker + common accessors for every Restartly event. Events are fired on
 * the server thread; keep listeners fast and never block the thread.
 */
public interface RestartEvent {

    /**
     * Fired when a restart is planned (scheduled or manual) but the
     * countdown has not started yet.
     */
    record RestartScheduled(Instant fireTime, Duration countdown, String reason,
                            String scheduleId) implements RestartEvent {
    }

    /**
     * Fired when the countdown actually starts.
     */
    record RestartStarted(Duration countdown, String reason, String scheduleId)
            implements RestartEvent {
    }

    /**
     * Fired every time a countdown warning step is reached.
     */
    record RestartWarning(Duration remaining, String stepLabel) implements RestartEvent {
        public String stepLabel() {
            return stepLabel == null ? "warning" : stepLabel;
        }
    }

    /**
     * Fired when a restart is cancelled (manual or automatic).
     */
    record RestartCancelled(String reason, boolean automatic) implements RestartEvent {
    }

    /**
     * Fired when the restart enters the waiting-for-conditions state
     * (smart restart).
     */
    record RestartWaiting(Duration maxDelay, String reason) implements RestartEvent {
    }

    /**
     * Fired when the server restart is actually being executed.
     */
    record RestartCompleted(String reason, String scheduleId) implements RestartEvent {
    }

    /**
     * Fired when maintenance mode is toggled.
     */
    record MaintenanceChanged(boolean active) implements RestartEvent {
    }

    /**
     * Listener contract. Implement anonymously or with a lambda.
     */
    @FunctionalInterface
    interface Listener<T extends RestartEvent> {
        void on(T event);
    }
}