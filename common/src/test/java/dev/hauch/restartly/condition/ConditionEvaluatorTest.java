package dev.hauch.restartly.condition;

import dev.hauch.restartly.api.RestartCheckContext;
import dev.hauch.restartly.api.RestartCondition;
import dev.hauch.restartly.config.RestartlyConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEvaluatorTest {

    private static RestartlyConfig.ConditionsConfig cfg(int min, int max, boolean empty,
                                                       double tps, boolean combat,
                                                       boolean event) {
        return new RestartlyConfig.ConditionsConfig(min, max, empty, tps, combat, event,
                Duration.ofSeconds(30), RestartlyConfig.ConditionFailurePolicy.CANCEL,
                -1, false, 0.0);
    }

    @Test
    void neutralContextPassesByDefault() {
        var result = ConditionEvaluator.evaluate(cfg(0, -1, false, 0, false, false),
                RestartCheckContext.neutral(), List.of());
        assertTrue(result.satisfied());
    }

    @Test
    void minPlayersBlocksRestart() {
        var result = ConditionEvaluator.evaluate(cfg(0, -1, false, 0, false, false),
                new RestartCheckContext(3, 100, 20.0, false, false), List.of());
        assertTrue(result.satisfied());

        var blocked = ConditionEvaluator.evaluate(cfg(5, -1, false, 0, false, false),
                new RestartCheckContext(3, 100, 20.0, false, false), List.of());
        assertFalse(blocked.satisfied());
        assertTrue(blocked.failures().stream().anyMatch(f -> f.contains("5")));
    }

    @Test
    void maxPlayersBlocksAboveThreshold() {
        var blocked = ConditionEvaluator.evaluate(cfg(0, 5, false, 0, false, false),
                new RestartCheckContext(6, 100, 20.0, false, false), List.of());
        assertFalse(blocked.satisfied());
    }

    @Test
    void requireEmptyBlocksWhenPlayersOnline() {
        var blocked = ConditionEvaluator.evaluate(cfg(0, -1, true, 0, false, false),
                new RestartCheckContext(1, 100, 20.0, false, false), List.of());
        assertFalse(blocked.satisfied());
    }

    @Test
    void tpsThreshold() {
        var blocked = ConditionEvaluator.evaluate(cfg(0, -1, false, 18.0, false, false),
                new RestartCheckContext(0, 100, 12.0, false, false), List.of());
        assertFalse(blocked.satisfied());
    }

    @Test
    void combatAndEventConditions() {
        var combatBlocked = ConditionEvaluator.evaluate(cfg(0, -1, false, 0, true, false),
                new RestartCheckContext(0, 100, 20.0, true, false), List.of());
        assertFalse(combatBlocked.satisfied());

        var eventBlocked = ConditionEvaluator.evaluate(cfg(0, -1, false, 0, false, true),
                new RestartCheckContext(0, 100, 20.0, false, true), List.of());
        assertFalse(eventBlocked.satisfied());
    }

    @Test
    void registeredConditionIsEnforced() {
        RestartCondition alwaysFalse = ctx -> false;
        var result = ConditionEvaluator.evaluate(cfg(0, -1, false, 0, false, false),
                RestartCheckContext.neutral(), List.of(alwaysFalse));
        assertFalse(result.satisfied());
    }

    @Test
    void cancelIfPlayersAbove() {
        var cfg = new RestartlyConfig.ConditionsConfig(0, -1, false, 0, false, false,
                Duration.ofSeconds(30), RestartlyConfig.ConditionFailurePolicy.CANCEL,
                10, false, 0.0);
        assertTrue(ConditionEvaluator.shouldCancel(cfg,
                new RestartCheckContext(11, 100, 20.0, false, false)));
        assertFalse(ConditionEvaluator.shouldCancel(cfg,
                new RestartCheckContext(9, 100, 20.0, false, false)));
    }

    @Test
    void cancelIfTpsBelow() {
        var cfg = new RestartlyConfig.ConditionsConfig(0, -1, false, 0, false, false,
                Duration.ofSeconds(30), RestartlyConfig.ConditionFailurePolicy.CANCEL,
                -1, false, 10.0);
        assertTrue(ConditionEvaluator.shouldCancel(cfg,
                new RestartCheckContext(0, 100, 5.0, false, false)));
    }
}