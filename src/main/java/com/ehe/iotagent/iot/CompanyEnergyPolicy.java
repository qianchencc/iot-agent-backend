package com.ehe.iotagent.iot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Explicit, read-only tenant policy for converting epf deltas into energy. */
public record CompanyEnergyPolicy(String tenantCode, String version, Map<String, Meter> meters) {
    public CompanyEnergyPolicy {
        tenantCode = Objects.requireNonNull(tenantCode, "tenantCode");
        version = version == null || version.isBlank() ? null : version;
        meters = meters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(meters));
    }

    public record Meter(String unit, Double multiplier) {
        public Meter {
            unit = unit == null || unit.isBlank() ? null : unit;
            if (multiplier != null && (!Double.isFinite(multiplier) || multiplier < 0)) {
                throw new IllegalArgumentException("meter multiplier must be finite and non-negative");
            }
        }
    }
}
