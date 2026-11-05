package ltd.qianc.iotagent.iot.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, identity-scoped cache of already-read energy readings.
 *
 * <p>The key is the authorized identity, never an upstream page number: adding a
 * record shifts pages but not the date ranges retained here. Coverage is tracked
 * per meter so one scope cannot certify another meter's data. Only a scan that
 * completed without truncation may extend coverage; failed or partial scans never
 * become a complete historical window.</p>
 */
final class EnergyHistoryStore {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration CURRENT_TTL = Duration.ofSeconds(60);
    /** Extra day fetched before a requested start to retain its interval anchor. */
    private static final Duration ANCHOR_LOOKBACK = Duration.ofDays(1);
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_SAMPLES = 60_000;
    private static final int MAX_COVERAGES_PER_METER = 8;

    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries =
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true);

    EnergyHistoryStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A reading already shaped by the composer's epf contract. */
    record Reading(String meterId, String id, Instant readTime, double value, Double powerFactor) {
        Reading {
            Objects.requireNonNull(meterId, "meterId");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(readTime, "readTime");
        }
    }

    /** One direct upstream range request; the range already includes anchor lookback. */
    record Fetch(Instant from, Instant to) {
        Fetch {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** Held readings plus whether every requested meter has fresh coverage for the window. */
    record View(List<Reading> readings, boolean covered) {
    }

    /**
     * Returns held readings when every requested meter has a fresh coverage interval
     * containing the window. {@code covered} is the completeness proof; the reading
     * list alone may still be useful for a partial response.
     */
    synchronized View view(String key, Collection<String> meterIds, Instant from, Instant to,
            Instant freshnessTo) {
        Entry entry = entries.get(key);
        if (entry == null || meterIds.isEmpty()) return new View(List.of(), false);
        Instant now = clock.instant();
        boolean covered = meterIds.stream().allMatch(meterId -> entry.coverages.getOrDefault(meterId, List.of())
                .stream().anyMatch(coverage -> coverage.covers(from, to) && fresh(coverage, freshnessTo, now)));
        return new View(entry.readings, covered);
    }

    /**
     * Narrows a miss to the oldest or newest uncovered part common to all requested
     * meters. A disjoint, stale or absent interval is read in full. The returned
     * range carries one anchor day before the requested start.
     */
    synchronized Fetch missing(String key, Collection<String> meterIds, Instant from, Instant to,
            Instant readTo, Instant freshnessTo) {
        Instant anchorFrom = from.minus(ANCHOR_LOOKBACK);
        Entry entry = entries.get(key);
        if (entry == null || meterIds.isEmpty()) return new Fetch(anchorFrom, readTo);
        Instant now = clock.instant();
        Coverage common = null;
        for (String meterId : meterIds) {
            Coverage chosen = null;
            for (Coverage coverage : entry.coverages.getOrDefault(meterId, List.of())) {
                if (!fresh(coverage, freshnessTo, now)) continue;
                if (coverage.to().isBefore(from) || coverage.from().isAfter(to)) continue;
                if (chosen == null || coverage.loadedAt().isAfter(chosen.loadedAt())) chosen = coverage;
            }
            if (chosen == null) return new Fetch(anchorFrom, readTo);
            if (common == null) {
                common = chosen;
            } else {
                Instant start = chosen.from().isAfter(common.from()) ? chosen.from() : common.from();
                Instant end = chosen.to().isBefore(common.to()) ? chosen.to() : common.to();
                if (start.isAfter(end)) return new Fetch(anchorFrom, readTo);
                Instant loadedAt = chosen.loadedAt().isAfter(common.loadedAt()) ? chosen.loadedAt() : common.loadedAt();
                common = new Coverage(start, end, loadedAt);
            }
        }
        if (common == null || common.covers(from, to)) return new Fetch(anchorFrom, readTo);
        if (common.from().isAfter(from) && !common.to().isBefore(to)) {
            Instant prefixTo = common.from().isBefore(to) ? common.from() : to;
            return new Fetch(anchorFrom, prefixTo);
        }
        if (common.to().isBefore(to) && !common.from().isAfter(from)) {
            Instant start = common.to().isAfter(anchorFrom) ? common.to() : anchorFrom;
            return new Fetch(start, readTo);
        }
        return new Fetch(anchorFrom, readTo);
    }

    /**
     * Adds readings and a complete coverage interval for each scanned meter. A
     * partial scan must never call this method.
     */
    synchronized void record(String key, Collection<String> meterIds, Instant from, Instant to,
            List<Reading> readings) {
        if (!from.isBefore(to) || meterIds.isEmpty()) return;
        Map<String, Reading> anchors = new LinkedHashMap<>();
        List<Reading> fetched = new ArrayList<>();
        for (Reading reading : readings) {
            if (reading.readTime().isBefore(from)) {
                Reading prior = anchors.get(reading.meterId());
                if (prior == null || reading.readTime().isAfter(prior.readTime())) {
                    anchors.put(reading.meterId(), reading);
                }
            } else if (!reading.readTime().isAfter(to)) {
                fetched.add(reading);
            }
        }
        // The last reading before the covered window is required to compute its first
        // interval; keeping it is part of the coverage, not extra history.
        fetched.addAll(anchors.values());
        Entry entry = entries.get(key);
        if (entry == null) {
            if (fetched.size() > MAX_SAMPLES) return;
            entry = new Entry();
            entries.put(key, entry);
            while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        }
        Map<String, Reading> merged = new LinkedHashMap<>();
        for (Reading reading : entry.readings) merged.put(readingKey(reading), reading);
        for (Reading reading : fetched) merged.put(readingKey(reading), reading);
        List<Reading> values = new ArrayList<>(merged.values());
        values.sort(readingOrder());
        if (values.size() > MAX_SAMPLES) {
            // Capacity pressure wins over reuse: keep only this verified range.
            if (fetched.size() > MAX_SAMPLES) {
                entries.remove(key);
                return;
            }
            values = new ArrayList<>(fetched);
            values.sort(readingOrder());
            entry.coverages.clear();
        }
        entry.readings = List.copyOf(values);

        Instant loadedAt = clock.instant();
        for (String meterId : meterIds) {
            List<Coverage> result = new ArrayList<>();
            Coverage incoming = new Coverage(from, to, loadedAt);
            for (Coverage existing : entry.coverages.getOrDefault(meterId, List.of())) {
                if (existing.to().isBefore(incoming.from()) || incoming.to().isBefore(existing.from())) {
                    result.add(existing);
                } else {
                    Instant mergedFrom = existing.from().isBefore(incoming.from()) ? existing.from() : incoming.from();
                    Instant mergedTo = existing.to().isAfter(incoming.to()) ? existing.to() : incoming.to();
                    boolean refreshed = !incoming.from().isAfter(existing.from()) && !incoming.to().isBefore(existing.to());
                    Instant mergedAt = refreshed ? loadedAt
                            : existing.loadedAt().isBefore(loadedAt) ? existing.loadedAt() : loadedAt;
                    incoming = new Coverage(mergedFrom, mergedTo, mergedAt);
                }
            }
            result.add(incoming);
            result.sort(Comparator.comparing(Coverage::loadedAt).reversed());
            entry.coverages.put(meterId, List.copyOf(
                    result.subList(0, Math.min(MAX_COVERAGES_PER_METER, result.size()))));
        }
    }

    synchronized void discard(String key) {
        entries.remove(key);
    }

    private boolean fresh(Coverage coverage, Instant freshnessTo, Instant now) {
        if (freshnessTo.isAfter(todayStart(now))) {
            return !now.isAfter(coverage.loadedAt().plus(CURRENT_TTL));
        }
        return !now.isAfter(nextShanghaiMidnight(coverage.loadedAt()));
    }

    private static Instant todayStart(Instant now) {
        return now.atZone(SHANGHAI).toLocalDate().atStartOfDay(SHANGHAI).toInstant();
    }

    private static Instant nextShanghaiMidnight(Instant loadedAt) {
        return loadedAt.atZone(SHANGHAI).toLocalDate().plusDays(1).atStartOfDay(SHANGHAI).toInstant();
    }

    private static String readingKey(Reading reading) {
        return reading.meterId() + '\u0000' + reading.id() + '\u0000' + reading.readTime();
    }

    private static Comparator<Reading> readingOrder() {
        return Comparator.comparing(Reading::readTime)
                .thenComparing(Reading::meterId).thenComparing(Reading::id);
    }

    private static final class Entry {
        private List<Reading> readings = List.of();
        private Map<String, List<Coverage>> coverages = new LinkedHashMap<>();
    }

    private record Coverage(Instant from, Instant to, Instant loadedAt) {
        boolean covers(Instant start, Instant end) {
            return !from.isAfter(start) && !to.isBefore(end);
        }
    }
}
