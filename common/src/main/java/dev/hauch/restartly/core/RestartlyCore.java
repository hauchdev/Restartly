package dev.hauch.restartly.core;

import dev.hauch.restartly.Restartly;
import dev.hauch.restartly.api.RestartlyAPI;
import dev.hauch.restartly.condition.CombatTracker;
import dev.hauch.restartly.config.ConfigManager;
import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.event.EventBus;
import dev.hauch.restartly.integration.IntegrationManager;
import dev.hauch.restartly.integration.webhook.WebhookIntegration;
import dev.hauch.restartly.maintenance.MaintenanceManager;
import dev.hauch.restartly.message.Messenger;
import dev.hauch.restartly.message.PlaceholderRegistry;
import dev.hauch.restartly.persistence.StateStore;
import dev.hauch.restartly.platform.RestartPlatform;
import dev.hauch.restartly.restart.RestartManager;
import dev.hauch.restartly.restart.RestartState;
import dev.hauch.restartly.scheduler.RestartScheduler;
import dev.hauch.restartly.updates.UpdateChecker;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Composition root. Wires every subsystem together once per server start,
 * keeps references for the loaders to call back into and the API to expose.
 */
public final class RestartlyCore {

    private static final String STATE_FILE = "state.yml";

    private final RestartPlatform platform;
    private final Path configDir;
    private final dev.hauch.restartly.util.TpsTracker tpsTracker;

    private ConfigManager configManager;
    private StateStore stateStore;
    private PlaceholderRegistry placeholders;
    private EventBus eventBus;
    private Messenger messenger;
    private MaintenanceManager maintenance;
    private CombatTracker combatTracker;
    private RestartScheduler scheduler;
    private RestartManager restartManager;
    private IntegrationManager integrations;
    private UpdateChecker updateChecker;

    private volatile boolean started;
    private volatile boolean debugMode;

    private RestartlyCore(RestartPlatform platform, Path configDir,
                          dev.hauch.restartly.util.TpsTracker tpsTracker) {
        this.platform = platform;
        this.configDir = configDir;
        this.tpsTracker = tpsTracker;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static RestartlyCore start(RestartPlatform platform, Path configDir,
                                      dev.hauch.restartly.util.TpsTracker tpsTracker) {
        RestartlyCore core = new RestartlyCore(platform, configDir, tpsTracker);
        core.build();
        RestartlyAPI.bind(core);
        return core;
    }

    private void build() {
        configManager = new ConfigManager(configDir);
        stateStore = new StateStore(configDir);
        placeholders = new PlaceholderRegistry();
        eventBus = new EventBus();
        messenger = new Messenger(platform);
        combatTracker = CombatTracker.create();
        maintenance = new MaintenanceManager(stateStore, platform, placeholders);
        scheduler = new RestartScheduler(stateStore::lastRestart);
        restartManager = new RestartManager(platform, configManager, placeholders,
                messenger, eventBus, maintenance, combatTracker, stateStore);
        integrations = new IntegrationManager();
        updateChecker = new UpdateChecker();

        integrations.register(new WebhookIntegration(eventBus, this));
    }

    /**
     * Called by the loader once the server has started.
     */
    public void onServerStarted() {
        if (started) {
            return;
        }
        started = true;
        try {
            configManager.initialize();
        } catch (Exception e) {
            Restartly.LOGGER.error("[Restartly] Could not initialize configuration: {}",
                    e.getMessage());
            configManager = new ConfigManager(configDir);
            try {
                configManager.initialize();
            } catch (Exception retryFailure) {
                Restartly.LOGGER.error(
                        "[Restartly] Configuration is unusable ({}); using safe defaults.",
                        retryFailure.getMessage());
            }
        }
        stateStore.load();
        RestartlyConfig config = configManager.get();

        if (!config.general().enabled()) {
            Restartly.LOGGER.warn("[Restartly] Disabled by configuration; schedules will not fire.");
        }
        maintenance.configure(config.maintenance());
        maintenance.restore();
        scheduler.setSchedules(config.schedules(), config.zone());
        integrations.configure(config.integrations(), configManager);
        integrations.start();

        if (config.general().checkUpdates()) {
            updateChecker.checkAsync(Restartly.VERSION);
        }

        Restartly.LOGGER.info("[Restartly] Loaded {} restart schedule(s). Next restart: {}.",
                config.schedules().size(), scheduler.describeNext(config.zone()));
    }

    /**
     * Called by the loader while the server is stopping.
     */
    public void onServerStopping() {
        integrations.shutdown();
        started = false;
    }

    /**
     * Called every server tick by the loader.
     */
    public void onTick() {
        if (!started) {
            return;
        }
        tpsTracker.recordTick();
        RestartScheduler.Fire fire = scheduler.poll(Instant.now());
        if (fire != null) {
            restartManager.requestScheduledRestart(fire);
        }
        restartManager.tick();
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!started) {
            return;
        }
        RestartlyConfig config = configManager.get();
        String kickReason = maintenance.joinCheck(player, config);
        if (kickReason != null) {
            platform.disconnect(player,
                    dev.hauch.restartly.message.MinecraftText.parse(kickReason));
            return;
        }
        messenger.onPlayerJoin(player);
    }

