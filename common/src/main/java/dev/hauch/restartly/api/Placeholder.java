package dev.hauch.restartly.api;

/**
 * Functional interface for a custom placeholder.
 *
 * <p>Register instances through
 * {@link dev.hauch.restartly.api.RestartlyAPI#registerPlaceholder}.</p>
 */
@FunctionalInterface
public interface Placeholder {

    /**
     * Renders the placeholder value for the given context.
     *
     * @return the replacement string, never {@code null}
     */
    String apply(PlaceholderContext context);
}