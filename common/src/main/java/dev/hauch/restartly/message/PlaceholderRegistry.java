package dev.hauch.restartly.message;

import dev.hauch.restartly.api.Placeholder;
import dev.hauch.restartly.api.PlaceholderContext;
import dev.hauch.restartly.util.DurationParser;
import dev.hauch.restartly.util.TimeFormats;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry for the built-in placeholders and whatever other mods register
 * through the API. Resolution is a simple {@code {name}} substitution so it
 * stays cheap; no allocation happens on the tick path beyond the final
 * string.
 */
public final class PlaceholderRegistry {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private final Map<String, Placeholder> custom = new LinkedHashMap<>();

    public PlaceholderRegistry() {
    }

    /**
     * Registers a custom placeholder. Silent no-op when the name is already
     * taken (built-ins are reserved).
     *
     * @return {@code true} when the placeholder was registered
     */
    public boolean register(String name, Placeholder placeholder) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (BUILT_INS.containsKey(key) || custom.containsKey(key) || key.isBlank()) {
            return false;
        }
        custom.put(key, placeholder);
        return true;
    }

    public Set<String> customNames() {
        return Collections.unmodifiableSet(custom.keySet());
    }

    /**
     * Resolves every {@code {placeholder}} in the template against the
     * context. Unresolved placeholders are left as-is so admins notice
     * typos instead of silently empty messages.
     */
    public String resolve(String template, PlaceholderContext context) {
        if (template == null || template.indexOf('{') < 0) {
            return template == null ? "" : template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            Placeholder builtIn = BUILT_INS.get(name);
            String value;
            if (builtIn != null) {
                value = builtIn.apply(context);
            } else {
                Placeholder customPlaceholder = custom.get(name);
                value = customPlaceholder == null ? matcher.group(0)
                        : String.valueOf(customPlaceholder.apply(context));
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Built-ins ({time}, {players}, {server_version}, ...)
    // ------------------------------------------------------------------

    private static final Map<String, Placeholder> BUILT_INS = Map.ofEntries(
            Map.entry("time", ctx -> DurationParser.formatClock(ctx.remaining())),
            Map.entry("seconds", ctx -> String.valueOf(ctx.totalSeconds())),
            Map.entry("minutes", ctx -> String.valueOf(ctx.totalSeconds() / 60)),
            Map.entry("hours", ctx -> String.valueOf(ctx.totalSeconds() / 3_600)),
            Map.entry("players", ctx -> String.valueOf(ctx.onlinePlayers())),
            Map.entry("max_players", ctx -> String.valueOf(ctx.maxPlayers())),
            Map.entry("server_version", ctx -> String.valueOf(ctx.serverVersion())),
            Map.entry("reason", ctx -> ctx.reason() == null ? "none" : ctx.reason()),
            Map.entry("schedule", ctx -> ctx.scheduleId() == null ? "manual" : ctx.scheduleId()),
            Map.entry("timezone", ctx -> ctx.timezone().getId()),
            Map.entry("state", ctx -> String.valueOf(ctx.state())),
            Map.entry("uptime", ctx -> TimeFormats.formatUptime(ctx.uptime())),
            Map.entry("now", ctx -> TimeFormats.format(ctx.now(), ctx.timezone())));
}