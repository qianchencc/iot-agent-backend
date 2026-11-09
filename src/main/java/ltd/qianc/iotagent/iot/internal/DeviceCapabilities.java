package ltd.qianc.iotagent.iot.internal;

import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.iot.AcCommandCapabilities;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;

/** Capability facts are entirely determined by the published kind/read-only flags and existing business rules. */
public final class DeviceCapabilities {
    private static final List<Map<String, Object>> LIGHTING = List.of(Map.of("closeOrOpen", 0), Map.of("closeOrOpen", 1));
    private static final List<Map<String, Object>> ACCESS = List.of(Map.of("controlType", 2));
    private static final DeviceCapabilityInspection.MapParameters AC = AcCommandCapabilities.parameters();
    private DeviceCapabilities() { }
    public static DeviceCapabilityInspection.DeviceCapability describe(AuthorizedDevice device) {
        List<Map<String, Object>> commands = device.observationOnly() ? List.of()
                : "lighting".equalsIgnoreCase(device.kind()) ? LIGHTING
                : "access".equalsIgnoreCase(device.kind()) ? ACCESS : List.of();
        return new DeviceCapabilityInspection.DeviceCapability(device.id(), device.kind(), commands,
                "ac".equalsIgnoreCase(device.kind()) && !device.observationOnly() ? AC : null);
    }
}
