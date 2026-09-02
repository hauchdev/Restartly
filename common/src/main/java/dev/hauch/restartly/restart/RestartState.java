package dev.hauch.restartly.restart;

/**
 * Lifecycle states of a restart session.
 *
 * <pre>
 * IDLE ──SCHEDULED──▶ COUNTDOWN ──▶ WAITING ──▶ RESTARTING ──▶ (server stops)
 *                        │              ▲
 *                        └───(cancel)───┘
 * </pre>
 *
 * <p>{@link #MAINTENANCE} is a companion flag state reported for status
 * output while maintenance is active; the session itself stays in
 * COUNTDOWN/WAITING.</p>
 */
public enum RestartState {

    /** Nothing planned. */
    IDLE,
    /** A restart is planned but its countdown has not started yet. */
    SCHEDULED,
    /** The countdown is running. */
    COUNTDOWN,
    /** Conditions failed; waiting for the environment to clear. */
    WAITING,
    /** Maintenance mode is active alongside a running countdown. */
    MAINTENANCE,
    /** The server is being restarted. */
    RESTARTING;

    public boolean isActive() {
        return this != IDLE;
    }
}