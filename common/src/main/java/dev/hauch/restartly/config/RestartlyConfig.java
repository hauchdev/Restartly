package dev.hauch.restartly.config;

import dev.hauch.restartly.util.DurationParser;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the whole Restartly configuration.
 *
 * <p>Every section of {@code restartly.yml} maps onto one of the nested
 * records below. Instances are created by {@link ConfigLoader} and are never
 * mutated afterwards; a reload produces a brand new instance which is swapped
 * atomically by {@link ConfigManager}.</p>
 */
public record RestartlyConfig(
        int version,
        GeneralConfig general,
        List<ScheduleEntry> schedules,
        CountdownConfig countdown,
        List<WarningEntry> warnings,
        TitleConfig title,
        ActionbarConfig actionbar,
        BossbarConfig bossbar,
        ChatConfig chat,
        KickConfig kick,
        MaintenanceConfig maintenance,
        ConditionsConfig conditions,
        SmartRestartConfig smartRestart,
        CommandsConfig commands,
        IntegrationsConfig integrations,
        PermissionsConfig permissions,
        LoggingConfig logging
) {

    public static RestartlyConfig createDefault() {
        return ConfigLoader.parseDefaults();
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    public record GeneralConfig(
            boolean enabled,
            boolean debug,
            ZoneId timezone,
            String language,
            boolean checkUpdates,
            boolean metrics,
            String onError
    ) {
    }

    public record CountdownConfig(
            Duration defaultDuration,
            List<Duration> steps
    ) {
    }

    /**
     * One entry of the top level {@code schedule:} list. All fields are
     * optional except the time definition, which depends on the type.
     */
    public record ScheduleEntry(
            String id,
            ScheduleType type,
            String time,
            List<String> times,
            Map<String, List<String>> weekly,
            List<String> dates,
            Duration interval,
            String cron,
            String description,
            boolean enabled,
            int priority,
            ZoneId timezone,
            Duration countdown,
            List<WarningEntry> warnings,
            ConditionsConfig conditions,
            SmartRestartConfig smartRestart,
            MaintenanceConfig maintenance
    ) {

        public boolean usesDefaultCountdown() {
            return countdown == null;
        }
    }

    public enum ScheduleType {
        DAILY,
        WEEKLY,
        INTERVAL,
        DATES,
        CRON;

        public static ScheduleType parse(String raw) {
            for (ScheduleType type : values()) {
                if (type.name().equalsIgnoreCase(raw)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown schedule type: '" + raw + "'");
        }
    }

    /**
     * A single countdown warning; every action is optional so admins can
     * compose wildly different warnings ("chat only", "title + sound", ...).
     */
    public record WarningEntry(
            Duration time,
            ChatAction chat,
            TitleAction title,
            ActionbarAction actionbar,
            BossbarAction bossbar,
            SoundAction sound,
            List<String> commands
    ) {

        public static WarningEntry chatOnly(Duration time, String message) {
            return new WarningEntry(time, new ChatAction(true, message), null,
                    null, null, null, List.of());
        }

        public List<String> commandsOrEmpty() {
            return commands == null ? List.of() : commands;
        }
    }

    public record ChatAction(boolean enabled, String message) {
    }

    public record TitleAction(
            boolean enabled,
            String title,
            String subtitle,
            int fadeIn,
            int stay,
            int fadeOut
    ) {
    }

    public record ActionbarAction(boolean enabled, String message) {
    }

    public record BossbarAction(
            boolean enabled,
            String color,
            String overlay,
            String message
    ) {
    }

    public record SoundAction(boolean enabled, String sound, float volume, float pitch) {
    }

    public record TitleConfig(
            boolean enabled,
            int fadeIn,
            int stay,
            int fadeOut,
            String title,
            String subtitle
    ) {
    }

    public record ActionbarConfig(boolean enabled, String message, Duration updateInterval) {
    }

    public record BossbarConfig(
            boolean enabled,
            String color,
            String overlay,
            String message,
            String progressMode
    ) {
    }

    public record ChatConfig(String prefix, Map<String, String> messages) {

        public String message(String key, String fallback) {
            String configured = messages.get(key);
            return configured == null ? fallback : configured;
        }

        public String messageOrNull(String key) {
            return messages.get(key);
        }
    }

    public enum KickTiming {
        NONE,
        COUNTDOWN_START,
        MAINTENANCE_ACTIVATE,
        SHUTDOWN;

        public static KickTiming parse(String raw) {
            for (KickTiming timing : values()) {
                if (timing.name().equalsIgnoreCase(raw)) {
                    return timing;
                }
            }
            throw new IllegalArgumentException("Unknown kick timing: '" + raw + "'");
        }
    }

    public record KickConfig(
            boolean enabled,
            KickTiming when,
            Duration delay,
            String message
    ) {
    }

    public record MaintenanceConfig(
            boolean enabled,
            Duration activateBefore,
            boolean kickPlayers,
            String kickMessage,
            boolean blockJoin,
            boolean autoDisableOnRestart
    ) {
    }

    /**
     * Failure policy applied when restart conditions are not met.
     */
    public enum ConditionFailurePolicy {
        /** Restart anyway. */
        RESTART,
        /** Cancel the restart. */
        CANCEL,
        /** Wait (smart restart) until conditions are satisfied. */
        WAIT;

        public static ConditionFailurePolicy parse(String raw) {
            for (ConditionFailurePolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(raw)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("Unknown condition failure policy: '" + raw + "'");
        }
    }

    public record ConditionsConfig(
            int minPlayers,
            int maxPlayers,
            boolean requireEmpty,
            double requireTpsAbove,
            boolean requireNoCombat,
            boolean requireNoActiveEvent,
            Duration combatTimeout,
            ConditionFailurePolicy onFailure,
            int cancelIfPlayersAbove,
            boolean cancelIfEventActive,
            double cancelIfTpsBelow
    ) {
    }

    public enum MaxDelayAction {
        RESTART,
        CANCEL,
        FORCE;

        public static MaxDelayAction parse(String raw) {
            for (MaxDelayAction action : values()) {
                if (action.name().equalsIgnoreCase(raw)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("Unknown max delay action: '" + raw + "'");
        }
    }

    public record SmartRestartConfig(
            boolean enabled,
            boolean waitForEmpty,
            Duration maxDelay,
            Duration retryInterval,
            int minPlayers,
            int maxPlayers,
            MaxDelayAction maxDelayAction
    ) {
    }

    public record CommandsConfig(
            List<String> onRestartStart,
            List<String> onRestart,
            List<String> onCancel,
            List<String> onWaiting,
            Map<String, List<String>> byTime
    ) {

        public List<String> onRestartStartOrEmpty() {
            return onRestartStart == null ? List.of() : onRestartStart;
        }

        public List<String> onRestartOrEmpty() {
            return onRestart == null ? List.of() : onRestart;
        }

        public List<String> onCancelOrEmpty() {
            return onCancel == null ? List.of() : onCancel;
        }

        public List<String> onWaitingOrEmpty() {
            return onWaiting == null ? List.of() : onWaiting;
        }

        /**
         * Commands bound to a specific countdown step ("on_30m": [...]).
         */
        public List<String> forTime(String formattedStep) {
            List<String> commands = byTime.get(formattedStep);
            return commands == null ? List.of() : commands;
        }
    }

    public record WebhookConfig(
            boolean enabled,
            String url,
            int retries,
            Map<String, Boolean> events
    ) {

        public boolean wants(String event) {
            Boolean value = events.get(event);
            return value != null && value;
        }
    }

    public record IntegrationsConfig(WebhookConfig webhook) {
    }

    public record PermissionsConfig(Map<String, String> nodes) {

        public String node(String key, String fallback) {
            String configured = nodes.get(key);
            return configured == null ? fallback : configured;
        }
    }

    public record LoggingConfig(
            boolean logScheduleFires,
            boolean logWarningFires,
            boolean logWebhookResponses
    ) {
    }

    // ------------------------------------------------------------------
    // Convenience accessors used across the codebase
    // ------------------------------------------------------------------

    public ZoneId zone() {
        return general.timezone();
    }

    public Duration countdownFor(ScheduleEntry schedule) {
        if (schedule != null && schedule.countdown() != null) {
            return schedule.countdown();
        }
        return countdown.defaultDuration();
    }

    /**
     * Effective warnings for a schedule: per-schedule overrides when
     * present, otherwise the global list.
     */
    public List<WarningEntry> warningsFor(ScheduleEntry schedule) {
        if (schedule != null && schedule.warnings() != null && !schedule.warnings().isEmpty()) {
            return schedule.warnings();
        }
        return warnings;
    }

    /**
     * Effective conditions for a schedule (per-schedule override or the
     * global {@code conditions} section).
     */
    public ConditionsConfig conditionsFor(ScheduleEntry schedule) {
        if (schedule != null && schedule.conditions() != null) {
            return schedule.conditions();
        }
        return conditions;
    }

    /**
     * Effective smart restart configuration for a schedule.
     */
    public SmartRestartConfig smartRestartFor(ScheduleEntry schedule) {
        if (schedule != null && schedule.smartRestart() != null) {
            return schedule.smartRestart();
        }
        return smartRestart;
    }

    /**
     * Effective maintenance configuration for a schedule.
     */
    public MaintenanceConfig maintenanceFor(ScheduleEntry schedule) {
        if (schedule != null && schedule.maintenance() != null) {
            return schedule.maintenance();
        }
        return maintenance;
    }

    /**
     * Builds a modifiable map view for defaults expansion; kept out of
     * production paths.
     */
    public static Map<String, Object> toSection(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    public static List<String> asStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of(String.valueOf(raw));
    }

    public static Duration durationOrNull(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        Duration parsed = DurationParser.parseOrNull(String.valueOf(raw));
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid duration '" + raw + "' for " + field);
        }
        return parsed;
    }
}