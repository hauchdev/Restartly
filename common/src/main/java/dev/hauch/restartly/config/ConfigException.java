package dev.hauch.restartly.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thrown when a configuration cannot be applied. Carries one or more
 * concrete, human readable problems so reloads can show exactly what is
 * wrong instead of a stack trace.
 */
public class ConfigException extends RuntimeException {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = Collections.unmodifiableList(new ArrayList<>(problems));
    }

    public ConfigException(String problem) {
        this(List.of(problem));
    }

    public List<String> problems() {
        return problems;
    }
}