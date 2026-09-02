package dev.hauch.restartly;

import dev.hauch.restartly.command.RestartlyCommands;
import dev.hauch.restartly.core.RestartlyCore;
import dev.hauch.restartly.platform.ForgeRestartPlatform;
import dev.hauch.restartly.platform.PlatformProvider;
import dev.hauch.restartly.util.TpsTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge entry point. Server-side only; the {@code side = "SERVER"} metadata
 * in {@code mods.toml} prevents it from loading on clients.
 */
@Mod(Restartly.MOD_ID)
public class RestartlyForge {

    private static RestartlyCore core;

    public RestartlyForge() {
        MinecraftForge.EVENT_BUS.register(this);
        Restartly.LOGGER.info("{} initialized on Forge.", Restartly.MOD_NAME);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        TpsTracker tpsTracker = new TpsTracker();
        ForgeRestartPlatform platform = new ForgeRestartPlatform(event.getServer(), tpsTracker);
        PlatformProvider.initialize(platform);
        core = RestartlyCore.start(platform,
                FMLPaths.CONFIGDIR.get().resolve(Restartly.MOD_ID), tpsTracker);
        core.onServerStarted();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (core != null) {
            core.onServerStopping();
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || core == null) {
            return;
        }
        core.onTick();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        if (core != null) {
            RestartlyCommands.register(event.getDispatcher(), core);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (core != null && event.getEntity() instanceof ServerPlayer player) {
            core.onPlayerJoin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (core != null && event.getEntity() instanceof ServerPlayer player) {
            core.onPlayerQuit(player);
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (core == null) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer victim) {
            core.onPlayerHurt(victim);
        }
        if (event.getSource().getEntity() instanceof ServerPlayer attacker) {
            core.onPlayerHurt(attacker);
        }
    }
}