    public void onPlayerQuit(ServerPlayer player) {
        messenger.onPlayerQuit(player);
    }

    public void onPlayerHurt(ServerPlayer player) {
        if (!started) {
            return;
        }
        RestartlyConfig.ConditionsConfig conditions = configManager.get().conditions();
        long timeoutMillis = conditions.combatTimeout() == null
                ? 30_000 : conditions.combatTimeout().toMillis();
        combatTracker.flag(player.getUUID(), timeoutMillis);
    }

    // ------------------------------------------------------------------
    // Operations (commands + API)
    // ------------------------------------------------------------------

    /**
     * Reloads the configuration. Returns {@code null} on success or the list
     * of concrete problems when the reload was rejected.
     */
    public List<String> reload() {
        List<String> problems = configManager.reload();
        if (problems != null) {
            return problems;
        }
        RestartlyConfig config = configManager.get();
        maintenance.configure(config.maintenance());
        scheduler.setSchedules(config.schedules(), config.zone());
        integrations.configure(config.integrations(), configManager);
        return null;
    }

    public boolean scheduleRestart(Duration countdown, String reason, boolean force,
                                  boolean planOnly) {
        if (planOnly) {
            return planFutureRestart(Instant.now().plus(countdown), reason, countdown);
        }
        return restartManager.requestManualRestart(countdown, reason, force);
    }

    public boolean planFutureRestart(Instant fireTime, String reason, Duration countdown) {
        if (restartManager.state().isActive()) {
            return false;
        }
        RestartlyConfig config = configManager.get();
        Duration effective = countdown == null ? config.countdown().defaultDuration() : countdown;
        Instant end = fireTime.plus(effective);
        RestartManager.RestartSession session = new RestartManager.RestartSession(
                null, reason, end, effective, config.warningsFor(null), false, config);
        restartManager.planFuture(session);
        return true;
    }

    public boolean cancelRestart(String reason) {
        return restartManager.cancelRestart(reason);
    }

    public RestartState state() {
        return restartManager.state();
    }

    public Duration remaining() {
        return restartManager.remaining();
    }

    public RestartManager.RestartSession session() {
        return restartManager.session();
    }

    public boolean maintenanceActive() {
        return maintenance.isActive();
    }

    public boolean setMaintenance(boolean active) {
        RestartlyConfig config = configManager.get();
        return maintenance.setActive(active, false, true, config);
    }

    public Optional<Instant> nextRestart() {
        return scheduler.nearest(Instant.now())
                .map(RestartScheduler.Fire::fireTime);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public RestartPlatform platform() {
        return platform;
    }

    public double currentTps() {
        return tpsTracker.currentTps();
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public RestartScheduler scheduler() {
        return scheduler;
    }

    public RestartManager restartManager() {
        return restartManager;
    }

    public PlaceholderRegistry placeholders() {
        return placeholders;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public MaintenanceManager maintenance() {
        return maintenance;
    }

    public boolean debugMode() {
        return debugMode;
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        Restartly.LOGGER.info("[Restartly] Debug mode {}.", debugMode ? "enabled" : "disabled");
    }
}