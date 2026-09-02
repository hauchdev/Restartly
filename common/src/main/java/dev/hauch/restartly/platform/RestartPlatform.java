package dev.hauch.restartly.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Everything Restartly needs from the surrounding game runtime. Each loader
 * (Fabric, Forge) provides one implementation that adapts its own APIs; the
 * common code only ever talks to this interface and to vanilla Minecraft
 * classes available on both loaders.
 */
public interface RestartPlatform {

    /**
     * Loader identifier for logs and status output ("Fabric", "Forge").
     */
    String loaderName();

    /**
     * Minecraft version string, e.g. "1.20.1".
     */
    String serverVersion();

    /**
     * The running server instance; {@code null} before the server started.
     */
    MinecraftServer server();

    /**
     * Online players in an unmodifiable view.
     */
    List<ServerPlayer> onlinePlayers();

    /**
     * Server player cap ({@code max-players}).
     */
    int maxPlayers();

    /**
     * Rolling TPS average (0.0 - 20.0).
     */
    double currentTps();

    /**
     * Whether the runtime is a dedicated server.
     */
    boolean isDedicatedServer();

    /**
     * Checks a player against a permission node. Implementations should fall
     * back to the given operator level when no permission plugin is present
     * (LuckPerms et al. hook in here optionally).
     */
    boolean hasPermission(ServerPlayer player, String node, int fallbackOpLevel);

    /**
     * Broadcasts a chat message to every online player.
     */
    void broadcast(Component message);

    /**
     * Saves the world and player data.
     */
    void saveAll();

    /**
     * Stops the server process.
     *
     * @param force when true, bypass the normal shutdown sequence
     */
    void stopServer(boolean force);

    /**
     * Disconnects a player with the given reason.
     */
    void disconnect(ServerPlayer player, Component reason);

    /**
     * Executes a command with console (op level 4) permissions.
     */
    void executeConsoleCommand(String command);

    /**
     * Whether a significant in-game event is active (used by the
     * {@code require_no_active_event} condition). Defaults to raid checks
     * implemented on the platform.
     */
    boolean anyActiveEvent();

    /**
     * Schedules a task on the main server thread (next tick).
     */
    void runOnServer(Runnable task);
}