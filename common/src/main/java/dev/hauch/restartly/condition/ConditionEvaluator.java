package dev.hauch.restartly.condition;

import dev.hauch.restartly.api.RestartCheckContext;
import dev.hauch.restartly.api.RestartCondition;
import dev.hauch.restartly.config.RestartlyConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the configured {@code conditions} and {@code cancel_if} sections
 * plus any conditions registered through the API. Pure logic — fully unit
 * tested without a server.
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public record Result(boolean satisfied, List<String> failures) {
    }

    /**
     * Checks all restart conditions; registered conditions are evaluated
     * after the configured ones.
     */
    public static Result evaluate(RestartlyConfig.ConditionsConfig config,
                                  RestartCheckContext context,
                                  List<RestartCondition> registered) {
        List<String> failures = new ArrayList<>(4);

        if (context.onlinePlayers() < config.minPlayers()) {
            failures.add("less than " + config.minPlayers() + " player(s) required");
        }
        if (config.maxPlayers() >= 0 && context.onlinePlayers() > config.maxPlayers()) {
            failures.add("more than " + config.maxPlayers() + " player(s) allowed");
        }
        if (config.requireEmpty() && context.onlinePlayers() > 0) {
            failures.add("server must be empty ("
                    + context.onlinePlayers() + " online)");
        }
        if (context.tps() < config.requireTpsAbove()) {
            failures.add("TPS " + String.format(java.util.Locale.ROOT, "%.1f", context.tps())
                    + " below required " + config.requireTpsAbove());
        }
        if (config.requireNoCombat() && context.anyPlayerInCombat()) {
            failures.add("player(s) in combat");
        }
        if (config.requireNoActiveEvent() && context.anyActiveEvent()) {
            failures.add("an active event/raid is running");
        }

        for (RestartCondition condition : registered) {
            if (!condition.isSatisfied(context)) {
                failures.add(condition.label());
            }
        }
        return new Result(failures.isEmpty(), List.copyOf(failures));
    }

    /**
     * Evaluates the {@code cancel_if} rules; returning {@code true} means
     * the restart should be cancelled.
     */
    public static boolean shouldCancel(RestartlyConfig.ConditionsConfig config,
                                       RestartCheckContext context) {
        if (config.cancelIfPlayersAbove() >= 0
                && context.onlinePlayers() > config.cancelIfPlayersAbove()) {
            return true;
        }
        if (config.cancelIfEventActive() && context.anyActiveEvent()) {
            return true;
        }
        return config.cancelIfTpsBelow() > 0
                && context.tps() < config.cancelIfTpsBelow();
    }

    /**
     * Builds a fresh check context from live values through the platform
     * snapshot holder passed by the caller.
     */
    public static RestartCheckContext context(int online, int max, double tps,
                                              boolean combat, boolean event) {
        return new RestartCheckContext(online, max, tps, combat, event);
    }
}