package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.util.HashSet;
import java.util.Set;

/** Pure recurrence calculations; all dates use the Runtime's Asia/Shanghai boundary. */
public final class TaskRecurrence {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private TaskRecurrence() { }

    public record RepeatRule(String frequency, Set<Integer> weekdays, Integer dayOfMonth, LocalDate until) {
        public RepeatRule {
            if (frequency == null || frequency.isBlank()) throw new IllegalArgumentException("重复规则无效");
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

    public static LocalDate localDate(Instant value) { return value.atZone(ZONE).toLocalDate(); }
    public static Instant occurrenceAt(Instant anchor, LocalDate date) {
        ZonedDateTime source = anchor.atZone(ZONE);
        return date.atTime(source.toLocalTime()).atZone(ZONE).toInstant();
    }
    public static String occurrenceId(String planId, LocalDate date) {
        return "occurrence:" + planId + ":" + date;
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
        LocalDate start = localDate(after);
        if (!occurrenceAt(anchor, start).isAfter(after)) start = start.plusDays(1);
        LocalDate anchorDate = localDate(anchor);
        if (start.isBefore(anchorDate)) start = anchorDate;
        int horizon = "monthly".equals(rule.frequency()) ? 62 : 14;
        for (int i = 0; i <= horizon; i++) {
            LocalDate candidate = start.plusDays(i);
            if (matches(rule, candidate) && occurrenceAt(anchor, candidate).isAfter(after)) return candidate;
        }
        return null;
    }
}
