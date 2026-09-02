package dev.hauch.restartly.platform;

import dev.hauch.restartly.util.TpsTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Forge adapter. Forge 1.20.1 uses the same Mojang mapped vanilla API as the
 * common module, so this implementation mirrors the Fabric one.
 */
public final class ForgeRestartPlatform implements RestartPlatform {

    private final MinecraftServer server;
    private final TpsTracker tpsTracker;

    public ForgeRestartPlatform(MinecraftServer server, TpsTracker tpsTracker) {
        this.server = server;
        this.tpsTracker = tpsTracker;
    }

    @Override
    public String loaderName() {
        return "Forge";
    }

    @Override
    public String serverVersion() {
        return server.getServerVersion();
    }

    @Override
    public MinecraftServer server() {
        return server;
    }

    @Override
    public List<ServerPlayer> onlinePlayers() {
        return server.getPlayerList().getPlayers();
    }

    @Override
    public int maxPlayers() {
        return server.getPlayerList().getMaxPlayers();
    }

    @Override
    public double currentTps() {
        return tpsTracker.currentTps();
    }

    @Override
    public boolean isDedicatedServer() {
        return server.isDedicatedServer();
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String node, int fallbackOpLevel) {
        return player.hasPermissions(fallbackOpLevel);
    }

    @Override
    public void broadcast(Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    @Override
    public void saveAll() {
        server.saveEverything(false, true, false);
    }

    @Override
    public void stopServer(boolean force) {
        server.halt(force);
    }

    @Override
    public void disconnect(ServerPlayer player, Component reason) {
        player.connection.disconnect(reason);
    }

    @Override
    public void executeConsoleCommand(String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    @Override
    public boolean anyActiveEvent() {
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.world.entity.raid.Raider
                        || entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                        || entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void runOnServer(Runnable task) {
        server.execute(task);
    }
}