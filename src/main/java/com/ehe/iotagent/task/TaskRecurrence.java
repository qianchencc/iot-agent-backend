package com.ehe.iotagent.task;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/** Pure recurrence calculations; all dates use the Runtime's Asia/Shanghai boundary. */
public final class TaskRecurrence {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> FREQUENCIES = Set.of("daily", "weekdays", "weekly", "monthly");

    private TaskRecurrence() { }

    public record RepeatRule(String frequency, Set<Integer> weekdays, Integer dayOfMonth, LocalDate until) {
        public RepeatRule {
            if (frequency == null || !FREQUENCIES.contains(frequency)) throw new IllegalArgumentException("重复规则无效");
            weekdays = weekdays == null ? Set.of() : Set.copyOf(weekdays);
            if ("weekly".equals(frequency) && (weekdays.isEmpty() || weekdays.stream().anyMatch(day -> day < 1 || day > 7))) {
                throw new IllegalArgumentException("请指定有效且不重复的周几");
            }
            if (!"weekly".equals(frequency) && !weekdays.isEmpty()) throw new IllegalArgumentException("请指定有效且不重复的周几");
            if ("monthly".equals(frequency) && (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31)) {
                throw new IllegalArgumentException("请指定有效的每月日期");
            }
            if (!"monthly".equals(frequency) && dayOfMonth != null) throw new IllegalArgumentException("请指定有效的每月日期");
        }
    }
    public record OccurrenceReference(String planId, LocalDate date) { }


    public static LocalDate localDate(Instant value) { return value.atZone(ZONE).toLocalDate(); }
    public static Instant occurrenceAt(Instant anchor, LocalDate date) {
        ZonedDateTime source = anchor.atZone(ZONE);
        return date.atTime(source.toLocalTime()).atZone(ZONE).toInstant();
    }
    public static String occurrenceId(String planId, LocalDate date) {
        return "occurrence:" + planId + ":" + date;
    }
    public static OccurrenceReference parseOccurrenceId(String value) {
        if (value == null || !value.startsWith("occurrence:")) return null;
        String remainder = value.substring("occurrence:".length());
        int separator = remainder.lastIndexOf(':');
        if (separator <= 0 || separator == remainder.length() - 1
                || remainder.substring(0, separator).contains(":")) return null;
        String planId = remainder.substring(0, separator);
        String date = remainder.substring(separator + 1);
        try {
            LocalDate parsed = LocalDate.parse(date);
            if (!parsed.toString().equals(date)) return null;
            return new OccurrenceReference(planId, parsed);
        } catch (RuntimeException error) {
            return null;
        }
    }
    public static boolean matches(RepeatRule rule, LocalDate date) {
        if (rule.until() != null && date.isAfter(rule.until())) return false;
        return switch (rule.frequency()) {
            case "daily" -> true;
            case "weekdays" -> date.getDayOfWeek().getValue() <= 5;
            case "weekly" -> rule.weekdays().contains(date.getDayOfWeek().getValue());
            case "monthly" -> date.getDayOfMonth() == rule.dayOfMonth();
            default -> false;
        };
    }
    public static LocalDate next(RepeatRule rule, Instant anchor, Instant after) {
        return next(rule, anchor, after, null);
    }
    /** Returns the first matching local date strictly after the instant and optional date. */
    public static LocalDate next(RepeatRule rule, Instant anchor, Instant after, LocalDate afterDateExclusive) {
        LocalDate anchorDate = localDate(anchor);
        LocalDate start = localDate(after);
        if (afterDateExclusive != null && !start.isAfter(afterDateExclusive)) start = afterDateExclusive.plusDays(1);
        if (!occurrenceAt(anchor, start).isAfter(after)) start = start.plusDays(1);
        if (start.isBefore(anchorDate)) start = anchorDate;
        int horizon = "monthly".equals(rule.frequency()) ? 62 : 7;
        for (int i = 0; i <= horizon; i++) {
            LocalDate candidate = start.plusDays(i);
            if (matches(rule, candidate) && occurrenceAt(anchor, candidate).isAfter(after)
                    && (afterDateExclusive == null || candidate.isAfter(afterDateExclusive))) return candidate;
        }
        return null;
    }

    /** Parse and validate a JSON weekday array without silently accepting duplicates. */
    public static Set<Integer> weekdays(java.util.Collection<Integer> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("请指定有效且不重复的周几");
        Set<Integer> result = new HashSet<>();
        for (Integer value : values) {
            if (value == null || value < 1 || value > 7 || !result.add(value)) {
                throw new IllegalArgumentException("请指定有效且不重复的周几");
            }
        }
        return Set.copyOf(result);
    }
}
