package dev.hauch.restartly;

import dev.hauch.restartly.command.RestartlyCommands;
import dev.hauch.restartly.core.RestartlyCore;
import dev.hauch.restartly.platform.FabricRestartPlatform;
import dev.hauch.restartly.platform.PlatformProvider;
import dev.hauch.restartly.util.TpsTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Fabric entry point. Registers every loader-specific hook and translates
 * Fabric events into {@link RestartlyCore} calls. Server-side only — there
 * is deliberately no client initializer.
 */
public class RestartlyFabric implements ModInitializer {

    private static RestartlyCore core;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TpsTracker tpsTracker = new TpsTracker();
            FabricRestartPlatform platform = new FabricRestartPlatform(server, tpsTracker);
            PlatformProvider.initialize(platform);
            Path configDir = FabricLoader.getInstance().getConfigDir()
                    .resolve(Restartly.MOD_ID);
            core = RestartlyCore.start(platform, configDir, tpsTracker);
            core.onServerStarted();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (core != null) {
                core.onServerStopping();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (core != null) {
                core.onTick();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess,
                                                    environment) -> {
            if (core != null) {
                RestartlyCommands.register(dispatcher, core);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (core != null) {
                core.onPlayerJoin(handler.getPlayer());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (core != null) {
                core.onPlayerQuit(handler.getPlayer());
            }
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (core == null) {
                return true;
            }
            if (entity instanceof ServerPlayer victim) {
                core.onPlayerHurt(victim);
            }
            if (source.getEntity() instanceof ServerPlayer attacker) {
                core.onPlayerHurt(attacker);
            }
            return true;
        });

        Restartly.LOGGER.info("{} initialized on Fabric.", Restartly.MOD_NAME);
    }
}