package dev.hauch.restartly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants. The mod id and logger are referenced from every package;
 * the version mirrors {@code gradle.properties} and is kept in sync on
 * releases.
 */
public final class Restartly {

    public static final String MOD_ID = "restartly";
    public static final String MOD_NAME = "Restartly";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Restartly() {
    }
}