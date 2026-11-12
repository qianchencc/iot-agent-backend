package com.ehe.iotagent.iot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Principal-isolated raw meter facts. No method in this read boundary performs source collection. */
public interface EnergyHistory {
    /** The independently collected energy grant, not a grant inferred from a broad directory. */
    AuthorizedDeviceScope authorizedScope(RequestIdentity identity);

    /** Includes all samples in the range, the nearest left anchors, and optionally each meter's latest fact. */
    Window window(RequestIdentity identity, List<String> meterIds, Instant from, Instant to, boolean includeLatest);

    /** Existing raw history HTTP/Agent page, built from the same samples and coverage as statistics. */
    ObservationResponse read(ObservationRequest request);

    record Sample(String meterId, String sourceId, boolean sourceIdMissing, Instant readTime,
                  BigDecimal epf, BigDecimal epz, BigDecimal totalElectric, BigDecimal powerFactor,
                  boolean conflicted) {
        /** A fallback time is explicitly namespaced, never claimed to be a source record ID. */
        public String identity() { return (sourceIdMissing ? "time:" + readTime : "id:" + sourceId); }
    }

    record Window(List<Sample> samples, List<String> missingCoverageMeterIds, Map<String, Object> cache) {
        public Window {
            samples = List.copyOf(samples);
            missingCoverageMeterIds = List.copyOf(missingCoverageMeterIds);
            cache = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(cache));
        }
    }
}
