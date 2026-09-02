package dev.hauch.restartly.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.core.RestartlyCore;
import dev.hauch.restartly.message.MinecraftText;
import dev.hauch.restartly.restart.RestartManager;
import dev.hauch.restartly.restart.RestartState;
import dev.hauch.restartly.util.DurationParser;
import dev.hauch.restartly.util.TimeFormats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code /restartly} command tree. Registered by each loader through the
 * vanilla {@link CommandDispatcher}; permission nodes are resolved through
 * the {@link dev.hauch.restartly.platform.RestartPlatform} (op levels by
 * default, LuckPerms-style permission providers optional). Console is always
 * allowed.
 */
public final class RestartlyCommands {

    private RestartlyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                RestartlyCore core) {
        SuggestionProvider<CommandSourceStack> timeSuggestions = (context, builder) -> {
            RestartlyConfig config = core.configManager().get();
            List<String> values = new ArrayList<>();
            values.add("now");
            values.add("30s");
            values.add("1m");
            values.add("5m");
            values.add("10m");
            values.add("30m");
            values.add("1h");
            for (Duration step : config.countdown().steps()) {
                values.add(DurationParser.format(step));
            }
            values.stream().distinct()
                    .filter(v -> v.startsWith(builder.getRemainingLowerCase()))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("restartly")
                .then(Commands.literal("restart")
                        .executes(ctx -> restart(ctx.getSource(), core, null, null, false))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .suggests(timeSuggestions)
                                .executes(ctx -> restart(ctx.getSource(), core,
                                        parseTime(ctx.getArgument("time", String.class)),
                                        null, false))
                                .then(Commands.literal("--force")
                                        .executes(ctx -> restart(ctx.getSource(), core,
                                                parseTime(ctx.getArgument("time", String.class)),
                                                null, true)))
                                .then(Commands.literal("--reason")
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> restart(ctx.getSource(), core,
                                                        parseTime(ctx.getArgument("time", String.class)),
                                                        ctx.getArgument("reason", String.class), false)))))
                        .then(Commands.literal("--force")
                                .executes(ctx -> restart(ctx.getSource(), core, null, null, true)))
                        .then(Commands.literal("--reason")
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> restart(ctx.getSource(), core,
                                                null, ctx.getArgument("reason", String.class), false)))))
                .then(Commands.literal("cancel")
                        .executes(ctx -> cancel(ctx.getSource(), core, null))
                        .then(Commands.literal("--reason")
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> cancel(ctx.getSource(), core,
                                                ctx.getArgument("reason", String.class))))))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource(), core)))
                .then(Commands.literal("next")
                        .executes(ctx -> next(ctx.getSource(), core)))
                .then(Commands.literal("warnings")
                        .executes(ctx -> warnings(ctx.getSource(), core)))
                .then(Commands.literal("reload")
                        .requires(src -> hasPermission(src, core, "reload", 2))
                        .executes(ctx -> reload(ctx.getSource(), core)))
                .then(Commands.literal("version")
                        .executes(ctx -> version(ctx.getSource(), core)))
                .then(scheduleTree(core))
                .then(Commands.literal("maintenance")
                        .requires(src -> hasPermission(src, core, "maintenance", 2))
                        .then(Commands.literal("on")
                                .executes(ctx -> setMaintenance(ctx.getSource(), core, true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setMaintenance(ctx.getSource(), core, false)))
                        .then(Commands.literal("toggle")
                                .executes(ctx -> setMaintenance(ctx.getSource(), core,
                                        !core.maintenanceActive()))))
                .then(Commands.literal("debug")
                        .requires(src -> hasPermission(src, core, "debug", 2))
                        .then(Commands.literal("on")
                                .executes(ctx -> setDebug(ctx.getSource(), core, true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setDebug(ctx.getSource(), core, false)))
                        .then(Commands.literal("toggle")
                                .executes(ctx -> setDebug(ctx.getSource(), core,
                                        !core.debugMode()))))
                .executes(ctx -> status(ctx.getSource(), core));

        LiteralCommandNode<CommandSourceStack> built = root.build();
        dispatcher.register(root);
        dispatcher.register(Commands.literal("restartly").redirect(built));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> scheduleTree(RestartlyCore core) {
        return Commands.literal("schedule")
                .requires(src -> hasPermission(src, core, "schedule", 2))
                .then(Commands.literal("list")
                        .executes(ctx -> scheduleList(ctx.getSource(), core)))
                .then(scheduleAddNode(core))
                .then(scheduleRemoveNode(core));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> scheduleAddNode(RestartlyCore core) {
        return Commands.literal("add")
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (String type : List.of("daily", "interval", "cron", "dates")) {
                                builder.suggest(type);
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> scheduleAdd(ctx.getSource(), core,
                                        ctx.getArgument("type", String.class),
                                        ctx.getArgument("value", String.class), null))
                                .then(Commands.literal("--countdown")
                                        .then(Commands.argument("countdown", StringArgumentType.word())
                                                .executes(ctx -> scheduleAdd(ctx.getSource(), core,
                                                        ctx.getArgument("type", String.class),
                                                        ctx.getArgument("value", String.class),
                                                        ctx.getArgument("countdown", String.class)))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> scheduleRemoveNode(RestartlyCore core) {
        return Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> scheduleRemove(ctx.getSource(), core,
                                ctx.getArgument("id", String.class))));
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private static boolean hasPermission(CommandSourceStack source, RestartlyCore core,
                                         String key, int fallbackOpLevel) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return true; // console
        }
        RestartlyConfig.PermissionsConfig permissions = core.configManager().get().permissions();
        String node = permissions.node(key, "restartly." + key);
        String adminNode = permissions.node("admin", "restartly.admin");
        return core.platform().hasPermission(player, node, fallbackOpLevel)
                || core.platform().hasPermission(player, adminNode, 4);
    }

    private static Duration parseTime(String raw) {
        if (raw == null || raw.equalsIgnoreCase("now")) {
            return null;
        }
        return DurationParser.parse(raw);
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private static int restart(CommandSourceStack source, RestartlyCore core,
                               Duration countdown, String reason, boolean force) {
        if (!hasPermission(source, core, "restart", 2)) {
            sendFailure(source, key(core, "no_permission"));
            return 0;
        }
        boolean ok;
        if (countdown == null) {
            ok = core.restartManager().requestManualRestart(null, reason, force);
        } else {
            ok = core.restartManager().requestManualRestart(countdown, reason, force);
        }
        if (ok) {
            sendSuccess(source, key(core, "restart_started"));
            return 1;
        }
        sendFailure(source, core.configManager().get().chat().prefix()
                + "<red>Restart rejected: a restart session is already active.");
        return 0;
    }

    private static int cancel(CommandSourceStack source, RestartlyCore core, String reason) {
        if (!hasPermission(source, core, "cancel", 2)) {
            sendFailure(source, key(core, "no_permission"));
            return 0;
        }
        if (core.cancelRestart(reason)) {
            sendSuccess(source, key(core, "restart_cancelled"));
            return 1;
        }
        sendFailure(source, key(core, "no_restart_scheduled"));
        return 0;
    }

    private static int status(CommandSourceStack source, RestartlyCore core) {
        if (!hasPermission(source, core, "status", 2)) {
            sendFailure(source, key(core, "no_permission"));
            return 0;
        }
        RestartlyConfig config = core.configManager().get();
        RestartState state = core.state();
        List<String> lines = new ArrayList<>();
        lines.add(config.chat().prefix() + "<yellow>=== Restartly status ===");
        lines.add(config.chat().prefix() + "State: <green>" + state.name());
        RestartManager.RestartSession session = core.session();
        if (session != null) {
            lines.add(config.chat().prefix() + "Reason: <white>" + session.reason());
            lines.add(config.chat().prefix() + "Remaining: <white>"
                    + DurationParser.formatClock(core.remaining()));
        }
        Optional<Instant> next = core.nextRestart();
        lines.add(config.chat().prefix() + "Next scheduled: <white>"
                + (next.map(i -> TimeFormats.format(i, config.zone())).orElse("none")));
        lines.add(config.chat().prefix() + "Players: <white>"
                + core.platform().onlinePlayers().size() + "<gray>/<white>"
                + core.platform().maxPlayers());
        lines.add(config.chat().prefix() + "Maintenance: <white>"
                + (core.maintenanceActive() ? "<red>ACTIVE" : "<green>off"));
        lines.add(config.chat().prefix() + "Debug: <white>" + (core.debugMode() ? "on" : "off"));
        sendSuccessMulti(source, lines);
        return 1;
    }

    private static int next(CommandSourceStack source, RestartlyCore core) {
        if (!hasPermission(source, core, "status", 2)) {
            sendFailure(source, key(core, "no_permission"));
            return 0;
        }
        RestartlyConfig config = core.configManager().get();
        Optional<Instant> next = core.nextRestart();
        if (next.isEmpty()) {
            sendSuccess(source, key(core, "no_restart_scheduled"));
            return 1;
        }
        String message = key(core, "next_restart")
                .replace("{time}", TimeFormats.format(next.get(), config.zone()))
                .replace("{timezone}", config.zone().getId());
        sendSuccess(source, message);
        return 1;
    }

    private static int warnings(CommandSourceStack source, RestartlyCore core) {
        if (!hasPermission(source, core, "status", 2)) {
            sendFailure(source, key(core, "no_permission"));
            return 0;
        }
        RestartlyConfig config = core.configManager().get();
        List<String> lines = new ArrayList<>();
        lines.add(config.chat().prefix() + "<yellow>Configured countdown warnings:");
        if (config.warnings().isEmpty()) {
            lines.add(config.chat().prefix() + "<gray>none");
        }
        for (RestartlyConfig.WarningEntry warning : config.warnings()) {
            StringBuilder sb = new StringBuilder(config.chat().prefix())
                    .append("<white>").append(DurationParser.format(warning.time()));
            List<String> actions = new ArrayList<>();
            if (warning.chat() != null && warning.chat().enabled()) {
                actions.add("chat");
            }
            if (warning.title() != null && warning.title().enabled()) {
                actions.add("title");
            }
            if (warning.actionbar() != null && warning.actionbar().enabled()) {
                actions.add("actionbar");
            }
            if (warning.bossbar() != null && warning.bossbar().enabled()) {
                actions.add("bossbar");
            }
            if (warning.sound() != null && warning.sound().enabled()) {
                actions.add("sound");
            }
            if (!actions.isEmpty()) {
                sb.append(" <gray>(").append(String.join(", ", actions)).append(')');
            }
            lines.add(sb.toString());
        }
        sendSuccessMulti(source, lines);
        return 1;
    }

    private static int reload(CommandSourceStack source, RestartlyCore core) {
        List<String> problems = core.reload();
        if (problems == null) {
            RestartlyConfig config = core.configManager().get();
            String message = config.chat().message("reload_success",
                    "{prefix}<green>Configuration reloaded. {count} schedules active.")
                    .replace("{prefix}", config.chat().prefix())
                    .replace("{count}", String.valueOf(config.schedules().size()));
            sendSuccess(source, message);
            return 1;
        }
        sendFailure(source, core.configManager().get().chat().prefix()
                + "<red>Configuration reload failed:");
        problems.forEach(p -> sendFailure(source, core.configManager().get().chat().prefix()
                + "<gray>- " + p));
        return 0;
    }

    private static int version(CommandSourceStack source, RestartlyCore core) {
        if (!hasPermission(source, core, "status", 2)) {
            sendFailure(source, key(core, "no_permission"));
            return 0;
        }
        RestartlyConfig config = core.configManager().get();
        sendSuccessMulti(source, List.of(
                config.chat().prefix() + "<yellow>Restartly <white>" + dev.hauch.restartly.Restartly.VERSION,
                config.chat().prefix() + "Loader: <white>" + core.platform().loaderName()
                        + " | Minecraft: <white>" + core.platform().serverVersion(),
                config.chat().prefix() + "Config version: <white>" + config.version()));
        return 1;
    }

    private static int scheduleList(CommandSourceStack source, RestartlyCore core) {
        RestartlyConfig config = core.configManager().get();
        List<String> lines = new ArrayList<>();
        lines.add(config.chat().prefix() + "<yellow>Registered schedules (" + config.schedules().size() + "):");
        for (RestartlyConfig.ScheduleEntry schedule : config.schedules()) {
            String definition = switch (schedule.type()) {
                case DAILY -> schedule.time() != null ? schedule.time()
                        : String.join(",", schedule.times());
                case WEEKLY -> "weekly:" + schedule.weekly();
                case INTERVAL -> DurationParser.format(schedule.interval());
                case DATES -> String.join(",", schedule.dates());
                case CRON -> schedule.cron();
            };
            lines.add(config.chat().prefix() + "<white>" + schedule.id()
                    + " <gray>[" + schedule.type() + " " + definition + "]"
                    + (schedule.enabled() ? "" : " <red>(disabled)")
                    + (schedule.priority() != 0 ? " <gray>priority=" + schedule.priority() : ""));
        }
        sendSuccessMulti(source, lines);
        return 1;
    }

    private static int scheduleAdd(CommandSourceStack source, RestartlyCore core,
                                   String type, String value, String countdown) {
        List<String> problems = core.configManager().addSchedule(type, value, countdown);
        if (problems == null) {
            List<String> reloadProblems = core.reload();
            if (reloadProblems != null) {
                reloadProblems.forEach(p -> sendFailure(source, "<red>" + p));
                return 0;
            }
            sendSuccess(source, core.configManager().get().chat().prefix()
                    + "<green>Schedule added (" + type + ") and config reloaded.");
            return 1;
        }
        problems.forEach(p -> sendFailure(source, "<red>" + p));
        return 0;
    }

    private static int scheduleRemove(CommandSourceStack source, RestartlyCore core, String id) {
        List<String> problems = core.configManager().removeSchedule(id);
        if (problems == null) {
            List<String> reloadProblems = core.reload();
            if (reloadProblems != null) {
                reloadProblems.forEach(p -> sendFailure(source, "<red>" + p));
                return 0;
            }
            sendSuccess(source, core.configManager().get().chat().prefix()
                    + "<green>Schedule '{}' removed.".replace("{}", id));
            return 1;
        }
        problems.forEach(p -> sendFailure(source, "<red>" + p));
        return 0;
    }

    private static int setMaintenance(CommandSourceStack source, RestartlyCore core, boolean active) {
        if (!hasPermission(source, core, "maintenance", 2)) {
            sendFailure(source, key(core, "no_permission"));
            return 0;
        }
        boolean changed = core.setMaintenance(active);
        sendSuccess(source, key(core, active ? "maintenance_active" : "maintenance_disabled"));
        return changed ? 1 : 0;
    }

    private static int setDebug(CommandSourceStack source, RestartlyCore core, boolean debug) {
        core.setDebugMode(debug);
        sendSuccess(source, core.configManager().get().chat().prefix()
                + "<green>Debug mode " + (debug ? "enabled" : "disabled") + ".");
        return 1;
    }

    // ------------------------------------------------------------------
    // Feedback helpers
    // ------------------------------------------------------------------

    private static String key(RestartlyCore core, String key) {
        RestartlyConfig config = core.configManager().get();
        return config.chat().messageOrNull(key) == null
                ? config.chat().prefix() + "<white>" + key.replace('_', ' ')
                : config.chat().message(key, " ");
    }

    private static void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(() -> MinecraftText.parse(message), false);
    }

    private static void sendFailure(CommandSourceStack source, String message) {
        source.sendFailure(MinecraftText.parse(message));
    }

    private static void sendSuccessMulti(CommandSourceStack source, List<String> lines) {
        source.sendSuccess(() -> Component.literal("")
                .append(MinecraftText.parse(String.join("\n", lines))), false);
    }
}