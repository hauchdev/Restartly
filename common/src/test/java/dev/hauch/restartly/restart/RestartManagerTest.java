package dev.hauch.restartly.restart;

import dev.hauch.restartly.condition.CombatTracker;
import dev.hauch.restartly.config.ConfigManager;
import dev.hauch.restartly.config.ConfigLoader;
import dev.hauch.restartly.event.EventBus;
import dev.hauch.restartly.maintenance.MaintenanceManager;
import dev.hauch.restartly.message.Messenger;
import dev.hauch.restartly.message.PlaceholderRegistry;
import dev.hauch.restartly.persistence.StateStore;
import dev.hauch.restartly.platform.RestartPlatform;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartManagerTest {

    /** Platform stub; the state machine must not depend on a live server. */
    private static final class FakePlatform implements RestartPlatform {
        @Override
        public String loaderName() {
            return "Test";
        }

        @Override
        public String serverVersion() {
            return "1.20.1";
        }

        @Override
        public MinecraftServer server() {
            return null;
        }

        @Override
        public List<ServerPlayer> onlinePlayers() {
            return List.of();
        }

        @Override
        public int maxPlayers() {
            return 100;
        }

        @Override
        public double currentTps() {
            return 20.0;
        }

        @Override
        public boolean isDedicatedServer() {
            return true;
        }

        @Override
        public boolean hasPermission(ServerPlayer player, String node, int fallbackOpLevel) {
            return true;
        }

        @Override
        public void broadcast(Component message) {
        }

        @Override
        public void saveAll() {
        }

        @Override
        public void stopServer(boolean force) {
        }

        @Override
        public void disconnect(ServerPlayer player, Component reason) {
        }

        @Override
        public void executeConsoleCommand(String command) {
        }

        @Override
        public boolean anyActiveEvent() {
            return false;
        }

        @Override
        public void runOnServer(Runnable task) {
        }
    }

    @TempDir
    Path tempDir;

    private RestartManager manager() throws Exception {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.initialize();
        StateStore stateStore = new StateStore(tempDir);
        PlaceholderRegistry placeholders = new PlaceholderRegistry();
        EventBus eventBus = new EventBus();
        FakePlatform platform = new FakePlatform();
        Messenger messenger = new Messenger(platform);
        CombatTracker combatTracker = CombatTracker.create();
        MaintenanceManager maintenance =
                new MaintenanceManager(stateStore, platform, placeholders);
        return new RestartManager(platform, configManager, placeholders, messenger,
                eventBus, maintenance, combatTracker, stateStore);
    }

    @Test
    void manualRestartStartsAndCancelsCountdown() throws Exception {
        RestartManager manager = manager();
        assertTrue(manager.requestManualRestart(Duration.ofMinutes(5), "test", false));
        assertEquals(RestartState.COUNTDOWN, manager.state());
        assertTrue(manager.hasActiveSession());
        assertTrue(manager.remaining().getSeconds() > 0);

        assertTrue(manager.cancelRestart("done"));
        assertEquals(RestartState.IDLE, manager.state());
        assertFalse(manager.hasActiveSession());
    }

    @Test
    void secondRestartIsRejectedWhileActive() throws Exception {
        RestartManager manager = manager();
        assertTrue(manager.requestManualRestart(Duration.ofMinutes(5), "first", false));
        assertFalse(manager.requestManualRestart(Duration.ofMinutes(1), "second", false));
    }

    @Test
    void invalidCountdownIsRejected() throws Exception {
        RestartManager manager = manager();
        assertFalse(manager.requestManualRestart(Duration.ZERO, "zero", false));
        assertFalse(manager.requestManualRestart(Duration.ofSeconds(-5), "negative", false));
    }

    @Test
    void disabledGeneralRejectsManualRestart() throws Exception {
        String disabled = """
                version: 1
                general:
                  enabled: false
                countdown:
                  default: "10m"
                """;
        Files.writeString(tempDir.resolve(ConfigLoader.DEFAULT_FILE_NAME),
                disabled, StandardCharsets.UTF_8);
        RestartManager manager = manager();
        assertFalse(manager.requestManualRestart(Duration.ofMinutes(5), "off", false));
        assertEquals(RestartState.IDLE, manager.state());
    }
}