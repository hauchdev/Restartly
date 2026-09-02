package dev.hauch.restartly.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human readable durations ("30s", "5m", "1h", "2h30m", "1d 4h")
 * into {@link Duration} and formats them back.
 *
 * <p>All parsing in Restartly goes through this class so that the accepted
 * syntax stays consistent across the whole configuration surface.</p>
 */
public final class DurationParser {

    private static final Pattern DURATION = Pattern.compile(
            "\\s*(?:(\\d+)\\s*(?:d|days?|días?|dias?))?\\s*" +
                    "(?:(\\d+)\\s*(?:h|hrs?|hours?|horas?))?\\s*" +
                    "(?:(\\d+)\\s*(?:m|mins?|minutes?|minutos?))?\\s*" +
                    "(?:(\\d+)\\s*(?:s|secs?|seconds?|segundos?))?\\s*",
            Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    /**
     * @throws IllegalArgumentException when the input does not contain any
     *                                  valid duration component.
     */
    public static Duration parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Duration cannot be null");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }
        Matcher matcher = DURATION.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration: '" + input + "'");
        }
        long days = parseGroup(matcher.group(1), trimmed);
        long hours = parseGroup(matcher.group(2), trimmed);
        long minutes = parseGroup(matcher.group(3), trimmed);
        long seconds = parseGroup(matcher.group(4), trimmed);
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            throw new IllegalArgumentException("Invalid duration: '" + input + "'");
        }
        return Duration.ofDays(days)
                .plusHours(hours)
                .plusMinutes(minutes)
                .plusSeconds(seconds);
    }

    private static long parseGroup(String group, String raw) {
        if (group == null || group.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(group);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid number in duration: '" + raw + "'", e);
        }
    }

    /**
     * Parses a duration returning {@code null} when the input is blank or
     * invalid, logging nothing. Intended for optional configuration fields.
     */
    public static Duration parseOrNull(String input) {
        try {
            return parse(input);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Formats a duration as {@code 2h 30m 10s} (units are omitted when zero).
     */
    public static String format(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return "0s";
        }
        long seconds = duration.getSeconds();
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append('d').append(' ');
        }
        if (hours > 0) {
            sb.append(hours).append('h').append(' ');
        }
        if (minutes > 0) {
            sb.append(minutes).append('m').append(' ');
        }
        if (secs > 0 || sb.length() == 0) {
            sb.append(secs).append('s');
        }
        return sb.toString().trim();
    }

    /**
     * Formats a duration as {@code mm:ss} or {@code h:mm:ss} for countdowns.
     */
    public static String formatClock(Duration duration) {
        if (duration == null || duration.isNegative()) {
            duration = Duration.ZERO;
        }
        long total = duration.getSeconds();
        long hours = total / 3_600;
        long minutes = (total % 3_600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /**
     * Parses a time-of-day "HH:mm" or "HH:mm:ss" into seconds of day.
     *
     * @throws IllegalArgumentException when the value is not a valid time.
     */
    public static long parseTimeOfDay(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Time of day cannot be null");
        }
        String[] parts = input.trim().split(":");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Invalid time of day: '" + input + "'");
        }
        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59
                    || seconds < 0 || seconds > 59) {
                throw new IllegalArgumentException("Invalid time of day: '" + input + "'");
            }
            return hours * 3_600L + minutes * 60L + seconds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid time of day: '" + input + "'", e);
        }
    }
}