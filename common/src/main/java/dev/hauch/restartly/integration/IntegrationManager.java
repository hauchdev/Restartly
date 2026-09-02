package dev.hauch.restartly.integration;

import dev.hauch.restartly.config.ConfigManager;
import dev.hauch.restartly.config.RestartlyConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of optional integrations (webhook, and future: Discord,
 * PlaceholderAPI, LuckPerms, ...). Every integration ships inside Restartly,
 * is off by default and is never a hard dependency.
 */
public final class IntegrationManager {

    /** What every optional integration must implement. */
    public interface Integration {
        void configure(RestartlyConfig.IntegrationsConfig config, ConfigManager configManager);

        void start();

        void shutdown();
    }

    private final List<Integration> integrations = new ArrayList<>();

    public void register(Integration integration) {
        integrations.add(integration);
    }

    public void configure(RestartlyConfig.IntegrationsConfig config, ConfigManager configManager) {
        for (Integration integration : integrations) {
            try {
                integration.configure(config, configManager);
            } catch (RuntimeException e) {
                dev.hauch.restartly.Restartly.LOGGER.error(
                        "[Restartly] Integration {} failed to configure: {}",
                        integration.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public void start() {
        for (Integration integration : integrations) {
            try {
                integration.start();
            } catch (RuntimeException e) {
                dev.hauch.restartly.Restartly.LOGGER.error(
                        "[Restartly] Integration {} failed to start: {}",
                        integration.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public void shutdown() {
        for (Integration integration : integrations) {
            try {
                integration.shutdown();
            } catch (RuntimeException ignored) {
                // shutdown best effort
            }
        }
    }
}