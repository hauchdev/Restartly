package dev.hauch.restartly.scheduler;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

/**
 * A deliberately small but correct 5-field cron implementation covering the
 * syntax admins actually use:
 *
 * <pre>
 *   "0 4 * * *"          every day at 04:00
 *   "slash-asterisk /30 * * * *" every half hour (cron steps)
 *   "0 6-8 * * mon-fri"  weekdays between 06:00 and 08:00
 *   "30 4 1,15 * *"      the 1st and 15th at 04:30
 * </pre>
 *
 * <p>Fields are {@code minute hour day-of-month month day-of-week}.
 * Supported elements: the wildcard, step values ({@code a/n}), ranges
 * ({@code a-b}), comma separated lists, {@code sun..sat} names and 3-letter
 * month names. {@code ?} is accepted as a synonym for the wildcard. When
 * both the day-of-month and the day-of-week fields are restricted, standard
 * cron semantics apply (match if <em>either</em> matches).</p>
 */
public final class CronExpression {

    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronExpression(Set<Integer> minutes, Set<Integer> hours,
                           Set<Integer> daysOfMonth, Set<Integer> months,
                           Set<Integer> daysOfWeek,
                           boolean domRestricted, boolean dowRestricted) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    public static CronExpression parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("Cron expression cannot be null");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron expression must have 5 fields, got "
                    + fields.length + ": '" + expression + "'");
        }
        try {
            Set<Integer> minutes = parseField(fields[0], 0, 59, null, 0);
            Set<Integer> hours = parseField(fields[1], 0, 23, null, 0);
            Set<Integer> dom = parseField(fields[2], 1, 31, null, 0);
            Set<Integer> months = parseField(fields[3], 1, 12, MONTH_NAMES, 1);
            Set<Integer> dow = parseField(fields[4], 0, 7, DAY_NAMES, 0);
            // Both 0 and 7 mean Sunday in cron.
            if (dow.contains(7)) {
                dow.add(0);
            }
            boolean domRestricted = !(fields[2].equals("*") || fields[2].equals("?"));
            boolean dowRestricted = !(fields[4].equals("*") || fields[4].equals("?"));
            return new CronExpression(minutes, hours, dom, months, dow,
                    domRestricted, dowRestricted);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression '" + expression
                    + "': " + e.getMessage());
        }
    }

    /**
     * Returns the next time strictly after {@code after} that matches the
     * expression, or {@code null} when nothing matches within a year.
     */
    public ZonedDateTime nextAfter(ZonedDateTime after) {
        ZonedDateTime next = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        long remainingMinutes = 366L * 24 * 60;
        while (remainingMinutes-- > 0) {
            if (matches(next)) {
                return next;
            }
            next = next.plusMinutes(1);
        }
        return null;
    }

    public boolean matches(ZonedDateTime dateTime) {
        int minute = dateTime.getMinute();
        int hour = dateTime.getHour();
        int dayOfMonth = dateTime.getDayOfMonth();
        int month = dateTime.getMonthValue();
        int dow = dateTime.getDayOfWeek().getValue() % 7; // 0 = Sunday

        if (!minutes.contains(minute) || !hours.contains(hour)
                || !months.contains(month)) {
            return false;
        }
        boolean domOk = !domRestricted || daysOfMonth.contains(dayOfMonth);
        boolean dowOk = !dowRestricted || daysOfWeek.contains(dow);
        if (domRestricted && dowRestricted) {
            return domOk || dowOk;
        }
        return domOk && dowOk;
    }

    private static Set<Integer> parseField(String field, int min, int max,
                                           String[] names, int nameBase) {
        if (field.equals("?") || field.equals("*")) {
            return fullRange(min, max);
        }
        Set<Integer> result = new HashSet<>();
        for (String part : field.split(",")) {
            parsePart(part, min, max, names, nameBase, result);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' matches nothing");
        }
        return result;
    }

    private static void parsePart(String part, int min, int max, String[] names,
                                  int nameBase, Set<Integer> result) {
        String rangePart = part;
        int step = 1;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            rangePart = part.substring(0, slash);
            String stepRaw = part.substring(slash + 1);
            try {
                step = Integer.parseInt(stepRaw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid step '" + stepRaw
                        + "' in '" + part + "'");
            }
            if (step <= 0) {
                throw new IllegalArgumentException("Step must be positive in '" + part + "'");
            }
        }

        int start;
        int end;
        if (rangePart.equals("*")) {
            start = min;
            end = max;
        } else {
            int dash = rangePart.indexOf('-');
            if (dash >= 0) {
                start = parseValue(rangePart.substring(0, dash), min, max, names, nameBase);
                end = parseValue(rangePart.substring(dash + 1), min, max, names, nameBase);
            } else {
                start = parseValue(rangePart, min, max, names, nameBase);
                end = start;
            }
        }
        for (int value = start; value <= end; value += step) {
            result.add(value);
        }
    }

    private static int parseValue(String raw, int min, int max, String[] names,
                                  int nameBase) {
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equalsIgnoreCase(raw)) {
                    int value = i + nameBase;
                    if (value < min || value > max) {
                        throw new IllegalArgumentException("Value '" + raw
                                + "' out of range [" + min + "," + max + "]");
                    }
                    return value;
                }
            }
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) {
                throw new IllegalArgumentException("Value '" + raw
                        + "' out of range [" + min + "," + max + "]");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unknown value '" + raw + "'");
        }
    }

    private static Set<Integer> fullRange(int min, int max) {
        Set<Integer> result = new HashSet<>();
        for (int i = min; i <= max; i++) {
            result.add(i);
        }
        return result;
    }

    private static final String[] MONTH_NAMES = {
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"
    };

    private static final String[] DAY_NAMES = {
            "sun", "mon", "tue", "wed", "thu", "fri", "sat"
    };
}