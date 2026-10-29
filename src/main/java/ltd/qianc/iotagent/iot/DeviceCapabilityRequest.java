package ltd.qianc.iotagent.iot;

import java.util.List;

/** Capability inspection can only reference IDs returned by the same authorized scope. */
public record DeviceCapabilityRequest(List<DeviceCapabilitySelection> candidateSets) {
    public DeviceCapabilityRequest {
        candidateSets = List.copyOf(candidateSets == null ? List.of() : candidateSets);
    }
    public record DeviceCapabilitySelection(String candidateSetId, List<String> deviceIds) {
        public DeviceCapabilitySelection {
            if (candidateSetId == null || candidateSetId.isBlank()) throw new IllegalArgumentException("candidateSetId is required");
            deviceIds = List.copyOf(deviceIds == null ? List.of() : deviceIds);
        }
    }
}
