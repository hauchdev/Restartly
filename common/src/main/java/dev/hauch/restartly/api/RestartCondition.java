package dev.hauch.restartly.api;

/**
 * A named, side-effect free predicate the restart manager consults before
 * starting a restart.
 *
 * <p>Register instances through
 * {@link dev.hauch.restartly.api.RestartlyAPI#registerCondition}. Conditions
 * registered in code are <em>additional</em> to the configured
 * {@code conditions} section.</p>
 */
@FunctionalInterface
public interface RestartCondition {

    boolean isSatisfied(RestartCheckContext context);

    /**
     * Optional short label used in status output. Defaults to the
     * registration name supplied by {@code registerCondition}.
     */
    default String label() {
        return getClass().getSimpleName();
    }
}