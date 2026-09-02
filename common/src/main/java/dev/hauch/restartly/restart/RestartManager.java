package dev.hauch.restartly.restart;

import dev.hauch.restartly.Restartly;
import dev.hauch.restartly.api.PlaceholderContext;
import dev.hauch.restartly.api.RestartCheckContext;
import dev.hauch.restartly.api.RestartCondition;
import dev.hauch.restartly.api.RestartEvent;
import dev.hauch.restartly.condition.CombatTracker;
import dev.hauch.restartly.condition.ConditionEvaluator;
import dev.hauch.restartly.config.ConfigManager;
import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.event.EventBus;
import dev.hauch.restartly.maintenance.MaintenanceManager;
import dev.hauch.restartly.message.Messenger;
import dev.hauch.restartly.message.MinecraftText;
import dev.hauch.restartly.message.PlaceholderRegistry;
import dev.hauch.restartly.persistence.StateStore;
import dev.hauch.restartly.platform.RestartPlatform;
import dev.hauch.restartly.scheduler.RestartScheduler;
import dev.hauch.restartly.util.DurationParser;
import dev.hauch.restartly.warning.WarningManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The heart of Restartly: owns the restart lifecycle from scheduling through
 * countdown, warning steps, condition checks, smart-restart waiting and the
 * final shutdown sequence. All state transitions happen on the server thread
 * (the platform invokes {@link #tick()} every server tick); nothing here
 * sleeps or blocks.
 */
public final class RestartManager {

    private final RestartPlatform platform;
    private final ConfigManager configManager;
    private final PlaceholderRegistry placeholders;
    private final Messenger messenger;
    private final EventBus eventBus;
    private final MaintenanceManager maintenance;
    private final CombatTracker combatTracker;
    private final StateStore stateStore;
    private final WarningManager warningManager = new WarningManager();
    private final List<RestartCondition> extraConditions = new CopyOnWriteArrayList<>();

    private long lastSecondMillis = System.currentTimeMillis();
    private long lastActionbarMillis = 0;
    private final long startupMillis = System.currentTimeMillis();

    private volatile RestartState state = RestartState.IDLE;
    private RestartSession session;
    private Instant waitDeadline;
    private Instant nextRetryAt;
    private Instant maintenanceActivateAt;
    private boolean maintenanceActivated;
    private final Deque<TimedAction> queuedActions = new ArrayDeque<>();
    private String actionbarTemplate;

    /** Immutable per-session configuration snapshot. */
    public record RestartSession(
            RestartlyConfig.ScheduleEntry schedule,
            String reason,
            Instant countdownEnd,
            Duration totalCountdown,
            List<RestartlyConfig.WarningEntry> warnings,
            boolean force,
            RestartlyConfig snapshot
    ) {

        public String scheduleId() {
            return schedule == null ? null : schedule.id();
        }

        public boolean isScheduled() {
            return schedule != null;
        }
    }

    private record TimedAction(Instant deadline, Runnable action) {
    }

    public RestartManager(RestartPlatform platform, ConfigManager configManager,
                          PlaceholderRegistry placeholders, Messenger messenger,
                          EventBus eventBus, MaintenanceManager maintenance,
                          CombatTracker combatTracker, StateStore stateStore) {
        this.platform = platform;
        this.configManager = configManager;
        this.placeholders = placeholders;
        this.messenger = messenger;
        this.eventBus = eventBus;
        this.maintenance = maintenance;
        this.combatTracker = combatTracker;
        this.stateStore = stateStore;
    }

    // ------------------------------------------------------------------
    // Public state/status queries
    // ------------------------------------------------------------------

    public RestartState state() {
        return state;
    }

    public boolean isRestarting() {
        return state == RestartState.RESTARTING;
    }

    public boolean hasActiveSession() {
        return session != null;
    }

    public RestartSession session() {
        return session;
    }

    public Duration remaining() {
        if (session == null) {
            return Duration.ZERO;
        }
        long millis = session.countdownEnd().toEpochMilli() - System.currentTimeMillis();
        return millis <= 0 ? Duration.ZERO : Duration.ofMillis(millis);
    }

    public List<RestartCondition> extraConditions() {
        return extraConditions;
    }

    public void addCondition(RestartCondition condition) {
        extraConditions.add(condition);
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Manual restart, e.g. from {@code /restartly restart 10m}.
     *
     * @return {@code false} when a restart is already in progress
     */
    public boolean requestManualRestart(Duration countdown, String reason, boolean force) {
        if (state.isActive()) {
            Restartly.LOGGER.warn(
                    "[Restartly] Ignoring manual restart request: a restart session is already active.");
            return false;
        }
        RestartlyConfig config = configManager.get();
        Duration effective = countdown == null ? config.countdown().defaultDuration() : countdown;
        if (effective == null || effective.isZero() || effective.isNegative()) {
            Restartly.LOGGER.warn("[Restartly] Manual restart rejected: invalid countdown.");
            return false;
        }
        if (!config.general().enabled()) {
            Restartly.LOGGER.warn("[Restartly] Manual restart rejected: disabled in configuration.");
            return false;
        }
        Instant end = Instant.now().plus(effective);
        RestartSession newSession = new RestartSession(null, reason, end, effective,
                config.warningsFor(null), force, config);
        fireScheduled(newSession);
        startCountdown(newSession);
        return true;
    }

    /**
     * Entry point used by {@link RestartScheduler#poll} when a schedule is
     * due.
     */
    public void requestScheduledRestart(RestartScheduler.Fire fire) {
        if (state.isActive()) {
            Restartly.LOGGER.debug(
                    "[Restartly] Schedule '{}' fired while a session is active; ignored.",
                    fire.schedule().id());
            return;
        }
        RestartlyConfig config = configManager.get();
        if (!config.general().enabled()) {
            Restartly.LOGGER.debug("[Restartly] Restartly is disabled; schedule '{}' skipped.",
                    fire.schedule().id());
            return;
        }
        Duration countdown = config.countdownFor(fire.schedule());
        Instant end = fire.fireTime().plus(countdown);
        RestartSession newSession = new RestartSession(fire.schedule(),
                "scheduled:" + fire.schedule().id(), end, countdown,
                config.warningsFor(fire.schedule()), false, config);
        fireScheduled(newSession);
        startCountdown(newSession);
    }

    /**
     * Plans a restart in the future without starting its countdown yet; the
     * countdown begins automatically when the fire time is reached next tick
     * (used by the API's {@code scheduleRestartAt} feature).
     */
    public void planFuture(RestartSession planned) {
        if (state.isActive() || planned == null) {
            return;
        }
        fireScheduled(planned);
    }

    /**
     * Cancels the current session. Maintenance activated by the session is
     * also disabled.
     *
     * @return {@code true} when something was cancelled
     */
    public boolean cancelRestart(String reason) {
        if (session == null || state == RestartState.RESTARTING) {
            return false;
        }
        RestartlyConfig config = session.snapshot();
        cleanupCountdownUi();
        runCommands(config.commands().onCancelOrEmpty(), config);
        if (maintenanceActivated && config.maintenance().autoDisableOnRestart()) {
            maintenance.setActive(false, false, true, config);
            maintenanceActivated = false;
        }
        eventBus.fire(new RestartEvent.RestartCancelled(
                reason == null ? "manual" : reason, false));
        Restartly.LOGGER.info("[Restartly] Restart cancelled.");
        sendChat(config, config.chat().message("restart_cancelled", ""),
                context(Duration.ZERO));
        finishSession();
        return true;
    }

    // ------------------------------------------------------------------
    // Tick loop (invoked by the loader event bus on the server thread)
    // ------------------------------------------------------------------

    public void tick() {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastSecondMillis < 1_000) {
            return;
        }
        lastSecondMillis = nowMillis;

        runDueActions();

        switch (state) {
            case SCHEDULED -> tickScheduled();
            case COUNTDOWN -> tickCountdown(nowMillis);
            case WAITING -> tickWaiting(nowMillis);
            default -> { }
        }
    }

    private void tickScheduled() {
        if (session == null) {
            return;
        }
        Instant fireTime = session.countdownEnd().minus(session.totalCountdown());
        if (!Instant.now().isBefore(fireTime)) {
            startCountdown(session);
        }
    }

    private void tickCountdown(long nowMillis) {
        if (session == null) {
            return;
        }
        Instant now = Instant.now();

        if (maintenanceActivateAt != null && !maintenanceActivated
                && !now.isBefore(maintenanceActivateAt)) {
            activateMaintenance();
        }

        Duration remaining = Duration.between(now, session.countdownEnd());
        if (remaining.isNegative()) {
            finishCountdown();
            return;
        }

        for (WarningManager.Entry entry : warningManager.warningsReached(remaining)) {
            fireWarning(entry, remaining, session);
        }

        RestartlyConfig.ActionbarConfig actionbarCfg = session.snapshot().actionbar();
        if (actionbarTemplate != null && nowMillis - lastActionbarMillis
                >= actionbarCfg.updateInterval().toMillis()) {
            lastActionbarMillis = nowMillis;
            sendActionBar(actionbarTemplate, remaining);
        }

        if (messenger.isBossBarVisible() && session.totalCountdown().getSeconds() > 0) {
            float progress = (float) remaining.toMillis() / session.totalCountdown().toMillis();
            messenger.setBossBarProgress(progress);
        }
    }

    private void tickWaiting(long nowMillis) {
        if (session == null) {
            return;
        }
        RestartlyConfig config = session.snapshot();
        RestartlyConfig.SmartRestartConfig smart = config.smartRestartFor(session.schedule());
        Instant now = Instant.now();
        if (now.isBefore(nextRetryAt)) {
            return;
        }
        nextRetryAt = now.plus(smart.retryInterval());

        RestartCheckContext context = checkContext();
        if (ConditionEvaluator.shouldCancel(config.conditionsFor(session.schedule()), context)) {
            Restartly.LOGGER.info("[Restartly] Cancelling restart: cancel_if conditions met.");
            eventBus.fire(new RestartEvent.RestartCancelled("cancel_if", true));
            sendChat(config, config.chat().message("restart_cancelled", ""),
                    context(Duration.ZERO));
            finishSession();
            return;
        }

        if (evaluateCanProceed(session, context)) {
            Restartly.LOGGER.info("[Restartly] Conditions satisfied, restarting.");
            proceedRestart(false);
            return;
        }

        if (!now.isBefore(waitDeadline)) {
            Restartly.LOGGER.warn(
                    "[Restartly] Max delay reached and conditions are still unmet; applying "
                            + "max_delay_action '{}'.", smart.maxDelayAction());
            switch (smart.maxDelayAction()) {
                case CANCEL -> {
                    eventBus.fire(new RestartEvent.RestartCancelled("max_delay", true));
                    sendChat(config, config.chat().message("restart_cancelled", ""),
                            context(Duration.ZERO));
                    finishSession();
                }
                case RESTART -> proceedRestart(false);
                case FORCE -> proceedRestart(true);
            }
            return;
        }
        Restartly.LOGGER.debug("[Restartly] Waiting for conditions... ({} player(s) online)",
                platform.onlinePlayers().size());
    }

    // ------------------------------------------------------------------
    // Countdown lifecycle
    // ------------------------------------------------------------------

    private void fireScheduled(RestartSession session) {
        this.session = session;
        this.state = RestartState.SCHEDULED;
        Restartly.LOGGER.info("[Restartly] Restart planned: countdown {}, fire {}.",
                DurationParser.format(session.totalCountdown()),
                ZonedDateTime.ofInstant(
                        session.countdownEnd().minus(session.totalCountdown()),
                        effectiveZone(session)).toLocalTime());
        eventBus.fire(new RestartEvent.RestartScheduled(
                session.countdownEnd().minus(session.totalCountdown()),
                session.totalCountdown(), session.reason(), session.scheduleId()));
    }

    private void startCountdown(RestartSession session) {
        RestartlyConfig config = session.snapshot();
        this.state = RestartState.COUNTDOWN;

        warningManager.reset(session.warnings(), session.totalCountdown());
        cleanupCountdownUi();

        RestartlyConfig.MaintenanceConfig maintenanceCfg =
                config.maintenanceFor(session.schedule());
        maintenanceActivateAt = null;
        maintenanceActivated = false;
        if (maintenanceCfg.enabled() && !maintenanceCfg.activateBefore().isZero()) {
            maintenanceActivateAt = session.countdownEnd().minus(maintenanceCfg.activateBefore());
        }

        RestartlyConfig.KickConfig kick = config.kick();
        if (kick.enabled() && kick.when() == RestartlyConfig.KickTiming.COUNTDOWN_START) {
            kickAll(kick.message(), config);
        }

        runCommands(config.commands().onRestartStartOrEmpty(), config);

        sendChat(config, config.chat().message("restart_started", ""),
                context(session.totalCountdown()));
        Restartly.LOGGER.info("[Restartly] Restart countdown started: {}.",
                DurationParser.format(session.totalCountdown()));
        eventBus.fire(new RestartEvent.RestartStarted(
                session.totalCountdown(), session.reason(), session.scheduleId()));
        if (config.logging().logScheduleFires() && session.isScheduled()) {
            Restartly.LOGGER.info("[Restartly] Schedule '{}' fired.", session.scheduleId());
        }
    }

    private void finishCountdown() {
        RestartSession session = this.session;
        if (session == null) {
            return;
        }
        RestartlyConfig config = session.snapshot();
        RestartCheckContext context = checkContext();

        if (ConditionEvaluator.shouldCancel(config.conditionsFor(session.schedule()), context)) {
            Restartly.LOGGER.info("[Restartly] Cancelling restart: cancel_if conditions met.");
            eventBus.fire(new RestartEvent.RestartCancelled("cancel_if", true));
            sendChat(config, config.chat().message("restart_cancelled", ""),
                    context(Duration.ZERO));
            finishSession();
            return;
        }

        if (session.force()) {
            Restartly.LOGGER.info("[Restartly] Forced restart, skipping condition checks.");
            proceedRestart(true);
            return;
        }

        if (evaluateCanProceed(session, context)) {
            proceedRestart(false);
            return;
        }

        switch (config.conditionsFor(session.schedule()).onFailure()) {
            case WAIT -> beginWaiting(session, config);
            case RESTART -> {
                Restartly.LOGGER.warn("[Restartly] Conditions not met but on_failure=RESTART, "
                        + "restarting anyway.");
                proceedRestart(true);
            }
            case CANCEL -> {
                Restartly.LOGGER.info("[Restartly] Conditions not met, cancelling restart.");
                eventBus.fire(new RestartEvent.RestartCancelled("conditions", true));
                sendChat(config, config.chat().message("restart_cancelled", ""),
                        context(Duration.ZERO));
                finishSession();
            }
        }
    }

    private void beginWaiting(RestartSession session, RestartlyConfig config) {
        RestartlyConfig.SmartRestartConfig smart = config.smartRestartFor(session.schedule());
        this.state = RestartState.WAITING;
        this.waitDeadline = Instant.now().plus(smart.maxDelay());
        this.nextRetryAt = Instant.now().plus(smart.retryInterval());
        cleanupCountdownUi();
        eventBus.fire(new RestartEvent.RestartWaiting(smart.maxDelay(), session.reason()));
        Restartly.LOGGER.info("[Restartly] Waiting for the server to become restartable"
                + " (max {}).", DurationParser.format(smart.maxDelay()));
        runCommands(config.commands().onWaitingOrEmpty(), config);
        sendChat(config, config.chat().message("restart_waiting", ""), context(null));
    }

    private void activateMaintenance() {
        RestartlyConfig.MaintenanceConfig maintenanceCfg =
                configManager.get().maintenanceFor(session.schedule());
        boolean activated = maintenance.setActive(true, maintenanceCfg.kickPlayers(),
                true, session.snapshot());
        maintenanceActivated = activated;
        if (activated) {
            Restartly.LOGGER.info("[Restartly] Maintenance mode activated.");
            RestartlyConfig.KickConfig kick = session.snapshot().kick();
            if (kick.enabled() && kick.when() == RestartlyConfig.KickTiming.MAINTENANCE_ACTIVATE) {
                kickAll(kick.message(), session.snapshot());
            }
            eventBus.fire(new RestartEvent.MaintenanceChanged(true));
        }
    }

    // ------------------------------------------------------------------
    // Shutdown sequence
    // ------------------------------------------------------------------

    private void proceedRestart(boolean force) {
        RestartSession session = this.session;
        if (session == null) {
            return;
        }
        RestartlyConfig config = session.snapshot();
        this.state = RestartState.RESTARTING;

        Restartly.LOGGER.info("[Restartly] Restarting server (reason: {})...", session.reason());
        eventBus.fire(new RestartEvent.RestartCompleted(session.reason(), session.scheduleId()));

        if (stateStore != null) {
            stateStore.setLastRestart(Instant.now());
            stateStore.save();
        }

        if (maintenanceActivated && config.maintenance().autoDisableOnRestart()) {
            maintenance.setActive(false, false, false, config);
            maintenanceActivated = false;
        }

        runCommands(config.commands().onRestartOrEmpty(), config);
        platform.saveAll();

        RestartlyConfig.KickConfig kick = config.kick();
        if (kick.enabled() && kick.when() == RestartlyConfig.KickTiming.SHUTDOWN) {
            kickAll(kick.message(), config);
            long delayMillis = kick.delay().toMillis();
            queuedActions.add(new TimedAction(Instant.now().plusMillis(delayMillis),
                    () -> platform.stopServer(false)));
            Restartly.LOGGER.info("[Restartly] Players kicked, stopping server in {}.",
                    DurationParser.format(kick.delay()));
        } else {
            platform.stopServer(false);
        }
    }

    // ------------------------------------------------------------------
    // Warnings
    // ------------------------------------------------------------------

    private void fireWarning(WarningManager.Entry entry, Duration remaining,
                             RestartSession session) {
        RestartlyConfig config = session.snapshot();
        RestartlyConfig.WarningEntry warning = entry.warning();
        PlaceholderContext context = context(remaining);

        if (config.logging().logWarningFires()) {
            Restartly.LOGGER.info("[Restartly] Warning: {} until restart.",
                    DurationParser.format(remaining));
        }
        eventBus.fire(new RestartEvent.RestartWarning(remaining,
                DurationParser.format(entry.remaining())));

        if (warning.chat() != null && warning.chat().enabled()) {
            sendChat(config, warning.chat().message(), context);
        }

        RestartlyConfig.TitleConfig titleCfg = config.title();
        if (warning.title() != null && warning.title().enabled()) {
            String title = warning.title().title().isEmpty()
                    ? titleCfg.title() : warning.title().title();
            String subtitle = warning.title().subtitle().isEmpty()
                    ? titleCfg.subtitle() : warning.title().subtitle();
            messenger.sendTitleToAll(
                    placeholders.resolve(title, context),
                    placeholders.resolve(subtitle, context),
                    warning.title().fadeIn(), warning.title().stay(),
                    warning.title().fadeOut());
        }

        if (warning.actionbar() != null && warning.actionbar().enabled()) {
            actionbarTemplate = warning.actionbar().message();
            sendActionBar(actionbarTemplate, remaining);
        }

        if (warning.bossbar() != null && warning.bossbar().enabled()) {
            RestartlyConfig.BossbarConfig bossbarCfg = config.bossbar();
            String name = warning.bossbar().message() == null
                    ? bossbarCfg.message() : warning.bossbar().message();
            String color = warning.bossbar().color() == null
                    ? bossbarCfg.color() : warning.bossbar().color();
            String overlay = warning.bossbar().overlay() == null
                    ? bossbarCfg.overlay() : warning.bossbar().overlay();
            messenger.ensureBossBar(MinecraftText.parse(
                    placeholders.resolve(name, context)), color, overlay);
            messenger.setBossBarVisible(true);
        }

        if (warning.sound() != null && warning.sound().enabled()) {
            messenger.playSoundToAll(warning.sound().sound(),
                    warning.sound().volume(), warning.sound().pitch());
        }

        List<String> commands = new ArrayList<>(warning.commandsOrEmpty());
        commands.addAll(config.commands().forTime(
                DurationParser.format(entry.remaining())));
        if (!commands.isEmpty()) {
            runCommands(commands, config);
        }
    }

    private void sendActionBar(String template, Duration remaining) {
        messenger.sendActionBarToAll(MinecraftText.parse(
                placeholders.resolve(template, context(remaining))));
    }

    // ------------------------------------------------------------------
    // Condition evaluation
    // ------------------------------------------------------------------

    private boolean evaluateCanProceed(RestartSession session, RestartCheckContext context) {
        RestartlyConfig config = session.snapshot();
        RestartlyConfig.ConditionsConfig conditions =
                config.conditionsFor(session.schedule());
        ConditionEvaluator.Result result = ConditionEvaluator.evaluate(
                conditions, context, extraConditions);
        if (!result.satisfied()) {
            Restartly.LOGGER.debug("[Restartly] Conditions not met: {}",
                    String.join(", ", result.failures()));
        }
        return result.satisfied();
    }

    private RestartCheckContext checkContext() {
        List<UUID> uuids = new ArrayList<>();
        for (ServerPlayer player : platform.onlinePlayers()) {
            uuids.add(player.getUUID());
        }
        return new RestartCheckContext(
                platform.onlinePlayers().size(),
                platform.maxPlayers(),
                platform.currentTps(),
                combatTracker.anyInCombat(uuids),
                platform.anyActiveEvent());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void sendChat(RestartlyConfig config, String template, PlaceholderContext context) {
        if (template == null || template.isEmpty()) {
            return;
        }
        String resolved = placeholders.resolve(
                template.replace("{prefix}", config.chat().prefix()), context);
        platform.broadcast(MinecraftText.parse(resolved));
    }

    private PlaceholderContext context(Duration remaining) {
        int online = platform.onlinePlayers().size();
        return PlaceholderContext.builder()
                .remaining(remaining)
                .onlinePlayers(online)
                .maxPlayers(platform.maxPlayers())
                .serverVersion(platform.serverVersion())
                .reason(session == null ? null : session.reason())
                .scheduleId(session == null ? null : session.scheduleId())
                .timezone(session == null ? configManager.get().zone() : effectiveZone(session))
                .state(state.name())
                .uptime(Duration.ofMillis(System.currentTimeMillis() - startupMillis))
                .now(Instant.now())
                .build();
    }

    private ZoneId effectiveZone(RestartSession session) {
        if (session.schedule() != null && session.schedule().timezone() != null) {
            return session.schedule().timezone();
        }
        return session.snapshot().zone();
    }

    private void runCommands(List<String> commands, RestartlyConfig config) {
        for (String command : commands) {
            String trimmed = command == null ? "" : command.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            Restartly.LOGGER.debug("[Restartly] Executing console command: {}", trimmed);
            try {
                platform.executeConsoleCommand(trimmed);
            } catch (Exception e) {
                Restartly.LOGGER.error("[Restartly] Failed to execute command '{}': {}",
                        trimmed, e.getMessage());
            }
        }
    }

    private void kickAll(String message, RestartlyConfig config) {
        Component reason = MinecraftText.parse(message);
        for (ServerPlayer player : platform.onlinePlayers()) {
            platform.disconnect(player, reason);
        }
    }

    private void runDueActions() {
        Instant now = Instant.now();
        while (!queuedActions.isEmpty()) {
            TimedAction action = queuedActions.peek();
            if (action.deadline().isAfter(now)) {
                return;
            }
            queuedActions.remove();
            try {
                action.action().run();
            } catch (Exception e) {
                Restartly.LOGGER.error("[Restartly] Timed action failed: {}", e.getMessage());
            }
        }
    }

    private void cleanupCountdownUi() {
        messenger.hideBossBar();
        messenger.clearTitleToAll();
        actionbarTemplate = null;
    }

    private void finishSession() {
        cleanupCountdownUi();
        session = null;
        state = RestartState.IDLE;
        waitDeadline = null;
        nextRetryAt = null;
        maintenanceActivateAt = null;
        maintenanceActivated = false;
        queuedActions.clear();
    }
}