package io.aura;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A standard five-field cron schedule: {@code minute hour day-of-month month day-of-week}.
 *
 * <p>Field syntax is the crontab subset: {@code *}, lists ({@code 1,15}), ranges
 * ({@code 8-10}), and steps ({@code *&#47;5}, {@code 5-40&#47;10}, {@code 20&#47;5}). Day
 * of week accepts 0 and 7 for Sunday. When both day-of-month and day-of-week restrict the
 * day, a day matches either one, which is what a crontab line does.
 */
final class CronSchedule {
    private static final String[] FIELD_NAMES = {
            "minute", "hour", "day-of-month", "month", "day-of-week"
    };
    private static final int[] FIELD_MIN = {0, 0, 1, 1, 0};
    private static final int[] FIELD_MAX = {59, 23, 31, 12, 7};
    private static final int[] MONTH_MAX_DAY = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    /** {@code 0 0 29 2 *} is the sparsest schedule the field checks accept: four years between fires. */
    private static final int SEARCH_DAYS = 1461;

    private final String expression;
    private final long minutes;
    private final long hours;
    private final long daysOfMonth;
    private final long months;
    private final long daysOfWeek;
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;

    private CronSchedule(String expression, long minutes, long hours, long daysOfMonth,
                         long months, long daysOfWeek) {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthRestricted = daysOfMonth != fullMask(1, 31);
        this.dayOfWeekRestricted = daysOfWeek != fullMask(0, 6);
    }

    static CronSchedule parse(String expression) {
        String normalized = expression == null ? "" : expression.trim();
        String[] fields = normalized.split("\\s+");
        if (normalized.isEmpty() || fields.length != 5) {
            throw new IllegalArgumentException("Cron expression \"" + expression
                    + "\" needs 5 fields: minute hour day-of-month month day-of-week");
        }
        long parsedMinutes = field(expression, fields[0], 0);
        long parsedHours = field(expression, fields[1], 1);
        long parsedDaysOfMonth = field(expression, fields[2], 2);
        long parsedMonths = field(expression, fields[3], 3);
        long parsedDaysOfWeek = field(expression, fields[4], 4);
        requireSomeSelectedMonthHasSomeSelectedDay(expression, parsedDaysOfMonth, parsedMonths,
                parsedDaysOfWeek);
        return new CronSchedule(normalized, parsedMinutes, parsedHours, parsedDaysOfMonth,
                parsedMonths, parsedDaysOfWeek);
    }

    /** The first fire time strictly after {@code from}, on the wall clock of its zone. */
    ZonedDateTime next(ZonedDateTime from) {
        ZoneId zone = from.getZone();
        LocalDateTime after = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1).toLocalDateTime();
        LocalDate firstDay = after.toLocalDate();
        for (int dayOffset = 0; dayOffset < SEARCH_DAYS; dayOffset++) {
            LocalDate day = firstDay.plusDays(dayOffset);
            if (!matchesDay(day)) continue;
            LocalTime earliest = dayOffset == 0 ? after.toLocalTime() : LocalTime.MIDNIGHT;
            for (int hour = earliest.getHour(); hour < 24; hour++) {
                if (!get(hours, hour)) continue;
                for (int minute = hour == earliest.getHour() ? earliest.getMinute() : 0;
                        minute < 60; minute++) {
                    if (!get(minutes, minute)) continue;
                    return ZonedDateTime.of(LocalDateTime.of(day, LocalTime.of(hour, minute)), zone);
                }
            }
        }
        throw new IllegalArgumentException("Cron expression \"" + expression
                + "\" has no fire time in the " + SEARCH_DAYS + " days after " + from);
    }

    String expression() { return expression; }

    private boolean matchesDay(LocalDate day) {
        if (!get(months, day.getMonthValue())) return false;
        boolean dayOfMonth = get(daysOfMonth, day.getDayOfMonth());
        boolean dayOfWeek = get(daysOfWeek, day.getDayOfWeek().getValue() % 7);
        if (dayOfMonthRestricted && dayOfWeekRestricted) return dayOfMonth || dayOfWeek;
        return dayOfMonth && dayOfWeek;
    }

    private static void requireSomeSelectedMonthHasSomeSelectedDay(String expression,
                                                                  long daysOfMonth, long months,
                                                                  long daysOfWeek) {
        boolean dayOfMonthRestricted = daysOfMonth != fullMask(1, 31);
        boolean monthRestricted = months != fullMask(1, 12);
        boolean dayOfWeekRestricted = daysOfWeek != fullMask(0, 6);
        // A restricted day-of-week keeps the schedule alive on its own, because the two day
        // fields form a union. Only a day-of-month narrowed by month can name an empty day.
        if (!dayOfMonthRestricted || !monthRestricted || dayOfWeekRestricted) return;
        int longestMonth = 0;
        for (int month = 1; month <= 12; month++) {
            if (get(months, month) && (longestMonth == 0
                    || MONTH_MAX_DAY[month - 1] > MONTH_MAX_DAY[longestMonth - 1])) {
                longestMonth = month;
            }
        }
        int earliestDay = Long.numberOfTrailingZeros(daysOfMonth);
        if (earliestDay <= MONTH_MAX_DAY[longestMonth - 1]) return;
        throw new IllegalArgumentException("Cron expression \"" + expression + "\" never fires:"
                + " day-of-month " + earliestDay + " does not exist in any selected month, whose"
                + " longest is month " + longestMonth + " with "
                + MONTH_MAX_DAY[longestMonth - 1] + " days");
    }

    private static long field(String expression, String text, int index) {
        long mask = 0;
        for (String token : text.split(",")) {
            if (token.isEmpty()) throw invalid(expression, text, index, "has an empty list item");
            String body = token;
            int step = 1;
            int slash = token.lastIndexOf('/');
            if (slash >= 0) {
                body = token.substring(0, slash);
                step = number(expression, text, index, token.substring(slash + 1));
                if (step < 1) throw invalid(expression, text, index, "needs a step of at least 1");
            }
            int from;
            int to;
            if (body.equals("*")) {
                from = FIELD_MIN[index];
                to = FIELD_MAX[index];
            } else {
                int dash = body.indexOf('-');
                if (dash > 0) {
                    from = number(expression, text, index, body.substring(0, dash));
                    to = number(expression, text, index, body.substring(dash + 1));
                } else {
                    from = number(expression, text, index, body);
                    to = step > 1 ? FIELD_MAX[index] : from;
                }
            }
            if (from > to) throw invalid(expression, text, index, "runs backwards");
            for (int value = from; value <= to; value += step) {
                mask |= bit(value, index);
            }
        }
        return mask;
    }

    private static int number(String expression, String text, int index, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw invalid(expression, text, index, "is not a number");
        }
        if (parsed < FIELD_MIN[index] || parsed > FIELD_MAX[index]) {
            throw invalid(expression, text, index,
                    "needs a number between " + FIELD_MIN[index] + " and " + FIELD_MAX[index]);
        }
        return parsed;
    }

    private static long bit(int value, int index) {
        return 1L << (index == 4 ? value % 7 : value);
    }

    private static long fullMask(int from, int to) {
        long mask = 0;
        for (int value = from; value <= to; value++) mask |= 1L << value;
        return mask;
    }

    private static boolean get(long mask, int bit) {
        return (mask & (1L << bit)) != 0;
    }

    private static IllegalArgumentException invalid(String expression, String text, int index,
                                                    String reason) {
        return new IllegalArgumentException("Cron expression \"" + expression + "\" has the "
                + FIELD_NAMES[index] + " field \"" + text + "\" that " + reason);
    }
}
