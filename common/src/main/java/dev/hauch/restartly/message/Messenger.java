package dev.hauch.restartly.message;

import dev.hauch.restartly.Restartly;
import dev.hauch.restartly.platform.RestartPlatform;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;

/**
 * Renders countdown messages to players: chat, times (title), action bar,
 * sounds and the boss bar. All packet classes used here exist on 1.20.1;
 * the boss bar lifecycle is owned by this class so it can always be cleaned
 * up when a restart is cancelled, completed or interrupted.
 */
public final class Messenger {

    private final RestartPlatform platform;

    private ServerBossEvent bossBar;
    private boolean bossBarVisible;

    public Messenger(RestartPlatform platform) {
        this.platform = platform;
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    public void broadcastChat(Component message) {
        platform.broadcast(message);
    }

    public void sendChat(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    // ------------------------------------------------------------------
    // Titles / action bar
    // ------------------------------------------------------------------

    public void sendTitle(ServerPlayer player, String title, String subtitle,
                          int fadeIn, int stay, int fadeOut) {
        var connection = player.connection;
        if (connection == null) {
            return;
        }
        connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        if (title != null && !title.isEmpty()) {
            connection.send(new ClientboundSetTitleTextPacket(MinecraftText.parse(title)));
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            connection.send(new ClientboundSetSubtitleTextPacket(MinecraftText.parse(subtitle)));
        }
    }

    public void sendTitleToAll(String title, String subtitle,
                               int fadeIn, int stay, int fadeOut) {
        for (ServerPlayer player : platform.onlinePlayers()) {
            sendTitle(player, title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    public void sendActionBar(ServerPlayer player, Component message) {
        var connection = player.connection;
        if (connection != null) {
            connection.send(new ClientboundSetActionBarTextPacket(message));
        }
    }

    public void sendActionBarToAll(Component message) {
        for (ServerPlayer player : platform.onlinePlayers()) {
            sendActionBar(player, message);
        }
    }

    public void clearTitleToAll() {
        var packet = new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true);
        for (ServerPlayer player : platform.onlinePlayers()) {
            var connection = player.connection;
            if (connection != null) {
                connection.send(packet);
            }
        }
    }

    // ------------------------------------------------------------------
    // Sounds
    // ------------------------------------------------------------------

    /**
     * Resolves a sound id like {@code minecraft:block.note_block.bell}.
     * Returns {@code null} for unknown ids (invalid configs are logged once
     * and skipped).
     */
    public static Holder<SoundEvent> parseSound(String id) {
        try {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                return null;
            }
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(location);
            return sound == null ? null : new Holder.Direct<>(sound);
        } catch (Exception e) {
            return null;
        }
    }

    public void playSoundToAll(String id, float volume, float pitch) {
        Holder<SoundEvent> sound = parseSound(id);
        if (sound == null) {
            Restartly.LOGGER.warn("[Restartly] Unknown sound id '{}' in configuration.", id);
            return;
        }
        for (ServerPlayer player : platform.onlinePlayers()) {
            var connection = player.connection;
            if (connection != null) {
                connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER,
                        player.getX(), player.getY(), player.getZ(), volume, pitch,
                        player.level().getRandom().nextLong()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Boss bar
    // ------------------------------------------------------------------

    private static final BossEvent.BossBarColor[] COLORS =
            BossEvent.BossBarColor.values();
    private static final BossEvent.BossBarOverlay[] OVERLAYS =
            BossEvent.BossBarOverlay.values();

    public static BossEvent.BossBarColor parseColor(String raw) {
        if (raw == null) {
            return BossEvent.BossBarColor.RED;
        }
        for (BossEvent.BossBarColor color : COLORS) {
            if (color.name().equalsIgnoreCase(raw)) {
                return color;
            }
        }
        Restartly.LOGGER.warn("[Restartly] Unknown bossbar color '{}', using RED.", raw);
        return BossEvent.BossBarColor.RED;
    }

    public static BossEvent.BossBarOverlay parseOverlay(String raw) {
        if (raw == null) {
            return BossEvent.BossBarOverlay.PROGRESS;
        }
        for (BossEvent.BossBarOverlay overlay : OVERLAYS) {
            if (overlay.name().equalsIgnoreCase(raw)) {
                return overlay;
            }
        }
        Restartly.LOGGER.warn("[Restartly] Unknown bossbar overlay '{}', using PROGRESS.", raw);
        return BossEvent.BossBarOverlay.PROGRESS;
    }

    public void ensureBossBar(Component name, String color, String overlay) {
        if (bossBar == null) {
            bossBar = new ServerBossEvent(name, parseColor(color), parseOverlay(overlay));
        } else {
            bossBar.setName(name);
            bossBar.setColor(parseColor(color));
            bossBar.setOverlay(parseOverlay(overlay));
        }
    }

    public void setBossBarVisible(boolean visible) {
        if (bossBar == null || bossBarVisible == visible) {
            return;
        }
        bossBarVisible = visible;
        if (visible) {
            for (ServerPlayer player : platform.onlinePlayers()) {
                bossBar.addPlayer(player);
            }
        } else {
            bossBar.removeAllPlayers();
        }
        bossBar.setVisible(visible);
    }

    public void setBossBarProgress(float progress) {
        if (bossBar != null) {
            bossBar.setProgress(Math.max(0.0f, Math.min(1.0f, progress)));
        }
    }

    public void setBossBarName(Component name) {
        if (bossBar != null) {
            bossBar.setName(name);
        }
    }

    /**
     * Adds/removes players from the boss bar when they join/leave during an
     * active countdown.
     */
    public void onPlayerJoin(ServerPlayer player) {
        if (bossBarVisible && bossBar != null) {
            bossBar.addPlayer(player);
        }
    }

    public void onPlayerQuit(ServerPlayer player) {
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    public boolean isBossBarVisible() {
        return bossBarVisible;
    }

    /**
     * Hides and tears down the boss bar (cancel, completion, state change).
     */
    public void hideBossBar() {
        if (bossBar == null) {
            return;
        }
        bossBarVisible = false;
        bossBar.removeAllPlayers();
        bossBar.setVisible(false);
        bossBar = null;
    }
}