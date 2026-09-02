package dev.hauch.restartly.platform;

/**
 * Static access to the platform adapter, initialized by the loaders once the
 * server object is available. Internal plumbing — prefer the
 * {@code dev.hauch.restartly.api} package for integrations.
 */
public final class PlatformProvider {

    private static volatile RestartPlatform platform;

    private PlatformProvider() {
    }

    public static void initialize(RestartPlatform platform) {
        if (PlatformProvider.platform != null && PlatformProvider.platform != platform) {
            throw new IllegalStateException("RestartPlatform is already initialized.");
        }
        PlatformProvider.platform = platform;
    }

    public static RestartPlatform get() {
        RestartPlatform current = platform;
        if (current == null) {
            throw new IllegalStateException("RestartPlatform has not been initialized.");
        }
        return current;
    }

    public static boolean isInitialized() {
        return platform != null;
    }
}