package ltd.qianc.iotagent.admin.internal;

import java.util.Set;

/** Legacy Runtime knobs never controlled Java reads; they must not be offered as effective settings. */
final class RetiredCacheSettings {
    static final Set<String> NAMES = Set.of(
            "catalogTtlMs", "authoritativeStateTtlMs", "actionIdTtlMs", "energyReadTtlMs");

    private RetiredCacheSettings() { }
}
