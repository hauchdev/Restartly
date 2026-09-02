package dev.hauch.restartly.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Small formatting helpers shared by the logging, status and placeholder code.
 */
public final class TimeFormats {

    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFormats() {
    }

    public static String format(Instant instant, ZoneId zone) {
        if (instant == null) {
            return "-";
        }
        return ZonedDateTime.ofInstant(instant, zone).format(TIMESTAMP);
    }

    public static String format(ZonedDateTime dateTime) {
        if (dateTime == null) {
            return "-";
        }
        return dateTime.format(TIMESTAMP);
    }

    /**
     * Formats an uptime duration like {@code 1d 2h 3m} (unit components
     * are omitted when zero).
     */
    public static String formatUptime(Duration uptime) {
        if (uptime == null || uptime.isNegative()) {
            return "0s";
        }
        long seconds = uptime.getSeconds();
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || sb.length() == 0) {
            sb.append(minutes).append("m");
        }
        return sb.toString().trim();
    }
}