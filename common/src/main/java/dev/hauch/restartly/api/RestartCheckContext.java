package dev.hauch.restartly.api;


/**
 * Read-only view of the environment handed to registered
 * {@link RestartCondition}s when the manager evaluates whether a
 * restart may proceed.
 */
public record RestartCheckContext(
        int onlinePlayers,
        int maxPlayers,
        double tps,
        boolean anyPlayerInCombat,
        boolean anyActiveEvent
) {

    public static RestartCheckContext of(int onlinePlayers, int maxPlayers, double tps,
                                         boolean anyPlayerInCombat, boolean anyActiveEvent) {
        return new RestartCheckContext(onlinePlayers, maxPlayers, tps,
                anyPlayerInCombat, anyActiveEvent);
    }

    /**
     * Test helper producing a neutral context (no players, healthy TPS).
     */
    public static RestartCheckContext neutral() {
        return new RestartCheckContext(0, 100, 20.0, false, false);
    }
}