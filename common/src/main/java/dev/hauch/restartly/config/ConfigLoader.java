package dev.hauch.restartly.config;

import dev.hauch.restartly.Restartly;
import dev.hauch.restartly.config.RestartlyConfig.ActionbarAction;
import dev.hauch.restartly.config.RestartlyConfig.ActionbarConfig;
import dev.hauch.restartly.config.RestartlyConfig.BossbarAction;
import dev.hauch.restartly.config.RestartlyConfig.BossbarConfig;
import dev.hauch.restartly.config.RestartlyConfig.ChatAction;
import dev.hauch.restartly.config.RestartlyConfig.ChatConfig;
import dev.hauch.restartly.config.RestartlyConfig.CommandsConfig;
import dev.hauch.restartly.config.RestartlyConfig.ConditionFailurePolicy;
import dev.hauch.restartly.config.RestartlyConfig.ConditionsConfig;
import dev.hauch.restartly.config.RestartlyConfig.CountdownConfig;
import dev.hauch.restartly.config.RestartlyConfig.GeneralConfig;
import dev.hauch.restartly.config.RestartlyConfig.IntegrationsConfig;
import dev.hauch.restartly.config.RestartlyConfig.KickConfig;
import dev.hauch.restartly.config.RestartlyConfig.KickTiming;
import dev.hauch.restartly.config.RestartlyConfig.LoggingConfig;
import dev.hauch.restartly.config.RestartlyConfig.MaintenanceConfig;
import dev.hauch.restartly.config.RestartlyConfig.MaxDelayAction;
import dev.hauch.restartly.config.RestartlyConfig.PermissionsConfig;
import dev.hauch.restartly.config.RestartlyConfig.ScheduleEntry;
import dev.hauch.restartly.config.RestartlyConfig.ScheduleType;
import dev.hauch.restartly.config.RestartlyConfig.SmartRestartConfig;
import dev.hauch.restartly.config.RestartlyConfig.SoundAction;
import dev.hauch.restartly.config.RestartlyConfig.TitleAction;
import dev.hauch.restartly.config.RestartlyConfig.TitleConfig;
import dev.hauch.restartly.config.RestartlyConfig.WarningEntry;
import dev.hauch.restartly.config.RestartlyConfig.WebhookConfig;
import dev.hauch.restartly.util.DurationParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the raw {@code Map} produced by SnakeYAML into a validated
 * {@link RestartlyConfig}. Every problem found is collected and reported as
 * one {@link ConfigException} with an entry per field, so invalid files
 * never leave Restartly half-configured.
 */
public final class ConfigLoader {

    public static final int CURRENT_VERSION = 1;
    public static final String DEFAULT_FILE_NAME = "restartly.yml";

    private static final Set<String> KNOWN_MESSAGES = Set.of(
            "restart_started", "restart_cancelled", "restart_now",
            "restart_waiting", "restart_scheduled", "next_restart",
            "maintenance_active", "maintenance_disabled", "reload_success",
            "reload_failed", "no_restart_scheduled", "restart_completed",
            "invalid_subcommand", "no_permission"
    );

    private final List<String> problems = new ArrayList<>();

    private ConfigLoader() {
    }

    public static RestartlyConfig parse(String yamlText) {
        ConfigLoader loader = new ConfigLoader();
        Object raw = loader.loadDocument(yamlText);
        return loader.build(raw);
    }

