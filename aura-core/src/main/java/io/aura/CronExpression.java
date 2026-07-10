package io.aura;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class CronExpression {

    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;
    private final String raw;

    public CronExpression(String expression) {
        this.raw = expression;
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Cron expression must have 5 fields: " + expression);
        }
        this.minutes = parseField(parts[0], 0, 59);
        this.hours = parseField(parts[1], 0, 23);
        this.daysOfMonth = parseField(parts[2], 1, 31);
        this.months = parseField(parts[3], 1, 12);
        this.daysOfWeek = parseField(parts[4], 0, 6);
    }

    public LocalDateTime nextFireTime(LocalDateTime from) {
        LocalDateTime candidate = from.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
        int maxIterations = 366 * 24 * 60;
        for (int i = 0; i < maxIterations; i++) {
            if (matches(candidate)) return candidate;
            candidate = candidate.plusMinutes(1);
        }
        throw new IllegalStateException("No next fire time found within 1 year for: " + raw);
    }

    public boolean matches(LocalDateTime time) {
        int dow = time.getDayOfWeek().getValue() % 7; // Mon=1..Sun=7 → 1..6,0
        return minutes.contains(time.getMinute())
                && hours.contains(time.getHour())
                && daysOfMonth.contains(time.getDayOfMonth())
                && months.contains(time.getMonthValue())
                && daysOfWeek.contains(dow);
    }

    @Override
    public String toString() {
        return raw;
    }

    static Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> result = new HashSet<>();
        for (String part : field.split(",")) {
            if (part.contains("/")) {
                String[] stepParts = part.split("/", 2);
                int step = Integer.parseInt(stepParts[1]);
                int start = stepParts[0].equals("*") ? min : Integer.parseInt(stepParts[0]);
                for (int i = start; i <= max; i += step) {
                    result.add(i);
                }
            } else if (part.contains("-")) {
                String[] range = part.split("-", 2);
                int from = Integer.parseInt(range[0]);
                int to = Integer.parseInt(range[1]);
                for (int i = from; i <= to; i++) {
                    result.add(i);
                }
            } else if (part.equals("*")) {
                for (int i = min; i <= max; i++) {
                    result.add(i);
                }
            } else {
                result.add(Integer.parseInt(part));
            }
        }
        return result;
    }
}
