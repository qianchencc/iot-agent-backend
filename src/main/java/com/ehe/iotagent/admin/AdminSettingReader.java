package com.ehe.iotagent.admin;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads one live long setting from the active admin configuration for real
 * consumers. Fail-open to the deployment fallback on any store problem so
 * operational loops keep running; the console still shows the saved value.
 */
public final class AdminSettingReader {
    private AdminSettingReader() {
    }

    public static java.util.function.LongSupplier longSetting(
            ObjectProvider<AdminConfigurationStore> stores, String key, long min, long max, long fallback) {
        return () -> {
            AdminConfigurationStore store = stores == null ? null : stores.getIfAvailable();
            if (store == null) return fallback;
            try {
                Object value = store.active().get(key);
                if (value instanceof Number number && number.doubleValue() == number.longValue()) {
                    long parsed = number.longValue();
                    if (parsed >= min && parsed <= max) return parsed;
                }
            } catch (RuntimeException ignored) {
                // Store outages fall back to the deployment value.
            }
            return fallback;
        };
    }
}
