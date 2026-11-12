package com.ehe.iotagent.iot;

import java.util.List;
import java.util.Map;

/** Runtime-compatible capability projection with no provider action identifiers. */
public record DeviceCapabilityInspection(List<String> candidateSetIds, List<DeviceCapability> devices) {
    public DeviceCapabilityInspection {
        candidateSetIds = List.copyOf(candidateSetIds == null ? List.of() : candidateSetIds);
        devices = List.copyOf(devices == null ? List.of() : devices);
    }
    public record DeviceCapability(String deviceId, String kind, List<Map<String, Object>> supportedCommands, MapParameters parameters) {
        public DeviceCapability {
            supportedCommands = List.copyOf(supportedCommands == null ? List.of() : supportedCommands);
        }
    }
    /** Mirrors the miniapp parameter contract: {@code parameters.temperature.{min,max,step}}. */
    public record MapParameters(Temperature temperature, List<String> modes, List<String> fanSpeeds) {
        public MapParameters {
            modes = List.copyOf(modes == null ? List.of() : modes);
            fanSpeeds = List.copyOf(fanSpeeds == null ? List.of() : fanSpeeds);
        }
    }
    public record Temperature(double min, double max, double step) {
        public Temperature {
            if (min > max || step <= 0) throw new IllegalArgumentException("invalid temperature range");
        }
    }
}