    public static RestartlyConfig parseDefaults() {
        try (InputStream stream = ConfigLoader.class.getResourceAsStream(
                "/data/restartly/restartly.yml")) {
            if (stream == null) {
                Restartly.LOGGER.warn(
                        "Bundled default configuration is missing, using hardcoded defaults.");
                return new ConfigLoader().build(loader().load(
                        "# empty\nversion: " + CURRENT_VERSION + "\n"));
            }
            return parse(new String(stream.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException("Failed to read bundled default configuration: " + e.getMessage());
        }
    }

    static Yaml loader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    private Object loadDocument(String yamlText) {
        try {
            return loader().load(yamlText);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new ConfigException("Configuration is not valid YAML: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private RestartlyConfig build(Object raw) {
        if (raw == null) {
            raw = Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ConfigException(
                    "Configuration root must be a YAML mapping, got " + raw.getClass().getSimpleName());
        }
        Map<String, Object> root = (Map<String, Object>) map;

        int version = intValue(root, "version", CURRENT_VERSION);

        GeneralConfig general = parseGeneral(section(root, "general"));
        CountdownConfig countdown = parseCountdown(section(root, "countdown"));
        List<ScheduleEntry> schedules = parseSchedules(listOfMaps(root.get("schedule")), "schedule");
        List<WarningEntry> warnings = parseWarnings(listOfMaps(root.get("warnings")), "warnings");
        TitleConfig title = parseTitle(section(root, "title"));
        ActionbarConfig actionbar = parseActionbar(section(root, "actionbar"));
        BossbarConfig bossbar = parseBossbar(section(root, "bossbar"));
        ChatConfig chat = parseChat(section(root, "chat"));
        KickConfig kick = parseKick(section(root, "kick"));
        MaintenanceConfig maintenance = parseMaintenance(section(root, "maintenance"));
        ConditionsConfig conditions = parseConditions(section(root, "conditions"));
        SmartRestartConfig smartRestart = parseSmartRestart(section(root, "smart_restart"));
        CommandsConfig commands = parseCommands(section(root, "commands"));
        IntegrationsConfig integrations = parseIntegrations(section(root, "integrations"));
        PermissionsConfig permissions = parsePermissions(section(root, "permissions"));
        LoggingConfig logging = parseLogging(section(root, "logging"));

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }

        if (!schedules.isEmpty() && countdown.defaultDuration() == null) {
            problems.add("countdown.default is required when schedules are configured");
            throw new ConfigException(problems);
        }

        return new RestartlyConfig(
                version, general, schedules, countdown, warnings, title, actionbar,
                bossbar, chat, kick, maintenance, conditions, smartRestart,
                commands, integrations, permissions, logging);
    }

    // ------------------------------------------------------------------
    // Section parsers
    // ------------------------------------------------------------------

    private GeneralConfig parseGeneral(Map<String, Object> s) {
        ZoneId zone;
        try {
            zone = ZoneId.of(str(s, "timezone", "UTC"));
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
            problems.add("general.timezone is not a valid zone id: '"
                    + str(s, "timezone", "") + "'");
        }
        return new GeneralConfig(
                bool(s, "enabled", true),
                bool(s, "debug", false),
                zone,
                str(s, "language", "en_US"),
                bool(s, "check_updates", true),
                bool(s, "metrics", false),
                str(s, "on_error", "log"));
    }

    private CountdownConfig parseCountdown(Map<String, Object> s) {
        Duration defaultDuration;
        try {
            defaultDuration = DurationParser.parse(
                    s.get("default") == null ? "10m" : String.valueOf(s.get("default")));
        } catch (IllegalArgumentException e) {
            defaultDuration = Duration.ofMinutes(10);
            problems.add("countdown.default is invalid: " + e.getMessage());
        }
        List<Duration> steps = new ArrayList<>();
        for (Object rawStep : asList(s.get("steps"))) {
            Duration step = DurationParser.parseOrNull(String.valueOf(rawStep));
            if (step == null) {
                problems.add("countdown.steps contains an invalid duration: '" + rawStep + "'");
            } else {
                steps.add(step);
            }
        }
        if (steps.isEmpty()) {
            steps.add(Duration.ofMinutes(10));
            steps.add(Duration.ofMinutes(5));
            steps.add(Duration.ofMinutes(1));
            steps.add(Duration.ofSeconds(30));
        }
        return new CountdownConfig(defaultDuration, List.copyOf(steps));
    }

    private List<ScheduleEntry> parseSchedules(List<Map<String, Object>> entries, String path) {
        List<ScheduleEntry> result = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (Map<String, Object> entry : entries) {
            String id = str(entry, "id", "schedule-" + (result.size() + 1));
            if (!ids.add(id)) {
                problems.add(path + " contains duplicated id '" + id + "'");
            }
            ScheduleType type;
            try {
                type = ScheduleType.parse(str(entry, "type", "daily"));
            } catch (IllegalArgumentException e) {
                type = ScheduleType.DAILY;
                problems.add(path + "." + id + ": " + e.getMessage());
            }
            String time = strOrNull(entry, "time");
            List<String> times = asList(entry.get("times"));
            Map<String, List<String>> weekly = parseWeekly(entry.get("weekly"));
            List<String> dates = asList(entry.get("dates"));
            Duration interval = null;
            if (entry.get("interval") != null) {
                try {
                    interval = DurationParser.parse(String.valueOf(entry.get("interval")));
                } catch (IllegalArgumentException e) {
                    problems.add(path + "." + id + ".interval is invalid: " + e.getMessage());
                }
            }
            String cron = strOrNull(entry, "cron");
            ZoneId zone = null;
            if (entry.get("timezone") != null) {
                try {
                    zone = ZoneId.of(String.valueOf(entry.get("timezone")));
                } catch (Exception e) {
                    problems.add(path + "." + id + ".timezone is not a valid zone id: '"
                            + entry.get("timezone") + "'");
                }
            }
            Duration countdown = null;
            if (entry.get("countdown") != null) {
                countdown = duration(entry.get("countdown"),
                        path + "." + id + ".countdown");
            }
            List<WarningEntry> warnings = parseWarnings(
                    listOfMaps(entry.get("warnings")), path + "." + id + ".warnings");
            ConditionsConfig conditions = entry.containsKey("conditions")
                    ? parseConditions(section(entry, "conditions")) : null;
            SmartRestartConfig smartRestart = entry.containsKey("smart_restart")
                    ? parseSmartRestart(section(entry, "smart_restart")) : null;
            MaintenanceConfig maintenance = entry.containsKey("maintenance")
                    ? parseMaintenance(section(entry, "maintenance")) : null;

            String description = str(entry, "description", "");
            boolean enabled = bool(entry, "enabled", true);
            int priority = intValue(entry, "priority", 0);

            ScheduleEntry schedule = new ScheduleEntry(
                    id, type, time, times, weekly, dates, interval, cron,
                    description, enabled, priority, zone, countdown,
                    warnings.isEmpty() ? null : warnings,
                    conditions, smartRestart, maintenance);
            validateSchedule(schedule, path + "." + id);
            result.add(schedule);
        }
        return List.copyOf(result);
    }

    private void validateSchedule(ScheduleEntry schedule, String path) {
        switch (schedule.type()) {
            case DAILY -> {
                if (schedule.time() == null && schedule.times().isEmpty()) {
                    problems.add(path + ": daily schedules need a 'time' (\"04:00\") or 'times' list");
                }
                if (schedule.time() != null) {
                    validateTime(schedule.time(), path + ".time");
                }
                for (String t : schedule.times()) {
                    validateTime(t, path + ".times");
                }
            }
            case WEEKLY -> {
                if (schedule.weekly().isEmpty()) {
                    problems.add(path + ": weekly schedules need a 'weekly' map (monday: [\"04:00\"])");
                }
                schedule.weekly().forEach((day, dayTimes) -> {
                    if (!isDayName(day)) {
                        problems.add(path + ".weekly contains an invalid day: '" + day + "'");
                    }
                    for (String t : dayTimes) {
                        validateTime(t, path + ".weekly." + day);
                    }
                });
            }
            case INTERVAL -> {
                if (schedule.interval() == null) {
                    problems.add(path + ": interval schedules need an 'interval' (\"6h\")");
                }
            }
            case DATES -> {
                if (schedule.dates().isEmpty()) {
                    problems.add(path + ": date schedules need a 'dates' list (\"2026-12-24 04:00\")");
                }
                for (String date : schedule.dates()) {
                    try {
                        java.time.LocalDateTime.parse(date,
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    } catch (Exception e) {
                        problems.add(path + ".dates contains an invalid date: '" + date + "'");
                    }
                }
            }
            case CRON -> {
                if (schedule.cron() == null || schedule.cron().isBlank()) {
                    problems.add(path + ": cron schedules need a 'cron' expression");
                } else {
                    try {
                        dev.hauch.restartly.scheduler.CronExpression.parse(schedule.cron());
                    } catch (IllegalArgumentException e) {
                        problems.add(path + ".cron is invalid: " + e.getMessage());
                    }
                }
            }
        }
        if (schedule.countdown() != null && schedule.countdown().isZero()) {
            problems.add(path + ".countdown must be greater than zero");
        }
    }

    private static boolean isDayName(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "monday", "tuesday", "wednesday", "thursday", "friday",
                    "saturday", "sunday", "mon", "tue", "wed", "thu", "fri",
                    "sat", "sun" -> true;
            default -> false;
        };
    }

    private void validateTime(String raw, String field) {
        try {
            DurationParser.parseTimeOfDay(raw);
        } catch (IllegalArgumentException e) {
            problems.add(field + ": " + e.getMessage());
        }
    }

    private Map<String, List<String>> parseWeekly(Object raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), asList(entry.getValue()));
            }
        }
        return result;
    }

    private List<WarningEntry> parseWarnings(List<Map<String, Object>> entries, String path) {
        List<WarningEntry> result = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Duration time;
            try {
                time = DurationParser.parse(str(entry, "time", "0s"));
            } catch (IllegalArgumentException e) {
                problems.add(path + " contains an invalid time: " + e.getMessage());
                continue;
            }
            if (time.isNegative()) {
                problems.add(path + ".time must not be negative");
                continue;
            }
            ChatAction chat = null;
            Map<String, Object> chatSection = section(entry, "chat");
            if (!chatSection.isEmpty() && bool(chatSection, "enabled", true)) {
                chat = new ChatAction(true, str(chatSection, "message",
                        "<yellow>Server restarting in {time}"));
            }
            TitleAction title = parseTitleAction(section(entry, "title"));
            ActionbarAction actionbar = parseActionbarAction(section(entry, "actionbar"));
            BossbarAction bossbar = parseBossbarAction(section(entry, "bossbar"));
            SoundAction sound = parseSound(section(entry, "sound"));
            List<String> commands = asList(entry.get("commands"));
            result.add(new WarningEntry(time, chat, title, actionbar, bossbar, sound, commands));
        }
        return List.copyOf(result);
    }

    private TitleAction parseTitleAction(Map<String, Object> s) {
        if (s.isEmpty() || !bool(s, "enabled", true)) {
            return null;
        }
        return new TitleAction(
                true,
                str(s, "title", ""),
                str(s, "subtitle", ""),
                intValue(s, "fade_in", 10),
                intValue(s, "stay", 60),
                intValue(s, "fade_out", 20));
    }

    private ActionbarAction parseActionbarAction(Map<String, Object> s) {
        if (s.isEmpty() || !bool(s, "enabled", true)) {
            return null;
        }
        return new ActionbarAction(true, str(s, "message", "Restarting in {time}"));
    }

    private BossbarAction parseBossbarAction(Map<String, Object> s) {
        if (s.isEmpty() || !bool(s, "enabled", true)) {
            return null;
        }
        return new BossbarAction(true,
                str(s, "color", null),
                str(s, "overlay", null),
                str(s, "message", null));
    }

    private SoundAction parseSound(Map<String, Object> s) {
        if (s.isEmpty() || !bool(s, "enabled", true)) {
            return null;
        }
        return new SoundAction(true,
                str(s, "sound", "minecraft:block.note_block.bell"),
                floatValue(s, "volume", 1.0f),
                floatValue(s, "pitch", 1.0f));
    }

    private TitleConfig parseTitle(Map<String, Object> s) {
        return new TitleConfig(
                bool(s, "enabled", false),
                intValue(s, "fade_in", 10),
                intValue(s, "stay", 60),
                intValue(s, "fade_out", 20),
                str(s, "title", "<red>SERVER RESTART"),
                str(s, "subtitle", "<yellow>Restarting in {time}"));
    }

    private ActionbarConfig parseActionbar(Map<String, Object> s) {
        Duration interval = durationOr(s.get("update_interval"), "actionbar.update_interval",
                Duration.ofSeconds(1));
        return new ActionbarConfig(
                bool(s, "enabled", false),
                str(s, "message", "<red>Restarting in <white>{time}"),
                interval);
    }

    private BossbarConfig parseBossbar(Map<String, Object> s) {
        return new BossbarConfig(
                bool(s, "enabled", false),
                str(s, "color", "RED"),
                str(s, "overlay", "PROGRESS"),
                str(s, "message", "Restarting in {time}"),
                str(s, "progress", "countdown"));
    }

    private ChatConfig parseChat(Map<String, Object> s) {
        String prefix = str(s, "prefix", "<dark_gray>[<red>Restartly<dark_gray>] ");
        Map<String, Object> messagesSection = section(s, "messages");
        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : messagesSection.entrySet()) {
            messages.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        for (String known : KNOWN_MESSAGES) {
            if (!messages.containsKey(known)) {
                String fallback = defaultMessage(known, prefix);
                if (fallback != null) {
                    messages.put(known, fallback);
                }
            }
        }
        return new ChatConfig(prefix, Map.copyOf(messages));
    }

    private String defaultMessage(String key, String prefix) {
        return switch (key) {
            case "restart_started" -> "{prefix}<yellow>Restarting in {time}.";
            case "restart_scheduled" -> "{prefix}<yellow>Restart scheduled. Next restart: {time}.";
            case "restart_cancelled" -> "{prefix}<green>Restart cancelled.";
            case "restart_now" -> "{prefix}<red>Server restarting now.";
            case "restart_waiting" ->
                    "{prefix}<yellow>Conditions not met. Waiting to restart the server...";
            case "restart_completed" -> "{prefix}<green>Server restart completed.";
            case "next_restart" ->
                    "{prefix}<yellow>Next restart: {time} ({timezone}).";
            case "no_restart_scheduled" ->
                    "{prefix}<gray>No restart is currently scheduled.";
            case "maintenance_active" ->
                    "{prefix}<red>Maintenance mode is now active.";
            case "maintenance_disabled" ->
                    "{prefix}<green>Maintenance mode is now disabled.";
            case "reload_success" -> "{prefix}<green>Configuration reloaded. {count} schedules active.";
            case "no_permission" -> "{prefix}<red>You do not have permission to use this command.";
            case "invalid_subcommand" -> "{prefix}<red>Unknown subcommand. Use /restartly help.";
            default -> null;
        };
    }

    private KickConfig parseKick(Map<String, Object> s) {
        KickTiming when;
        try {
            when = KickTiming.parse(str(s, "when", "SHUTDOWN").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            when = KickTiming.SHUTDOWN;
            problems.add("kick.when is invalid: " + e.getMessage());
        }
        Duration delay = durationOr(s.get("delay"), "kick.delay", Duration.ofSeconds(10));
        return new KickConfig(
                bool(s, "enabled", false),
                when,
                delay,
                str(s, "message", "<red>Server restarting.\n\n<gray>Please reconnect shortly."));
    }

    private MaintenanceConfig parseMaintenance(Map<String, Object> s) {
        return new MaintenanceConfig(
                bool(s, "enabled", false),
                durationOr(s.get("activate_before"), "maintenance.activate_before",
                        Duration.ofMinutes(5)),
                bool(s, "kick_players", false),
                str(s, "kick_message", "<red>Server restarting."),
                bool(s, "block_join", true),
                bool(s, "auto_disable_on_restart", true));
    }

    private ConditionsConfig parseConditions(Map<String, Object> s) {
        if (s.isEmpty()) {
            return defaultConditions();
        }
        ConditionFailurePolicy onFailure = defaultOnFailure();
        if (s.get("on_failure") != null) {
            try {
                onFailure = ConditionFailurePolicy.parse(String.valueOf(s.get("on_failure")));
            } catch (IllegalArgumentException e) {
                problems.add("conditions.on_failure is invalid: " + e.getMessage());
            }
        }
        Duration combatTimeout = durationOr(s.get("combat_timeout"), "conditions.combat_timeout",
                Duration.ofSeconds(30));
        return new ConditionsConfig(
                intValue(s, "min_players", 0),
                intValue(s, "max_players", -1),
                bool(s, "require_empty", false),
                doubleValue(s, "require_tps_above", 0.0),
                bool(s, "require_no_combat", false),
                bool(s, "require_no_active_event", false),
                combatTimeout,
                onFailure,
                intValue(s, "cancel_if_players_above", -1),
                bool(s, "cancel_if_event_active", false),
                doubleValue(s, "cancel_if_tps_below", 0.0));
    }

    private ConditionsConfig defaultConditions() {
        return new ConditionsConfig(0, -1, false, 0.0, false, false,
                Duration.ofSeconds(30), defaultOnFailure(), -1, false, 0.0);
    }

    private ConditionFailurePolicy defaultOnFailure() {
        return ConditionFailurePolicy.CANCEL;
    }

    private SmartRestartConfig parseSmartRestart(Map<String, Object> s) {
        if (s.isEmpty()) {
            return new SmartRestartConfig(false, false,
                    Duration.ofHours(2), Duration.ofSeconds(30), 0, 0,
                    MaxDelayAction.RESTART);
        }
        MaxDelayAction maxDelayAction = MaxDelayAction.RESTART;
        if (s.get("max_delay_action") != null) {
            try {
                maxDelayAction = MaxDelayAction.parse(String.valueOf(s.get("max_delay_action")));
            } catch (IllegalArgumentException e) {
                problems.add("smart_restart.max_delay_action is invalid: " + e.getMessage());
            }
        }
        return new SmartRestartConfig(
                bool(s, "enabled", false),
                bool(s, "wait_for_empty", true),
                durationOr(s.get("max_delay"), "smart_restart.max_delay", Duration.ofHours(2)),
                durationOr(s.get("retry_interval"), "smart_restart.retry_interval",
                        Duration.ofSeconds(30)),
                intValue(s, "min_players", 0),
                intValue(s, "max_players", 0),
                maxDelayAction);
    }

    private CommandsConfig parseCommands(Map<String, Object> s) {
        Map<String, List<String>> byTime = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : s.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("on_")) {
                String step = key.substring(3).replace('_', ' ');
                String normalized = postProcessStep(step);
                if (normalized != null) {
                    byTime.put(normalized, asList(entry.getValue()));
                }
            }
        }
        return new CommandsConfig(
                asList(s.get("on_restart_start")),
                asList(s.get("on_restart")),
                asList(s.get("on_cancel")),
                asList(s.get("on_waiting")),
                byTime);
    }

    /**
     * Normalizes a step key like "on_30m" to "30m". Returns {@code null}
     * when the key is a fixed lifecycle hook (restart_start, restart, ...).
     */
    static String postProcessStep(String raw) {
        String step = raw.trim();
        if (step.equals("restart_start") || step.equals("restart")
                || step.equals("cancel") || step.equals("waiting")
                || step.isEmpty() || step.equals("now")) {
            return null;
        }
        return step;
    }

    private IntegrationsConfig parseIntegrations(Map<String, Object> s) {
        Map<String, Object> webhookSection = section(s, "webhook");
        Map<String, Boolean> eventMap = new LinkedHashMap<>();
        Map<String, Object> eventsSection = section(webhookSection, "events");
        for (Map.Entry<String, ?> entry : eventsSection.entrySet()) {
            eventMap.put(entry.getKey(), boolValue(entry.getValue()));
        }
        return new IntegrationsConfig(new WebhookConfig(
                bool(webhookSection, "enabled", false),
                str(webhookSection, "url", ""),
                intValue(webhookSection, "retries", 2),
                Map.copyOf(eventMap)));
    }

    private PermissionsConfig parsePermissions(Map<String, Object> s) {
        Map<String, String> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : s.entrySet()) {
            nodes.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return new PermissionsConfig(Map.copyOf(nodes));
    }

    private LoggingConfig parseLogging(Map<String, Object> s) {
        return new LoggingConfig(
                bool(s, "log_schedule_fires", true),
                bool(s, "log_warning_fires", true),
                bool(s, "log_webhook_responses", false));
    }

    // ------------------------------------------------------------------
    // Low level helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        problems.add(key + " must be a YAML mapping");
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                } else {
                    problems.add("Expected a list of mappings, got " + item);
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static List<String> asList(Object raw) {
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

    private static String str(Map<String, Object> s, String key, String fallback) {
        Object value = s.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String strOrNull(Map<String, Object> s, String key) {
        Object value = s.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> s, String key, boolean fallback) {
        Object value = s.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return fallback;
    }

    private static boolean boolValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Map<String, Object> s, String key, int fallback) {
        Object value = s.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static float floatValue(Map<String, Object> s, String key, float fallback) {
        Object value = s.get(key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return fallback;
    }

    private static double doubleValue(Map<String, Object> s, String key, double fallback) {
        Object value = s.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Duration duration(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        try {
            return DurationParser.parse(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            problems.add(field + " is invalid: " + e.getMessage());
            return null;
        }
    }

    private static Duration durationOr(Object raw, String field, Duration fallback) {
        if (raw == null) {
            return fallback;
        }
        Duration parsed = DurationParser.parseOrNull(String.valueOf(raw));
        return parsed == null ? fallback : parsed;
    }
}