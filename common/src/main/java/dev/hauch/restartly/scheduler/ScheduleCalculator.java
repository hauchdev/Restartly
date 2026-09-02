package dev.hauch.restartly.scheduler;

import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.util.DurationParser;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Computes the next fire time for a schedule type. Every method is pure —
 * same inputs, same outputs — which keeps the scheduler logic unit testable
 * (including DST transitions) without a running server.
 */
public final class ScheduleCalculator {

    public static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ScheduleCalculator() {
    }

    /**
     * Next instant this schedule should fire, strictly after {@code now}.
     *
     * @param lastTrigger anchor used by INTERVAL schedules; {@code null} for
     *                    the first run
     */
    public static Instant nextFire(RestartlyConfig.ScheduleEntry schedule, ZoneId zone,
                                   Instant now, Instant lastTrigger) {
        return switch (schedule.type()) {
            case DAILY -> nextDaily(schedule, zone, now);
            case WEEKLY -> nextWeekly(schedule, zone, now);
            case INTERVAL -> nextInterval(schedule, now, lastTrigger);
            case DATES -> nextDate(schedule, zone, now);
            case CRON -> nextCron(schedule, zone, now);
        };
    }

    private static Instant nextDaily(RestartlyConfig.ScheduleEntry schedule, ZoneId zone,
                                     Instant now) {
        ZonedDateTime current = ZonedDateTime.ofInstant(now, zone);
        LocalDate day = current.toLocalDate();
        ZonedDateTime best = null;
        for (String timeRaw : allTimes(schedule)) {
            long secondsOfDay = DurationParser.parseTimeOfDay(timeRaw);
            LocalTime time = LocalTime.ofSecondOfDay(secondsOfDay);
            ZonedDateTime candidate = ZonedDateTime.of(day, time, zone);
            if (!candidate.isAfter(current)) {
                candidate = ZonedDateTime.of(day.plusDays(1), time, zone);
            }
            if (best == null || candidate.isBefore(best)) {
                best = candidate;
            }
        }
        return best.toInstant();
    }

    private static java.util.List<String> allTimes(RestartlyConfig.ScheduleEntry schedule) {
        if (schedule.time() != null) {
            return java.util.List.of(schedule.time());
        }
        return schedule.times();
    }

    private static Instant nextWeekly(RestartlyConfig.ScheduleEntry schedule, ZoneId zone,
                                      Instant now) {
        ZonedDateTime current = ZonedDateTime.ofInstant(now, zone);
        ZonedDateTime best = null;
        for (var dayEntry : schedule.weekly().entrySet()) {
            java.time.DayOfWeek day = parseDay(dayEntry.getKey());
            for (String timeRaw : dayEntry.getValue()) {
                long secondsOfDay = DurationParser.parseTimeOfDay(timeRaw);
                LocalTime time = LocalTime.ofSecondOfDay(secondsOfDay);
                ZonedDateTime candidate = ZonedDateTime.of(current.toLocalDate(), time, zone);
                int diff = day.getValue() - candidate.getDayOfWeek().getValue();
                if (diff < 0) {
                    diff += 7;
                }
                candidate = candidate.plusDays(diff);
                if (!candidate.isAfter(current)) {
                    candidate = candidate.plusDays(7);
                }
                if (best == null || candidate.isBefore(best)) {
                    best = candidate;
                }
            }
        }
        return best.toInstant();
    }

    private static java.time.DayOfWeek parseDay(String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "monday", "mon" -> java.time.DayOfWeek.MONDAY;
            case "tuesday", "tue" -> java.time.DayOfWeek.TUESDAY;
            case "wednesday", "wed" -> java.time.DayOfWeek.WEDNESDAY;
            case "thursday", "thu" -> java.time.DayOfWeek.THURSDAY;
            case "friday", "fri" -> java.time.DayOfWeek.FRIDAY;
            case "saturday", "sat" -> java.time.DayOfWeek.SATURDAY;
            case "sunday", "sun" -> java.time.DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Unknown day: '" + raw + "'");
        };
    }

    private static Instant nextInterval(RestartlyConfig.ScheduleEntry schedule, Instant now,
                                        Instant lastTrigger) {
        Duration interval = schedule.interval();
        Instant anchor = lastTrigger != null ? lastTrigger : now;
        Instant next = anchor.plus(interval);
        while (!next.isAfter(now)) {
            next = next.plus(interval);
        }
        return next;
    }

    private static Instant nextDate(RestartlyConfig.ScheduleEntry schedule, ZoneId zone,
                                    Instant now) {
        Instant best = null;
        for (String raw : schedule.dates()) {
            LocalDateTime dateTime;
            try {
                dateTime = LocalDateTime.parse(raw.trim(), DATE_TIME);
            } catch (Exception e) {
                continue;
            }
            Instant candidate = ZonedDateTime.of(dateTime, zone).toInstant();
            if (candidate.isAfter(now) && (best == null || candidate.isBefore(best))) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No future date left for date schedule '"
                    + schedule.id() + "'");
        }
        return best;
    }

    private static Instant nextCron(RestartlyConfig.ScheduleEntry schedule, ZoneId zone,
                                    Instant now) {
        CronExpression cron = CronExpression.parse(schedule.cron());
        ZonedDateTime next = cron.nextAfter(ZonedDateTime.ofInstant(now, zone));
        if (next == null) {
            throw new IllegalStateException("Cron expression for schedule '"
                    + schedule.id() + "' never matches");
        }
        return next.toInstant();
    }

    /**
     * Formats an instant in the given zone for human readable output.
     */
    public static String format(Instant instant, ZoneId zone) {
        return ZonedDateTime.ofInstant(instant, zone)
                .format(DATE_TIME);
    }
}