package dev.hauch.restartly.maintenance;

import dev.hauch.restartly.api.PlaceholderContext;
import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.message.MinecraftText;
import dev.hauch.restartly.message.PlaceholderRegistry;
import dev.hauch.restartly.persistence.StateStore;
import dev.hauch.restartly.platform.RestartPlatform;
import net.minecraft.server.level.ServerPlayer;

/**
 * Maintenance mode: rejects new joins and optionally kicks everyone who is
 * not exempted by the {@code restartly.maintenance.bypass} permission.
 *
 * <p>The flag is persisted to {@code state.yml} so an admin who enables
 * maintenance and then accidentally restarts the server does not lose the
 * protection.</p>
 */
public final class MaintenanceManager {

    public static final String BYPASS_NODE = "restartly.maintenance.bypass";

    private final StateStore state;
    private final RestartPlatform platform;
    private final PlaceholderRegistry placeholders;

    private volatile boolean active;
    private volatile RestartlyConfig.MaintenanceConfig config;

    public MaintenanceManager(StateStore state, RestartPlatform platform,
                              PlaceholderRegistry placeholders) {
        this.state = state;
        this.platform = platform;
        this.placeholders = placeholders;
    }

    public void configure(RestartlyConfig.MaintenanceConfig config) {
        this.config = config;
    }

    public boolean isActive() {
        return active;
    }

    public void restore() {
        if (state != null && state.maintenance()) {
            this.active = true;
        }
    }

    /**
     * Toggles maintenance mode.
     *
     * @param kickNow immediately kick non-bypass players when activating
     * @param notify  broadcast the maintenance messages
     * @return {@code true} when the state changed
     */
    public boolean setActive(boolean active, boolean kickNow, boolean notify,
                             RestartlyConfig config) {
        if (this.active == active) {
            return false;
        }
        this.active = active;
        if (state != null) {
            state.setMaintenance(active);
        }
        if (notify) {
            String key = active ? "maintenance_active" : "maintenance_disabled";
            String template = config.chat().message(key,
                    active ? "{prefix}<red>Maintenance mode is now active."
                            : "{prefix}<green>Maintenance mode is now disabled.");
            PlaceholderContext context = PlaceholderContext.builder()
                    .state(active ? "MAINTENANCE" : "IDLE")
                    .onlinePlayers(platform.onlinePlayers().size())
                    .build();
            sendChat(config, template, context);
        }
        if (active && kickNow && config.maintenance().kickPlayers()) {
            kickNonBypassPlayers(config);
        }
        return true;
    }

    /**
     * Checks a joining player against the maintenance rules.
     *
     * @return the disconnect reason, or {@code null} when the player may join
     */
    public String joinCheck(ServerPlayer player, RestartlyConfig config) {
        if (!active) {
            return null;
        }
        RestartlyConfig.MaintenanceConfig maintenance = config.maintenance();
        if (!maintenance.blockJoin()) {
            return null;
        }
        if (platform.hasPermission(player, BYPASS_NODE, 2)) {
            return null;
        }
        return maintenance.kickMessage();
    }

    public void kickNonBypassPlayers(RestartlyConfig config) {
        for (ServerPlayer player : platform.onlinePlayers()) {
            if (!config.maintenance().kickPlayers()) {
                return;
            }
            if (platform.hasPermission(player, BYPASS_NODE, 2)) {
                continue;
            }
            disconnectWithMessage(player, config.maintenance().kickMessage());
        }
    }

    private void sendChat(RestartlyConfig config, String template,
                          PlaceholderContext context) {
        String message = template.replace("{prefix}",
                config.chat().prefix());
        message = placeholders.resolve(message, context);
        platform.broadcast(MinecraftText.parse(message));
    }

    private void disconnectWithMessage(ServerPlayer player, String message) {
        PlaceholderContext context = PlaceholderContext.simple("MAINTENANCE", null);
        String resolved = placeholders.resolve(message, context);
        platform.disconnect(player, MinecraftText.parse(resolved));
    }
}