package com.ehe.iotagent.iot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Local current observations and raw site history; neither read collects customer data. */
public interface EnvironmentHistory {
    DeviceStateReadResult current(RequestIdentity identity, List<String> deviceIds);

    ObservationResponse read(ObservationRequest request);

    /** deviceCode remains source metadata, not an asserted device or floor association. */
    record Sample(String sourceId, boolean sourceIdMissing, String deviceCode, String originalRecordedAt,
                  Instant recordedAt, BigDecimal temp, BigDecimal humidity, BigDecimal pm25,
                  BigDecimal pm10, BigDecimal pm1, boolean invalidValues, Object sourceQuality,
                  boolean conflicted) {
        public boolean qualityIssue() {
            return recordedAt == null || invalidValues || missingValues() || sourceQuality != null || conflicted;
        }

        public boolean missingValues() {
            return temp == null || humidity == null || pm25 == null || pm10 == null || pm1 == null;
        }

        public boolean metricEligible() {
            return recordedAt != null && sourceQuality == null && !conflicted;
        }
    }
}
