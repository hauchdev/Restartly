package dev.hauch.restartly.api;

import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.core.RestartlyCore;
import dev.hauch.restartly.restart.RestartState;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The public, stable integration surface of Restartly.
 *
 * <p>Other mods should <em>only</em> depend on this class (and the small
 * record types under {@link dev.hauch.restartly.api}). Everything else is
 * internal and may change between versions. All methods are safe to call
 * from any thread; state-changing calls run on the server thread.</p>
 */
public final class RestartlyAPI {

    private static volatile RestartlyCore core;

    private RestartlyAPI() {
    }

    /** Internal: called by RestartlyCore on startup. */
    public static void bind(RestartlyCore instance) {
        core = instance;
    }

    public static boolean isReady() {
        return core != null && core.platform() != null;
    }

    private static RestartlyCore core() {
        if (core == null) {
            throw new IllegalStateException("Restartly has not been initialized yet.");
        }
        return core;
    }

    // ------------------------------------------------------------------
    // Restart control
    // ------------------------------------------------------------------

    /**
     * Starts a manual restart countdown.
     *
     * @param countdown duration until the restart ({@code null} uses the
     *                  configured default)
     * @param reason    optional human readable reason ({@code null} fine)
     * @return {@code false} when a restart is already in progress
     */
    public static boolean scheduleRestart(Duration countdown, String reason) {
        return core().scheduleRestart(countdown, reason, false, false);
    }

    public static boolean scheduleRestart(Duration countdown) {
        return scheduleRestart(countdown, null);
    }

    /**
     * Plans a restart at an absolute time; the countdown starts once the
     * fire time is reached.
     */
    public static boolean scheduleRestartAt(Instant fireTime, String reason) {
        return core().planFutureRestart(fireTime, reason, null);
    }

    /**
     * Cancels the current restart session.
     */
    public static boolean cancelRestart(String reason) {
        return core().cancelRestart(reason);
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    public static boolean isRestarting() {
        return core().state() == RestartState.RESTARTING
                || core().state() == RestartState.COUNTDOWN;
    }

    public static RestartState getState() {
        return core().state();
    }

    public static Duration getRemainingTime() {
        return core().remaining();
    }

    public static Optional<Instant> getNextRestart() {
        return core().nextRestart();
    }

    public static boolean isMaintenance() {
        return core().maintenanceActive();
    }

    public static boolean setMaintenance(boolean active) {
        return core().setMaintenance(active);
    }

    // ------------------------------------------------------------------
    // Extension points
    // ------------------------------------------------------------------

    /**
     * Registers a custom placeholder. Built-in placeholders are reserved.
     *
     * @return {@code true} when registered
     */
    public static boolean registerPlaceholder(String name, Placeholder placeholder) {
        return core().placeholders().register(name, placeholder);
    }

    /**
     * Registers a custom restart condition that must be satisfied in
     * addition to the configured {@code conditions} section.
     */
    public static boolean registerCondition(RestartCondition condition) {
        core().restartManager().addCondition(condition);
        return true;
    }

    /**
     * Subscribes to Restartly events.
     *
     * <pre>
     * RestartlyAPI.subscribe(RestartEvent.RestartStarted.class,
     *     e -&gt; myPlugin.send(e.reason()));
     * </pre>
     */
    public static <T extends RestartEvent> void subscribe(Class<T> type,
                                                          RestartEvent.Listener<T> listener) {
        core().eventBus().subscribe(type, listener);
    }

    // ------------------------------------------------------------------
    // Configuration access
    // ------------------------------------------------------------------

    public static RestartlyConfig getConfig() {
        return core().configManager().get();
    }
}
